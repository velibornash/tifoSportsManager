package org.example.footballmanager.newLogic.sim.engine;

import org.example.footballmanager.newLogic.sim.model.MatchState;

import java.util.EnumMap;
import java.util.Map;

/**
 * Stoppage time (Sprint 1 — the real clock the substitution rules were faking).
 *
 * <p>{@code SubstitutionService.insideOpenWindow()} used to answer "is a substitution window open?"
 * with {@code state.getRestartTaker() != null} — i.e. "is anybody walking to a dead ball". That is
 * not a rule, it is a coincidence: it happened to be roughly right whenever a substitution followed
 * a restart, and wrong in the cases that matter most, like a window opened by a stoppage the restart
 * system knows nothing about.
 *
 * <p>This is the real thing, and it has two halves that are easy to conflate:
 *
 * <ol>
 *   <li><b>Play stopped.</b> When the referee stops play the match clock does not run. This is why a
 *       goalkeeper taking six seconds is six seconds, and why a replayed VAR check does not quietly
 *       consume match time.</li>
 *   <li><b>Added time announced.</b> Time lost to goals, injuries, substitutions and cards is
 *       announced at the end of each half and played out. Both halves were missing: the clock had no
 *       stop at all, so every one of those events silently ate playing time.</li>
 * </ol>
 *
 * <p>Modelled on the Premier League's actual figures: roughly 4–7 minutes added per half in recent
 * seasons, driven overwhelmingly by goals and injuries. A goal adds ~40s (a celebration plus the
 * restart), an injury ~50s, a substitution ~25s, a card ~20s, a VAR check ~60s.
 */
public class StoppageClock {

    /** Why the referee stopped play. Drives both the pause and the time added at half-time. */
    public enum Reason {
        GOAL(40),
        PENALTY_AWARDED(30),
        VAR_REVIEW(60),
        INJURY(50),
        SUBSTITUTION(25),
        RED_CARD(35),
        YELLOW_CARD(20),
        GOAL_KICK(6),
        CORNER(8),
        FREE_KICK(8),
        THROW_IN(4);

        private final int ticks;

        Reason(int ticks) {
            this.ticks = ticks;
        }

        /** Match ticks this stoppage is worth. 40 ticks = 1 minute of match time. */
        public int ticks() {
            return ticks;
        }
    }

    /**
     * How long a stoppage runs before play is forced back on, in ticks. A goalkeeper may take six
     * seconds; a long VAR check may take much longer, but the ball cannot sit dead indefinitely.
     */
    public static final int MAX_STOPPAGE_TICKS = 80;

    /** Per-half cap on announced added time, in ticks (40 ticks = 1 minute). */
    public static final int MAX_ADDED_PER_HALF = 240;

    private final Map<Reason, Integer> lostThisHalf = new EnumMap<>(Reason.class);
    private Reason currentReason;
    private int stoppageTicks;
    private int addedFirstHalf;
    private int addedSecondHalf;

    /**
     * Stops the clock and books the time against the half it happened in.
     *
     * <p>Repeated calls for the same reason inside one stoppage do not double-count: a VAR check that
     * confirms a penalty is one delay, not two.
     */
    public void stop(MatchState state, Reason reason) {
        if (currentReason == reason) return;
        if (currentReason != null) resume(state);
        currentReason = reason;
        stoppageTicks = 0;
        lostThisHalf.merge(reason, reason.ticks(), Integer::sum);
    }

    public boolean isStopped() {
        return currentReason != null;
    }

    public Reason currentReason() {
        return currentReason;
    }

    /** Ticks the stoppage; releases play when the referee has had long enough. */
    public void tick(MatchState state) {
        if (currentReason == null) return;
        if (++stoppageTicks >= MAX_STOPPAGE_TICKS) {
            resume(state);
        }
    }

    /**
     * Releases play.
     *
     * <p>Deliberately does <b>not</b> touch {@code state.setStopped(...)}. That flag means half-time
     * and belongs to {@link MatchClockService}. Sharing it was the first version of this class and it
     * deadlocked the match: a goal scored just before half-time left both flags set, and a stoppage
     * could then never clear the one it did not own, so the clock stayed stopped for the rest of the
     * game. The stoppage keeps its own {@code currentReason} and the clock consults both.
     */
    public void resume(MatchState state) {
        currentReason = null;
        stoppageTicks = 0;
    }

    /**
     * Ticks of added time to announce for the half now ending, in ticks.
     *
     * <p>Capped at {@link #MAX_ADDED_PER_HALF} (6 minutes) so a flurry of injuries cannot produce an
     * absurd announcement, and floored at one tick so a half always has some added time — every half
     * of every real match does.
     */
    public int addedTimeForHalfEnding(boolean secondHalf) {
        if (!secondHalf) {
            addedFirstHalf = Math.max(1, Math.min(MAX_ADDED_PER_HALF, totalLost()));
            return addedFirstHalf;
        }
        addedSecondHalf = Math.max(1, Math.min(MAX_ADDED_PER_HALF, totalLost()));
        return addedSecondHalf;
    }

    public int firstHalfAddedTicks() {
        return addedFirstHalf;
    }

    public int secondHalfAddedTicks() {
        return addedSecondHalf;
    }

    /** Ticks the first half may run past 1800 before the whistle. */
    public int firstHalfEndTick() {
        return MatchClockService.TOTAL_MATCH_TICKS / 2 + addedFirstHalf;
    }

    /** Ticks the second half may run past 3600 before the whistle. */
    public int secondHalfEndTick() {
        return MatchClockService.TOTAL_MATCH_TICKS + addedSecondHalf;
    }

    /** Seconds of added time to display, for the viewer and the log. */
    public int announcedSeconds(int halfTicks) {
        return (int) Math.round(halfTicks * 90.0 / MatchClockService.MATCH_TICKS_PER_MINUTE);
    }

    private int totalLost() {
        int sum = 0;
        for (int v : lostThisHalf.values()) sum += v;
        return sum;
    }

    /** Called at half-time: the second half starts with a clean slate. */
    public void startSecondHalf() {
        lostThisHalf.clear();
        currentReason = null;
        stoppageTicks = 0;
    }
}
