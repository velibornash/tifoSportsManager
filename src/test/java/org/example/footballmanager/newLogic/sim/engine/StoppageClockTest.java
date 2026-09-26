package org.example.footballmanager.newLogic.sim.engine;

import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stoppage time.
 *
 * <p>This is the real clock that {@code SubstitutionService.insideOpenWindow()} used to fake with
 * {@code state.getRestartTaker() != null} — a coincidence, not a rule.
 */
class StoppageClockTest {

    private final StoppageClock clock = new StoppageClock();
    private final MatchState state = new MatchState();

    @Test
    @DisplayName("play is stopped and released")
    void stopsAndReleases() {
        assertFalse(clock.isStopped());
        clock.stop(state, StoppageClock.Reason.GOAL);
        assertTrue(clock.isStopped());
        clock.resume(state);
        assertFalse(clock.isStopped());
    }

    @Test
    @DisplayName("a stoppage always ends - a dead ball cannot freeze the match")
    void stoppageAlwaysEnds() {
        clock.stop(state, StoppageClock.Reason.VAR_REVIEW);
        for (int i = 0; i < StoppageClock.MAX_STOPPAGE_TICKS; i++) clock.tick(state);
        assertFalse(clock.isStopped(), "the referee eventually restarts play");
    }

    @Test
    @DisplayName("a stoppage does not consume the half-time flag")
    void stoppageDoesNotOwnHalfTime() {
        clock.stop(state, StoppageClock.Reason.GOAL);
        clock.resume(state);
        // state.stopped means half-time and belongs to MatchClockService. A stoppage must never
        // set or clear it: doing so deadlocked any match where a goal was scored near half-time.
        assertFalse(state.isStopped(),
                "a stoppage must not touch the half-time flag");
    }

    @Test
    @DisplayName("the same stoppage is not double-counted")
    void repeatedStoppageIsNotDoubleCounted() {
        // A VAR check that confirms a penalty is one delay, not two.
        for (int i = 0; i < 10; i++) clock.stop(state, StoppageClock.Reason.VAR_REVIEW);
        int added = clock.addedTimeForHalfEnding(false);
        assertEquals(StoppageClock.Reason.VAR_REVIEW.ticks(), added,
                "ten identical stoppages are one stoppage");
    }

    @Test
    @DisplayName("a different stoppage while one is running is a separate delay")
    void differentStoppageCountsSeparately() {
        clock.stop(state, StoppageClock.Reason.GOAL);
        clock.stop(state, StoppageClock.Reason.INJURY);
        int added = clock.addedTimeForHalfEnding(false);
        assertEquals(StoppageClock.Reason.GOAL.ticks() + StoppageClock.Reason.INJURY.ticks(), added);
    }

    @Test
    @DisplayName("added time is realistic for a Premier League half")
    void addedTimeIsRealistic() {
        // A quiet half still gets some added time; a busy one lands in the 4-7 minute band the
        // Premier League actually announces.
        StoppageClock quiet = new StoppageClock();
        assertEquals(1, quiet.addedTimeForHalfEnding(false), "every half gets at least a little");

        StoppageClock busy = new StoppageClock();
        for (int i = 0; i < 3; i++) busy.stop(makeState(), StoppageClock.Reason.GOAL);
        for (int i = 0; i < 2; i++) busy.stop(makeState(), StoppageClock.Reason.INJURY);
        busy.stop(makeState(), StoppageClock.Reason.SUBSTITUTION);
        int seconds = busy.announcedSeconds(busy.addedTimeForHalfEnding(false));
        assertTrue(seconds >= 120 && seconds <= 420,
                "expected 2-7 minutes added, got " + seconds);
    }

    @Test
    @DisplayName("added time is capped so a flurry of injuries cannot produce an absurd half")
    void addedTimeIsCapped() {
        StoppageClock crazy = new StoppageClock();
        for (int i = 0; i < 100; i++) {
            crazy.stop(makeState(), StoppageClock.Reason.INJURY);
            crazy.resume(makeState());
        }
        assertEquals(StoppageClock.MAX_ADDED_PER_HALF, crazy.addedTimeForHalfEnding(false));
    }

    @Test
    @DisplayName("the second half starts with a clean slate")
    void secondHalfStartsClean() {
        clock.stop(state, StoppageClock.Reason.GOAL);
        clock.stop(state, StoppageClock.Reason.INJURY);
        assertNotEquals(1, clock.addedTimeForHalfEnding(false));
        clock.startSecondHalf();
        assertEquals(1, clock.addedTimeForHalfEnding(true),
                "first-half stoppages must not be announced again for the second half");
    }

    @Test
    @DisplayName("a stoppage left running does not block the next half's stoppage")
    void restartIsNotSticky() {
        clock.stop(state, StoppageClock.Reason.GOAL);
        clock.startSecondHalf();
        assertFalse(clock.isStopped(), "startSecondHalf must clear any running stoppage");
    }

    private MatchState makeState() {
        return new MatchState();
    }
}
