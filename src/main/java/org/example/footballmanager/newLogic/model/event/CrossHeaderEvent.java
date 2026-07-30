package org.example.footballmanager.newLogic.model.event;

public record CrossHeaderEvent(
    int minute,
    int tick,
    long chainId,
    long headerId,
    String headerName,
    String teamSide,
    long crosserId,
    String crosserName,
    boolean onTarget,
    double xG,
    String description,
    double x,
    double y
) implements MatchEvent {
    @Override
    public MatchEventType type() { return MatchEventType.CROSS_HEADER; }
}
