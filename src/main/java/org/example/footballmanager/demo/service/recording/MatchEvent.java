package org.example.footballmanager.demo.service.recording;

/**
 * Immutable event record for match recording.
 * Each event captures one simulation occurrence for JSON output.
 */
public record MatchEvent(
        long tick,
        int round,
        String actionId,
        String type,
        String description,
        String team,
        String playerId,
        String playerName,
        String targetPlayerId,
        Double positionRow,
        Double positionColumn,
        Integer skill,
        String outcome
) {
    public MatchEvent(long tick, int round, String actionId, String type, String description) {
        this(tick, round, actionId, type, description, null, null, null, null, null, null, null, null);
    }
}
