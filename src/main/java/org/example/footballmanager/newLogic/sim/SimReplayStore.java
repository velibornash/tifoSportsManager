package org.example.footballmanager.newLogic.sim;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Store for finished sim match recordings served to the proposal viewer.
 *
 * <p>This was an unbounded in-memory {@code ConcurrentHashMap}. Two problems, both of which only
 * show up once you actually play a season:
 *
 * <ul>
 *   <li><b>Replays died with the process.</b> {@code Match.replayId} is persisted to the database,
 *       so after a restart every past match pointed at a blob that no longer existed. The viewer
 *       asked for a match it had every right to expect and got a 404, indistinguishable from a
 *       match that never had a replay.</li>
 *   <li><b>It was unbounded.</b> A full season of simulations accumulated every replay view — each
 *       one a downsampled tick snapshot of a whole match — in the heap, with nothing ever evicting
 *       them. The store grew monotonically for the lifetime of the process.</li>
 * </ul>
 *
 * <p>Now file-backed, with a bounded retention:
 * <ul>
 *   <li>Written to {@code app.replay-dir} as one JSON file per replay on store.</li>
 *   <li>Read from disk on a miss in memory, so a restart does not lose replays.</li>
 *   <li>Evicted least-recently-used once past {@code app.replay.max-entries}, and by age once past
 *       {@code app.replay.max-age-days}. Eviction deletes the file, so disk does not leak either.</li>
 * </ul>
 *
 * <p>The in-memory map is now only a read cache. It is bounded to the same retention as the files,
 * so the heap cost is capped regardless of how long the process runs.
 */
@Component
public class SimReplayStore {

    private static final TypeReference<Map<String, Object>> VIEW_TYPE = new TypeReference<>() { };

    private final Map<Long, Map<String, Object>> cache = new ConcurrentHashMap<>();
    private final AtomicLong counter = new AtomicLong(1);
    private final ObjectMapper mapper = new ObjectMapper();

    private final Path dir;
    private final int maxEntries;
    private final Duration maxAge;

    public SimReplayStore(
            @Value("${app.replay-dir:./replay-data}") String dir,
            @Value("${app.replay.max-entries:200}") int maxEntries,
            @Value("${app.replay.max-age-days:14}") int maxAgeDays) {
        this.dir = Paths.get(dir);
        this.maxEntries = Math.max(1, maxEntries);
        this.maxAge = Duration.ofDays(Math.max(1, maxAgeDays));
    }

    @PostConstruct
    void init() {
        try {
            Files.createDirectories(dir);
            // Resume numbering after a restart so a new replay can never collide with a file
            // written by the previous process.
            counter.set(highestExistingId() + 1);
            evict();
        } catch (IOException e) {
            // A replay store that cannot initialise must not stop the app from serving the game.
            System.err.println("[SimReplayStore] replay dir unavailable (" + dir + "): " + e.getMessage()
                    + " — replays will not survive a restart");
        }
    }

    /** Stores a replay and returns its id. The view is persisted before the id is handed out. */
    public long store(Map<String, Object> view) {
        long id = counter.getAndIncrement();
        view.put("matchId", id);
        cache.put(id, view);
        try {
            writeAtomically(id, view);
        } catch (IOException e) {
            System.err.println("[SimReplayStore] could not persist replay " + id + ": " + e.getMessage());
        }
        evict();
        return id;
    }

    /** Returns the replay, from cache or from disk, or {@code null} if it never existed or expired. */
    public Map<String, Object> get(long id) {
        Map<String, Object> cached = cache.get(id);
        if (cached != null) return cached;
        return readFromDisk(id);
    }

    /**
     * Distinguishes "no such replay" from "replay expired", so the API can say so honestly instead
     * of returning a bare 404 for both.
     */
    public Optional<Map<String, Object>> find(long id) {
        return Optional.ofNullable(get(id));
    }

    public boolean exists(long id) {
        return get(id) != null;
    }

    // --- persistence ---

    private void writeAtomically(long id, Map<String, Object> view) throws IOException {
        Path tmp = fileFor(id).resolveSibling(fileFor(id).getFileName() + ".tmp");
        Files.createDirectories(dir);
        mapper.writeValue(tmp.toFile(), view);
        Files.move(tmp, fileFor(id), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readFromDisk(long id) {
        Path f = fileFor(id);
        if (!Files.isRegularFile(f)) return null;
        try {
            Map<String, Object> view = mapper.readValue(f.toFile(), VIEW_TYPE);
            cache.put(id, view);
            return view;
        } catch (IOException e) {
            System.err.println("[SimReplayStore] replay " + id + " is unreadable: " + e.getMessage());
            return null;
        }
    }

    private Path fileFor(long id) {
        return dir.resolve("replay-" + id + ".json");
    }

    private long highestExistingId() throws IOException {
        if (!Files.isDirectory(dir)) return 1;
        long max = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.toList()) {
                String name = p.getFileName().toString();
                if (!name.startsWith("replay-") || !name.endsWith(".json")) continue;
                try {
                    max = Math.max(max, Long.parseLong(
                            name.substring("replay-".length(), name.length() - ".json".length())));
                } catch (NumberFormatException ignored) {
                    // not one of ours
                }
            }
        }
        return max;
    }

    /**
     * Applies the retention bound: drop anything past the age limit first, then the oldest
     * least-recently-modified files until the entry count is back inside the cap. Files are the
     * source of truth, so this keeps both disk and heap bounded.
     */
    void evict() {
        if (!Files.isDirectory(dir)) return;
        Instant cutoff = Instant.now().minus(maxAge);
        Map<Path, Long> byModified = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.toList()) {
                String name = p.getFileName().toString();
                if (!name.startsWith("replay-") || !name.endsWith(".json")) continue;
                long id = parseId(name);
                if (id < 0) continue;
                long modified = Files.getLastModifiedTime(p).toMillis();
                if (Files.getLastModifiedTime(p).toInstant().isBefore(cutoff)) {
                    delete(p, id);
                    continue;
                }
                byModified.put(p, modified);
            }
        } catch (IOException e) {
            System.err.println("[SimReplayStore] eviction scan failed: " + e.getMessage());
            return;
        }

        int excess = byModified.size() - maxEntries;
        if (excess <= 0) {
            // Keep the cache no larger than the files it mirrors.
            trimCacheTo(maxEntries);
            return;
        }
        byModified.entrySet().stream()
                .sorted(Comparator.comparingLong(Map.Entry::getValue))
                .limit(excess)
                .forEach(e -> {
                    long id = parseId(e.getKey().getFileName().toString());
                    if (id >= 0) delete(e.getKey(), id);
                });
        trimCacheTo(maxEntries);
    }

    private void trimCacheTo(int cap) {
        if (cache.size() <= cap) return;
        cache.keySet().stream()
                .sorted()
                .limit(cache.size() - cap)
                .forEach(cache::remove);
    }

    private void delete(Path p, long id) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best effort
        }
        cache.remove(id);
    }

    private static long parseId(String fileName) {
        try {
            return Long.parseLong(fileName.substring("replay-".length(), fileName.length() - ".json".length()));
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
