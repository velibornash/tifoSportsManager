package org.example.footballmanager.newLogic.model.event;

public record MatchEndEvent(
    int minute,
    int tick,
    int homeGoals,
    int awayGoals
) implements MatchEvent {
    @Override
    public MatchEventType type() { return MatchEventType.MATCH_END; }
}
