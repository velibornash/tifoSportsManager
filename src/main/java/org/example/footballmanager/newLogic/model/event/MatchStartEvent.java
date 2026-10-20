package org.example.footballmanager.newLogic.model.event;

public record MatchStartEvent(
    int minute,
    int tick,
    String homeTeamName,
    String awayTeamName
) implements MatchEvent {
    @Override
    public MatchEventType type() { return MatchEventType.MATCH_START; }
}
