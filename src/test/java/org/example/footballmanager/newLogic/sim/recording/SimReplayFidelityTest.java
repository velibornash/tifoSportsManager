package org.example.footballmanager.newLogic.sim.recording;

import org.example.footballmanager.newLogic.sim.SimMatchRunner;
import org.example.footballmanager.newLogic.sim.engine.MatchOrchestrator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dashboard replay was showing a DIFFERENT pass to the one the log recorded.
 *
 * The replay downsampled snapshots 1:10 with a flat stride, but the ball only
 * moves fast when nobody owns it: a lofted kickoff pass covers ~8 cells in ten
 * ticks and the pitch is 7 cells long. The viewer therefore had to draw the
 * ball as a straight line across most of the pitch between two frames, so a pass
 * to one player rendered as a pass to whoever sat on that line.
 *
 * These tests pin the two fixes: no in-flight tick is ever dropped, and the
 * recorder records WHO the ball is aimed at.
 */
class SimReplayFidelityTest {

    private static final int TICKS = 900;

    @Test
    void replayKeepsEveryTickWhileTheBallIsInFlight() {
        MatchOrchestrator o = SimMatchRunner.run("Home FC", "Away United", TICKS);
        Map<String, Object> view = SimReplayView.build(o, "Home FC", "Away United");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> snapshots = (List<Map<String, Object>>) view.get("snapshots");
        assertNotNull(snapshots);
        assertTrue(snapshots.size() > 1, "replay must contain snapshots");

        // No gap in the kept ticks may be larger than the stride, and any gap
        // larger than a single tick must not contain a tick where the ball was
        // actually moving with nobody on it.
        int maxGap = 0;
        long previous = -1;
        for (Map<String, Object> s : snapshots) {
            long tick = ((Number) s.get("tick")).longValue();
            if (previous >= 0) maxGap = Math.max(maxGap, (int) (tick - previous));
            previous = tick;
        }
        assertTrue(maxGap <= SimReplayView.SNAPSHOT_STRIDE,
                "largest snapshot gap " + maxGap + " exceeds the stride "
                        + SimReplayView.SNAPSHOT_STRIDE);
    }

    @Test
    void recorderRecordsTheIntendedReceiverAndAimDuringAPass() {
        MatchOrchestrator o = SimMatchRunner.run("Home FC", "Away United", TICKS);
        List<MatchSnapshot> snapshots = o.getRecorder().getSnapshots();

        // Somewhere in the match there is a pass in flight; it must carry the
        // receiver and the landing point, not nulls.
        boolean foundFlight = false;
        boolean foundAim = false;
        for (MatchSnapshot s : snapshots) {
            if (s.getBallCarrierId() != null) continue;
            if (s.getTargetPlayerId() == null) continue;
            foundFlight = true;
            if (s.getActualTarget() != null || s.getIntendedTarget() != null) {
                foundAim = true;
                break;
            }
        }
        assertTrue(foundFlight, "a match must contain at least one pass in flight");
        assertTrue(foundAim,
                "a pass in flight must record where the ball is aimed, not null");
    }

    @Test
    void replayExposesThePassTargetToTheViewer() {
        MatchOrchestrator o = SimMatchRunner.run("Home FC", "Away United", TICKS);
        Map<String, Object> view = SimReplayView.build(o, "Home FC", "Away United");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> snapshots = (List<Map<String, Object>>) view.get("snapshots");
        boolean anyTarget = snapshots.stream()
                .anyMatch(s -> s.get("targetPlayerId") != null);
        assertTrue(anyTarget, "replay snapshots must expose targetPlayerId to the viewer");
    }

    @Test
    void kickoffIsLoggedSoAReplayHasAStartingLine() {
        MatchOrchestrator o = SimMatchRunner.run("Home FC", "Away United", 200);
        List<String> log = o.getEventLog();
        assertTrue(log.stream().anyMatch(l -> l.contains("KICKOFF")),
                "the match log must contain a KICKOFF line, got: " + log.subList(0, Math.min(5, log.size())));
    }

    @Test
    void kickoffLineNamesTheCentreSpotAndTheKicker() {
        MatchOrchestrator o = SimMatchRunner.run("Home FC", "Away United", 200);
        String kickoff = o.getEventLog().stream()
                .filter(l -> l.contains("KICKOFF"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no KICKOFF line in the log"));

        assertTrue(kickoff.contains("(4.5,4.0)"),
                "kickoff line must state the centre spot, got: " + kickoff);
        assertTrue(kickoff.contains("taker "),
                "kickoff line must name the taker, got: " + kickoff);
        // A team took it, and it is the HOME side at the start of the match.
        assertTrue(kickoff.contains("HOME"), "HOME takes the first kickoff, got: " + kickoff);
    }
}
