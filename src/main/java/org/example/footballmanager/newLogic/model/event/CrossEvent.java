package org.example.footballmanager.newLogic.model.event;

public record CrossEvent(
    int minute,
    int tick,
    long chainId,
    long crosserId,
    String crosserName,
    String teamSide,
    String description,
    double x,
    double y
) implements MatchEvent {
    @Override
    public MatchEventType type() { return MatchEventType.CROSS; }
}
