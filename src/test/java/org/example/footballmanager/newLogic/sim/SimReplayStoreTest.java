package org.example.footballmanager.newLogic.sim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 1.10 — replays survive a restart, and the store is bounded.
 *
 * <p>The store used to be an unbounded in-memory map, so a restart expired every replay in the
 * database at once and a season of matches grew the heap monotonically. These tests pin both halves:
 * durability across a fresh instance over the same directory, and retention actually evicting.
 */
class SimReplayStoreTest {

    private static Map<String, Object> view(String home, int goals) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("home", home);
        v.put("away", "Away United");
        v.put("homeGoals", goals);
        v.put("snapshots", java.util.List.of(Map.of("tick", 0), Map.of("tick", 40)));
        return v;
    }

    @Test
    @DisplayName("a replay is readable from a brand new store instance - it survives a restart")
    void survivesRestart(@TempDir Path dir) {
        SimReplayStore first = new SimReplayStore(dir.toString(), 200, 14);
        first.init();
        long id = first.store(view("Home FC", 2));

        // A completely separate instance over the same directory, as after an app restart.
        SimReplayStore afterRestart = new SimReplayStore(dir.toString(), 200, 14);
        afterRestart.init();

        Map<String, Object> loaded = afterRestart.get(id);
        assertNotNull(loaded, "the replay must still exist after a restart");
        assertEquals("Home FC", loaded.get("home"));
        assertEquals(2, ((Number) loaded.get("homeGoals")).intValue());
    }

    @Test
    @DisplayName("restarted numbering cannot collide with a replay from the previous process")
    void idsDoNotCollideAfterRestart(@TempDir Path dir) {
        SimReplayStore first = new SimReplayStore(dir.toString(), 200, 14);
        first.init();
        long a = first.store(view("A", 1));
        long b = first.store(view("B", 1));

        SimReplayStore afterRestart = new SimReplayStore(dir.toString(), 200, 14);
        afterRestart.init();
        long c = afterRestart.store(view("C", 1));

        assertTrue(c > b, "new ids must continue past the previous process: " + a + "," + b + "," + c);
        assertEquals("C", afterRestart.get(c).get("home"));
    }

    @Test
    @DisplayName("retention is bounded - the oldest replays are evicted, not kept forever")
    void retentionIsBounded(@TempDir Path dir) throws IOException {
        SimReplayStore store = new SimReplayStore(dir.toString(), 5, 14);
        store.init();
        long[] ids = new long[12];
        for (int i = 0; i < ids.length; i++) ids[i] = store.store(view("M" + i, i));

        long files = Files.list(dir).filter(p -> p.getFileName().toString().endsWith(".json")).count();
        assertEquals(5, files, "only the newest 5 replay files should survive");

        // The most recent replays must be the ones kept.
        assertNotNull(store.get(ids[11]), "newest replay must be kept");
        assertNull(store.get(ids[0]), "oldest replay must have been evicted");
    }

    @Test
    @DisplayName("a missing replay is absent, not an error")
    void missingReplayIsNull(@TempDir Path dir) {
        SimReplayStore store = new SimReplayStore(dir.toString(), 200, 14);
        store.init();
        assertNull(store.get(9999L));
        assertFalse(store.exists(9999L));
        assertTrue(store.find(9999L).isEmpty());
    }

    @Test
    @DisplayName("an unknown replay id is reported as expired rather than throwing")
    void findReportsExpiry(@TempDir Path dir) {
        SimReplayStore store = new SimReplayStore(dir.toString(), 200, 14);
        store.init();
        store.store(view("Home FC", 1));
        assertTrue(store.find(4242L).isEmpty(),
                "an id that never existed must read as absent so the API can answer 410 GONE");
    }
}
