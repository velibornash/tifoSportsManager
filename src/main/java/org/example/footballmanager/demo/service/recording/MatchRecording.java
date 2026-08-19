package org.example.footballmanager.demo.service.recording;

import java.util.List;

/**
 * Immutable read model of the complete match recording for JSON output.
 */
public record MatchRecording(
        String matchId,
        List<MatchEvent> events,
        List<MatchSnapshot> snapshots,
        int goalCount,
        int awayGoalCount
) {
    public MatchRecording {
        events = List.copyOf(events);
        snapshots = List.copyOf(snapshots);
    }
}
