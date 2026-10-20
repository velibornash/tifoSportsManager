package org.tifo.footballmanager.newLogic.service;

import org.tifo.footballmanager.newLogic.model.Country;
import org.tifo.footballmanager.newLogic.model.CountryNamePoolEntry;
import org.tifo.footballmanager.newLogic.repository.CountryNamePoolEntryRepository;
import org.tifo.footballmanager.newLogic.repository.CountryRepository;
import org.tifo.footballmanager.newLogic.util.naming.CountryNamePool;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The per-country name pools, as the admin panel edits them (redesign.md R-23).
 *
 * <p>Two copies, on purpose:
 * <ul>
 *   <li>the **files** under {@code classpath:/names/<ISO>.json} - what a world build starts from, and
 *       what survives a <i>Reset DB</i> (the tables are truncated with everything else, and the pools
 *       are content, not state);</li>
 *   <li>the **tables** - the working copy the admin panel edits, one country at a time.</li>
 * </ul>
 *
 * <p>A country with rows in the table uses them; a country with none falls back to its file, and a
 * country with neither keeps the names it always had.
 */
@Service
public class CountryNamePoolService {

    private final CountryNamePoolEntryRepository entries;
    private final CountryRepository countries;
    private final CountryNamePool files;

    public CountryNamePoolService(CountryNamePoolEntryRepository entries, CountryRepository countries,
                                  CountryNamePool files) {
        this.entries = entries;
        this.countries = countries;
        this.files = files;
    }

    /**
     * A country's pools, as the admin panel shows them.
     *
     * @param isoCode the country's ISO code
     * @return the four lists, and where they came from
     */
    @Transactional(readOnly = true)
    public Map<String, Object> forCountry(String isoCode) {
        Map<String, Object> out = new LinkedHashMap<>();
        String code = isoCode == null ? "" : isoCode.trim().toUpperCase(java.util.Locale.ROOT);
        out.put("iso", code);

        Optional<Country> country = countries.findByIsoCode(code);
        if (country.isPresent()) {
            Map<CountryNamePoolEntry.Category, List<String>> byCategory = new LinkedHashMap<>();
            for (CountryNamePoolEntry entry : entries.findByCountryIdOrderByCategoryAscValueAsc(country.get().getId())) {
                byCategory.computeIfAbsent(entry.getCategory(), key -> new ArrayList<>()).add(entry.getValue());
            }
            boolean any = byCategory.values().stream().anyMatch(list -> !list.isEmpty());
            out.put("source", any ? "database" : "file");
            out.put("clubs", byCategory.getOrDefault(CountryNamePoolEntry.Category.CLUB, List.of()));
            out.put("cities", byCategory.getOrDefault(CountryNamePoolEntry.Category.CITY, List.of()));
            out.put("firstNames", byCategory.getOrDefault(CountryNamePoolEntry.Category.FIRST_NAME, List.of()));
            out.put("lastNames", byCategory.getOrDefault(CountryNamePoolEntry.Category.LAST_NAME, List.of()));
            if (any) {
                return out;
            }
        }

        // No rows: the file is the pool.
        CountryNamePool.Pool pool = files.forCountry(code).orElse(CountryNamePool.Pool.empty());
        out.put("source", pool.isEmpty() ? "none" : "file");
        out.put("clubs", pool.clubs());
        out.put("cities", pool.cities());
        out.put("firstNames", pool.firstNames());
        out.put("lastNames", pool.lastNames());
        return out;
    }

    /**
     * Saves a country's pools, replacing whatever was there.
     *
     * @param isoCode the country's ISO code
     * @param clubs the club names
     * @param cities the cities
     * @param firstNames the players' first names
     * @param lastNames the players' last names
     * @return how many names were written
     */
    @Transactional
    public int save(String isoCode, List<String> clubs, List<String> cities, List<String> firstNames,
                    List<String> lastNames) {
        String code = isoCode == null ? "" : isoCode.trim().toUpperCase(java.util.Locale.ROOT);
        Country country = countries.findByIsoCode(code)
                .orElseThrow(() -> new IllegalArgumentException("That country is not set up in this game yet."));
        entries.deleteByCountryId(country.getId());
        int written = 0;
        written += write(country, CountryNamePoolEntry.Category.CLUB, clubs);
        written += write(country, CountryNamePoolEntry.Category.CITY, cities);
        written += write(country, CountryNamePoolEntry.Category.FIRST_NAME, firstNames);
        written += write(country, CountryNamePoolEntry.Category.LAST_NAME, lastNames);
        return written;
    }

