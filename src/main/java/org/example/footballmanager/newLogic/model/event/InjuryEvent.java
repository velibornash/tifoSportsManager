package org.example.footballmanager.newLogic.model.event;

public record InjuryEvent(
    int minute,
    int tick,
    long playerId,
    String playerName,
    String teamSide
) implements MatchEvent {
    @Override
    public MatchEventType type() { return MatchEventType.INJURY; }
}
