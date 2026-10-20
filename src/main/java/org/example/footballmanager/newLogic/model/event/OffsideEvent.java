package org.example.footballmanager.newLogic.model.event;

public record OffsideEvent(
    int minute,
    int tick,
    long playerId,
    String playerName,
    String teamSide
) implements MatchEvent {
    @Override
    public MatchEventType type() { return MatchEventType.OFFSIDE; }
}
