package org.example.footballmanager.newLogic.sim.engine;

import org.example.footballmanager.newLogic.sim.model.MatchState;

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
        // Only half-time lives on this flag. A referee's stoppage is halted a level up, in
        // MatchOrchestrator.tick, so the whole pipeline stops rather than just the clock.
        if (state.isStopped()) {
            if (isMatchFinished(state)) {
                state.setMatchFinished(true);
                return false;
            }
            return true;
        }

        state.advanceTick();

        // Half-time. The end of the half is a plain tick the orchestrator sets, because the
        // referee announces added time for the half that has just ended and that figure is what
        // extends the half in progress. Having the clock derive it from the stoppage clock
        // created a circular dependency - the clock needed an announcement that only happened
        // because the clock had stopped.
        if (state.getMatchTicks() == halfEndTick && !state.isHalfTime()) {
            state.setStopped(true);
            state.setHalfTime(true);
            return true; // match not finished, just paused
        }

        if (isMatchFinished(state)) {
            state.setMatchFinished(true);
            return false;
        }

        return true;
    }

    /** Tick at which the half in progress ends. Starts at 45 minutes; the orchestrator moves it. */
    private int halfEndTick = MatchClockService.TOTAL_MATCH_TICKS / 2;

    public int getHalfEndTick() {
        return halfEndTick;
    }

    /** Called by the orchestrator once the referee has announced added time for the half. */
    public void setHalfEndTick(int halfEndTick) {
        this.halfEndTick = halfEndTick;
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
    /**
     * The match is over once the SECOND half's end tick is passed.
     *
     * <p>This used to be a hardcoded {@code >= 3600}, which silently discarded the added time: the
     * referee could announce six minutes for the second half and the whistle would still go at 90:00.
     * The {@code halfEndTick > TOTAL/2} guard stops the first half's end from ever reading as
     * full time.
     */
    public boolean isMatchFinished(MatchState state) {
        if (halfEndTick <= TOTAL_MATCH_TICKS / 2) return false;
        return state.getMatchTicks() >= halfEndTick;
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
        // Forty ticks make a MINUTE and a tick is 1.5s, so the remainder scales to sixty, not
        // ninety. Multiplying by ninety yields up to 87 and a clock with 87 seconds in it.
        return ((tick % MATCH_TICKS_PER_MINUTE) * 60) / MATCH_TICKS_PER_MINUTE;
    }
}