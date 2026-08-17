package org.example.footballmanager.demo;

import java.util.List;

/**
 * Immutable read model of the demo match recording. Replay and statistics
 * consumers use this boundary instead of reaching into mutable SimulationState.
 */
public record SimulationRecording(
        List<SimulationEventStore.StoredSimulationEvent> events,
        List<SimulationSnapshot> snapshots,
        int goalCount,
        int awayGoalCount
) {
    public SimulationRecording {
        events = List.copyOf(events);
        snapshots = List.copyOf(snapshots);
    }
}