    /**
     * Loads a country's file pool into the tables.
     *
     * <p>Called when a country is activated, so that the names the owner set for it survive the
     * world the activation builds.
     */
    @Transactional
    public int seedFromFile(String isoCode) {
        String code = isoCode == null ? "" : isoCode.trim().toUpperCase(java.util.Locale.ROOT);
        Country country = countries.findByIsoCode(code).orElse(null);
        if (country == null) return 0;
        CountryNamePool.Pool pool = files.forCountry(code).orElse(CountryNamePool.Pool.empty());
        if (pool.isEmpty()) return 0;
        entries.deleteByCountryId(country.getId());
        int written = 0;
        written += write(country, CountryNamePoolEntry.Category.CLUB, pool.clubs());
        written += write(country, CountryNamePoolEntry.Category.CITY, pool.cities());
        written += write(country, CountryNamePoolEntry.Category.FIRST_NAME, pool.firstNames());
        written += write(country, CountryNamePoolEntry.Category.LAST_NAME, pool.lastNames());
        return written;
    }

    /**
     * The pool a country actually uses: the rows the admin saved, or the file, or nothing.
     *
     * <p>This is what the seeders ask, so that the names the owner set in the admin panel are the
     * names the world is built from - and a country nobody touched keeps the file's, and a country
     * with neither keeps what it always had.
     */
    @Transactional(readOnly = true)
    public CountryNamePool.Pool poolFor(String isoCode) {
        String code = isoCode == null ? "" : isoCode.trim().toUpperCase(java.util.Locale.ROOT);
        Optional<Country> country = countries.findByIsoCode(code);
        if (country.isPresent()) {
            Map<CountryNamePoolEntry.Category, List<String>> byCategory = new LinkedHashMap<>();
            for (CountryNamePoolEntry entry : entries.findByCountryIdOrderByCategoryAscValueAsc(country.get().getId())) {
                byCategory.computeIfAbsent(entry.getCategory(), key -> new ArrayList<>()).add(entry.getValue());
            }
            if (byCategory.values().stream().anyMatch(list -> !list.isEmpty())) {
                return new CountryNamePool.Pool(
                        byCategory.getOrDefault(CountryNamePoolEntry.Category.CLUB, List.of()),
                        byCategory.getOrDefault(CountryNamePoolEntry.Category.CITY, List.of()),
                        byCategory.getOrDefault(CountryNamePoolEntry.Category.FIRST_NAME, List.of()),
                        byCategory.getOrDefault(CountryNamePoolEntry.Category.LAST_NAME, List.of()));
            }
        }
        return files.forCountry(code).orElse(CountryNamePool.Pool.empty());
    }

    /** A random club name for a country, from the pool that is actually in force. */
    public String randomClubName(String isoCode, java.util.Random random) {
        return pick(poolFor(isoCode).clubs(), random);
    }

    /** A random city for a country, from the pool that is actually in force. */
    public String randomCity(String isoCode, java.util.Random random) {
        return pick(poolFor(isoCode).cities(), random);
    }

    /** A random first name for a country, from the pool that is actually in force. */
    public String randomFirstName(String isoCode, java.util.Random random) {
        return pick(poolFor(isoCode).firstNames(), random);
    }

    /** A random last name for a country, from the pool that is actually in force. */
    public String randomLastName(String isoCode, java.util.Random random) {
        return pick(poolFor(isoCode).lastNames(), random);
    }

    /**
     * One name out of a list.
     *
     * <p>The low bits, not {@code nextInt(size)}: Java's {@link java.util.Random} is a linear
     * congruential generator and for small consecutive seeds its first output's high bits barely move
     * ({@code new Random(0..5).nextInt(32)} returns the same number every time - measured). The world is
     * built from seeds like that, one per club, so every club would draw the same name. The full
     * {@code nextInt()} varies in its low bits, so the index is taken from there.
     */
    private String pick(List<String> values, java.util.Random random) {
        if (values == null || values.isEmpty() || random == null) return null;
        int index = Math.floorMod(random.nextInt(), values.size());
        return values.get(index);
    }

    private int write(Country country, CountryNamePoolEntry.Category category, List<String> values) {
        if (values == null) return 0;
        int written = 0;
        // **Duplicates are dropped, not refused.** The names arrive as one typed line split on
        // commas, and a person repeating a name is a person making a typo, not a request for an
        // error: the save failed on a repeated "Sloga Kraljevo" before this, and a save that refuses
        // because a name was typed twice is a save that loses the whole list.
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String value : values) {
            if (value == null) continue;
            String text = value.trim();
            if (text.isEmpty()) continue;
            String key = text.toLowerCase(java.util.Locale.ROOT);
            if (!seen.add(key)) continue;
            CountryNamePoolEntry entry = new CountryNamePoolEntry();
            entry.setCountry(country);
            entry.setCategory(category);
            entry.setValue(text);
            entries.save(entry);
            written++;
        }
        return written;
    }
}
