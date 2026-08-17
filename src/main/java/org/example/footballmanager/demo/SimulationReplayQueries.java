package org.example.footballmanager.demo;

import java.util.Comparator;
import java.util.List;

/** Read-only queries over recorded data; never re-runs simulation decisions. */
public final class SimulationReplayQueries {
    private SimulationReplayQueries() {}

    /**
     * Returns saved frames around every recorded goal. The returned list keeps
     * original snapshot order and can be rendered directly by a replay UI.
     */
    public static List<SimulationSnapshot> goalOnly(
            SimulationRecording recording, int ticksBefore, int ticksAfter) {
        int before = Math.max(0, ticksBefore);
        int after = Math.max(0, ticksAfter);
        List<Long> goalTicks = recording.events().stream()
                .map(SimulationEventStore.StoredSimulationEvent::event)
                .filter(event -> event instanceof ActionResultEvent result
                        && result.outcome() == ActionOutcome.SHOT_GOAL)
                .map(SimulationEvent::tick)
                .sorted()
                .toList();

        return recording.snapshots().stream()
                .filter(snapshot -> goalTicks.stream().anyMatch(goalTick ->
                        snapshot.tick() >= goalTick - before
                                && snapshot.tick() <= goalTick + after))
                .sorted(Comparator.comparingLong(SimulationSnapshot::tick))
                .toList();
    }
}
