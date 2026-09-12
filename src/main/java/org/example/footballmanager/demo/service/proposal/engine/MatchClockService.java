package org.example.footballmanager.demo.service.proposal.engine;

import org.example.footballmanager.demo.service.proposal.model.MatchState;

/**
 * Match clock service - controls the simulation clock.
 * Clock ALWAYS advances unless explicitly stopped (e.g., half time).
 * No OOB hold, no pause during ball flight.
 */
public class MatchClockService {

    public static final int MATCH_TICKS_PER_MINUTE = 40; // 1 tick = 1.5s of match time
    public static final int TOTAL_MATCH_TICKS = 3600;   // 90 minutes at 40 TPM (half-time at 1800)

    /**
     * Advance the match clock. Always runs unless state is stopped.
     * Returns true if match is still active, false if match ended.
     */
    public boolean tick(MatchState state) {
        if (state.isStopped()) {
            return !isMatchFinished(state);
        }

        state.advanceTick();

        // Check for half-time stop (45 min = 1800 ticks)
        if (state.getMatchTicks() == 1800) {
            state.setStopped(true);
            return true; // match not finished, just paused
        }

        return !isMatchFinished(state);
    }

    /**
     * Resume match after stop (e.g., after half time).
     */
    public void resume(MatchState state) {
        state.setStopped(false);
    }

    /**
     * Check if match has reached its end.
     */
    public boolean isMatchFinished(MatchState state) {
        return state.getMatchTicks() >= TOTAL_MATCH_TICKS;
    }

    /**
     * Get current match minute from tick count.
     */
    public int getMatchMinute(int tick) {
        return tick / MATCH_TICKS_PER_MINUTE;
    }

    /**
     * Get current match second from tick count.
     */
    public int getMatchSecond(int tick) {
        return ((tick % MATCH_TICKS_PER_MINUTE) * 90) / MATCH_TICKS_PER_MINUTE; // 1.5s per tick
    }
}