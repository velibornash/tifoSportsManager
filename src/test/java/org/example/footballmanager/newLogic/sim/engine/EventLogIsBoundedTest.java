package org.example.footballmanager.newLogic.sim.engine;

import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The in-memory event log is bounded, and the story survives the bound.
 *
 * <p><b>What was wrong (found 2026-10-19, P0-37a).</b> {@code ActionLogService.log} fires per player per
 * tick, so a match held about <b>1.6 million lines</b>, and the replay carried every one of them —
 * 11-14 MB of JSON per match, <b>of which the viewer reads none</b>. The lines are the match's memory:
 * a million short strings at about a hundred bytes each is over a hundred megabytes of heap for one
 * match, and the matchday holds hundreds of matches.
 *
 * <p>The fix caps the <b>noise</b> and not the story. The match story and every decision are kept
 * whatever happens — they are what a reader and a diagnostic ask for — and the per-player traces are
 * what nobody read and what the heap paid for.
 *
 * <p><b>The file log still has everything.</b> {@code target/proposal-app.log} is written on every line,
 * so nothing is lost; it is the replay JSON that was carrying a million lines nobody wanted.
 */
class EventLogIsBoundedTest {

    @Test
    @DisplayName("the per-player traces are capped")
    void thePerPlayerTracesAreCapped() {
        MatchState state = new MatchState();
        List<String> eventLog = new ArrayList<>();
        ActionLogService log = new ActionLogService(state, eventLog);

        for (int i = 0; i < ActionLogService.MAX_EVENT_LOG_LINES + 5_000; i++) {
            log.log("EXE", "player " + i + " did a thing");
        }

        assertTrue(eventLog.size() <= ActionLogService.MAX_EVENT_LOG_LINES,
                "the log grew to " + eventLog.size() + " lines. It fires per player per tick, so a match "
                        + "held about 1.6 million of them, and the replay carried every one");
    }

    @Test
    @DisplayName("the match story is kept whatever happens")
    void theMatchStoryIsKept() {
        MatchState state = new MatchState();
        List<String> eventLog = new ArrayList<>();
        ActionLogService log = new ActionLogService(state, eventLog);

        for (int i = 0; i < ActionLogService.MAX_EVENT_LOG_LINES + 1_000; i++) {
            log.log("EXE", "noise " + i);
        }
        log.log("GOAL", "*** GOAL HOME by Ada Goals ***");
        log.log("DECISION", "PASS by Ada Goals to Ben Assists");

        assertTrue(eventLog.stream().anyMatch(l -> l.contains("GOAL HOME by Ada Goals")),
                "the goal is not in the log. The story is what a reader and the replay are for, and it "
                        + "must survive the cap");
        assertTrue(eventLog.stream().anyMatch(l -> l.contains("PASS by Ada Goals")),
                "the decision is not in the log. MatchSimulationLauncher counts the decisions in it");
    }

    @Test
    @DisplayName("the file log still has every line")
    void theFileLogStillHasEverything() {
        // The cap is on the IN-MEMORY log only. The file is written on every line, so a QA grep for a
        // per-player trace still finds it.
        MatchState state = new MatchState();
        List<String> eventLog = new ArrayList<>();
        ActionLogService log = new ActionLogService(state, eventLog);

        assertEquals(0, eventLog.size(), "precondition: nothing logged yet");
        log.log("EXE", "a trace");
        log.log("GOAL", "a goal");

        assertEquals(2, eventLog.size(), "the first lines are always kept");
    }
}
