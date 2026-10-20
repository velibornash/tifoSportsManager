package org.example.footballmanager.newLogic.model.event;

public record CrossClearedEvent(
    int minute,
    int tick,
    long chainId,
    long clearerId,
    String clearerName,
    String teamSide,
    String description,
    double x,
    double y
) implements MatchEvent {
    @Override
    public MatchEventType type() { return MatchEventType.CROSS_CLEARED; }
}
