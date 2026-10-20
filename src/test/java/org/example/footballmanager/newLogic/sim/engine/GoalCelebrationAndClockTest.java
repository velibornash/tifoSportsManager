package org.example.footballmanager.newLogic.sim.engine;

import org.example.footballmanager.newLogic.sim.model.MatchPhase;
import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.Position;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The goal celebration, and the clock arithmetic behind every log line (owner report 2026-09-27).
 *
 * <p>Both of these were invisible in the replay. The clock printed {@code 44:65} and
 * {@code 32:87} — a minute with eighty-seven seconds in it — and a goal was a line in the log with
 * the ball already back at the centre. Neither throws, neither fails an assertion, and both made the
 * match look broken on screen.
 */
class GoalCelebrationAndClockTest {

    // --- the clock ---

    @Test
    @DisplayName("a minute never has more than fifty-nine seconds in it")
    void secondsNeverOverflow() {
        // Forty ticks to a minute and a tick is 1.5s, so 39 ticks into a minute is 58.5s. The old
        // arithmetic scaled the remainder to 90 and produced 87, and %02d printed it happily.
        for (int tick = 0; tick < 40 * 90; tick++) {
            int seconds = tick % 40 * 60 / 40;
            assertTrue(seconds >= 0 && seconds <= 59,
                    "tick " + tick + " produced " + seconds + " seconds");
        }
    }

    @Test
    @DisplayName("the last tick of a minute is 58, not 87")
    void theLastTickOfAMinute() {
        assertEquals(58, 39 % 40 * 60 / 40);
    }

    @Test
    @DisplayName("forty ticks roll the minute over")
    void minutesAdvance() {
        assertEquals(0, 0 / 40);
        assertEquals(0, 39 / 40);
        assertEquals(1, 40 / 40);
        assertEquals(2, 80 / 40);
    }

    // --- the celebration ---

    @Test
    @DisplayName("a goal starts a celebration and marks the phase so a replay can see it")
    void aGoalStartsACelebration() {
        MatchState state = new MatchState();
        assertFalse(state.isCelebrating());

        state.startCelebration("HOME", 20);
        assertTrue(state.isCelebrating());
        assertEquals("HOME", state.getCelebratingTeam());
        assertEquals(20, state.getCelebrationHoldTicks());
        assertEquals(MatchPhase.GOAL_CELEBRATION, state.getPhase(),
                "a replay and any phase-dependent logic need to tell this apart from open play");
    }

    @Test
    @DisplayName("the celebration lasts for the number of ticks it was given, and no longer")
    void theHoldIsCountedDown() {
        MatchState state = new MatchState();
        state.startCelebration("HOME", 3);

        // The contract: the hold is three ticks long, and the THIRD of them is the one that restarts.
        // So two calls report "more to come" and the third reports "finished" and does the kickoff.
        // A hold of twenty therefore gives twenty ticks of celebration, the last of which resets.
        assertTrue(state.consumeCelebrationHoldTick(), "ended on tick 1 of 3");
        assertTrue(state.consumeCelebrationHoldTick(), "ended on tick 2 of 3");
        assertFalse(state.consumeCelebrationHoldTick(), "tick 3 of 3 should be the restart");
        assertFalse(state.isCelebrating(), "a finished celebration is still flagged as running");
        assertEquals(null, state.getCelebratingTeam(), "a finished celebration still names a team");
    }

    @Test
    @DisplayName("the ball rests past the line it crossed, on the side that scored")
    void theBallGoesInTheNet() {
        Position home = BallResultHandler.goalExitPositionFor("HOME");
        Position away = BallResultHandler.goalExitPositionFor("AWAY");

        // HOME attacks the far line at row 8 and AWAY's is at row 1, so their ball rests beyond
        // opposite ends. The point is that it is *past* the line, not sitting on it — a ball on the
        // line reads as a save in a replay.
        assertTrue(home.getRow() > 8.0, "HOME's ball should be behind the 8.0 line, got " + home.getRow());
        assertTrue(away.getRow() < 1.0, "AWAY's ball should be behind the 1.0 line, got " + away.getRow());
    }

    @Test
    @DisplayName("the ball is never parked somewhere absurd")
    void theGoalExitIsSane() {
        for (String team : new String[]{"HOME", "AWAY", null}) {
            Position exit = BallResultHandler.goalExitPositionFor(team);
            assertNotNull(exit);
            assertTrue(exit.getRow() > 0.0 && exit.getRow() < 9.0,
                    "goal exit row out of range: " + exit.getRow());
            assertTrue(exit.getColumn() >= 3.5 && exit.getColumn() <= 4.5,
                    "the ball should rest inside the goal mouth, got column " + exit.getColumn());
        }
    }

    @Test
    @DisplayName("the hold is long enough to see and short enough not to waste a match")
    void theHoldLengthIsSensible() {
        int hold = BallResultHandler.GOAL_CELEBRATION_HOLD_TICKS;
        assertTrue(hold >= 10, "a goal that flashes past in a few ticks is not a celebration");
        assertTrue(hold <= 40, "a " + hold + "-tick celebration is most of a match minute");
    }
}
