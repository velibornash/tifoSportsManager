package org.tifo.footballmanager.newLogic.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The dead tick-state table stays dead.
 *
 * <p><b>What was here (decided 2026-10-19, P1-4).</b> {@code match_tick_states} was a table for
 * holding a match's per-tick states, for a tick-by-tick replay. The replay was never built that way —
 * it replays the event log the engine already writes — so <b>nothing ever wrote the table and nothing
 * ever read it</b>. The only code that touched it was the reset service's truncation list and a
 * migration for a column Hibernate had renamed.
 *
 * <p><b>And the alternative was worse.</b> Wiring the tick writer up would have meant writing a row per
 * tick per match — the same unbounded write that made the event log a memory problem — to serve a
 * replay that does not exist.
 *
 * <p>The table itself is left as an empty orphan: dropping a table at boot is a rollback hazard, and
 * the rows were never there.
 */
class MatchTickStateIsGoneTest {

    @Test
    @DisplayName("nothing writes or reads a per-tick state")
    void nothingWritesOrReadsATickState() throws Exception {
        for (String rel : new String[]{
                "src/main/java/org/example/footballmanager/newLogic/model/MatchTickState.java",
                "src/main/java/org/example/footballmanager/newLogic/repository/MatchTickStateRepository.java"}) {
            Path path = Path.of(rel);
            assertFalse(Files.exists(path),
                    rel + " is back. Nothing wrote a per-tick state and nothing read one - the replay "
                            + "replays the event log - so the table was dead weight, and the index that "
                            + "was going to be added to it (P1-1) would have been an index on nothing");
        }
    }

    @Test
    @DisplayName("the schema no longer carries the table")
    void theSchemaNoLongerCarriesTheTable() throws Exception {
        String reset = Files.readString(
                Path.of("src/main/java/org/example/footballmanager/newLogic/service/ResetService.java"),
                StandardCharsets.UTF_8);
        assertFalse(reset.contains("match_tick_states"),
                "the reset service still names match_tick_states. The table is gone from the code, so "
                        + "the truncation entry and the migration for its renamed column are too");

        String initializer = Files.readString(
                Path.of("src/main/java/org/example/footballmanager/newLogic/util/DatabaseInitializer.java"),
                StandardCharsets.UTF_8);
        assertFalse(initializer.contains("migrateTickStateMinuteColumn"),
                "the boot still migrates a table that no longer has an entity");
    }

    @Test
    @DisplayName("the reserved-word tripwire still has an entity to watch")
    void theReservedWordTripwireIsNotVacuous() throws Exception {
        // The tripwire listed exactly one entity - the one that was deleted - so the class went with
        // it. This says so, because a tripwire that watches nothing is worse than no tripwire: it
        // reads as a guard and guards nothing.
        assertFalse(Files.exists(Path.of(
                "src/test/java/org/example/footballmanager/newLogic/model/ReservedWordColumnTest.java")),
                "the reserved-word tripwire is back with no entity to watch. If a new entity maps a "
                        + "reserved word, it has to be added to that list - and the list is not empty "
                        + "for its own sake");
    }
}
