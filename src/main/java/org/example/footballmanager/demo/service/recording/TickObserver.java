package org.example.footballmanager.demo.service.recording;

import org.example.footballmanager.demo.service.MatchState;

/**
 * Callback interface invoked once per simulation tick.
 * Implementations must copy any data they need from {@link MatchState}
 * during the call — the state is mutable and continues to change after
 * the method returns.
 *
 * The callback is invoked at the END of each tick in the main play loop
 * (after movement, fatigue, transition, and snapshot capture), so the
 * state reflects the final position of all players for that tick.
 */
@FunctionalInterface
public interface TickObserver {
    void onTick(long tick, MatchState state);
}
