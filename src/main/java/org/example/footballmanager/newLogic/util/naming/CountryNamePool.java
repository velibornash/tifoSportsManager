package org.example.footballmanager.newLogic.util.naming;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The per-country name pools (redesign.md R-23).
 *
 * <p>The owner asked for a structure where, <b>for every country separately</b>, the club names, the
 * cities and the players' first and last names can be set, and the seeder draws from them at
 * random. Without it, every club in the world was called after its country code
 * ({@code "GER First FC01"} from {@code PyramidBuilder.clubName()}) and every player in the world
 * drew from one hardcoded Serbian list.
 *
 * <p><b>Why the pools are files and not only rows in a table:</b> a <i>Reset DB</i> truncates the
 * database, and the owner resets the world often. The pools are content, not state, so they have to
 * survive a reset. The files under {@code classpath:/names/<ISO>.json} are that copy; the database
 * holds the working copy the admin panel edits, and a build re-seeds the tables from the files.
 *
 * <p>A country with no file simply has no pool: the callers fall back to what they did before, so
 * adding pools is additive and nothing regresses.
 */
@Component
public class CountryNamePool {

    private static final Logger log = LoggerFactory.getLogger(CountryNamePool.class);
    private static final String DIRECTORY = "names/";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Optional<Pool>> cache = new ConcurrentHashMap<>();

    /**
     * One country's pools. Empty lists mean "nothing set for this kind of name", and the caller
     * falls back for that kind only - a country can have club names and no cities.
     */
    public record Pool(List<String> clubs, List<String> cities, List<String> firstNames, List<String> lastNames) {

        public static Pool empty() {
            return new Pool(List.of(), List.of(), List.of(), List.of());
        }

        public boolean hasClubs() { return !clubs.isEmpty(); }
        public boolean hasCities() { return !cities.isEmpty(); }
        public boolean hasFirstNames() { return !firstNames.isEmpty(); }
        public boolean hasLastNames() { return !lastNames.isEmpty(); }
        public boolean isEmpty() {
            return !hasClubs() && !hasCities() && !hasFirstNames() && !hasLastNames();
        }
    }

    /**
     * The pool for a country, if it has one.
     *
     * @param isoCode the country's ISO code, in any case
     * @return the pool, or empty when the country has none
     */
    public Optional<Pool> forCountry(String isoCode) {
        if (isoCode == null || isoCode.isBlank()) return Optional.empty();
        String code = isoCode.trim().toUpperCase(Locale.ROOT);
        return cache.computeIfAbsent(code, this::read);
    }

    /** A random club name from the country's pool, or empty when it has none. */
    public Optional<String> randomClubName(String isoCode, Random random) {
        return pick(forCountry(isoCode).map(Pool::clubs).orElse(List.of()), random);
    }

    /** A random city from the country's pool, or empty when it has none. */
    public Optional<String> randomCity(String isoCode, Random random) {
        return pick(forCountry(isoCode).map(Pool::cities).orElse(List.of()), random);
    }

    /** A random first name from the country's pool, or empty when it has none. */
    public Optional<String> randomFirstName(String isoCode, Random random) {
        return pick(forCountry(isoCode).map(Pool::firstNames).orElse(List.of()), random);
    }

    /** A random last name from the country's pool, or empty when it has none. */
    public Optional<String> randomLastName(String isoCode, Random random) {
        return pick(forCountry(isoCode).map(Pool::lastNames).orElse(List.of()), random);
    }

    /**
     * One name out of the pool.
     *
     * <p><b>The low bits, not {@code nextInt(size)}.</b> Java's {@link Random} is a linear congruential
     * generator, and for <b>small consecutive seeds</b> its first output's <i>high</i> bits barely move:
     * {@code new Random(0..5).nextInt(32)} returns the same number every time (measured: 23, six times
     * over). The world is built from seeds like that - one per club - so every club in the world drew
     * the <i>same</i> name from the pool, which is the opposite of what a pool is for. The full
     * {@code nextInt()} varies in its low bits, so the index is taken from there.
     */
    private Optional<String> pick(List<String> values, Random random) {
        if (values.isEmpty() || random == null) return Optional.empty();
        int index = Math.floorMod(random.nextInt(), values.size());
        return Optional.of(values.get(index));
    }

    private Optional<Pool> read(String code) {
        String path = DIRECTORY + code + ".json";
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            return Optional.empty();
        }
        try (InputStream in = resource.getInputStream()) {
            JsonNode node = mapper.readTree(in);
            Pool pool = new Pool(
                    strings(node, "clubs"),
                    strings(node, "cities"),
                    strings(node, "firstNames"),
                    strings(node, "lastNames"));
            if (pool.isEmpty()) {
                log.warn("The name pool for {} is empty - the country keeps the old names", code);
                return Optional.empty();
            }
            log.info("Name pool for {}: {} clubs, {} cities, {} first names, {} last names", code,
                    pool.clubs().size(), pool.cities().size(), pool.firstNames().size(), pool.lastNames().size());
            return Optional.of(pool);
        } catch (Exception e) {
            // A malformed pool must not stop a world from being built: the country falls back to the
            // old names, and the owner is told which file to fix.
            log.error("The name pool {} could not be read - the country keeps the old names: {}", path,
                    e.getMessage());
            return Optional.empty();
        }
    }

    private List<String> strings(JsonNode node, String field) {
        JsonNode values = node == null ? null : node.get(field);
        if (values == null || !values.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonNode value : values) {
            if (value == null) continue;
            String text = value.asText("").trim();
            if (!text.isEmpty()) out.add(text);
        }
        return List.copyOf(out);
    }
}
