package org.example.footballmanager.newLogic.model.event;

public record DribbleLostEvent(
    int minute,
    int tick,
    long chainId,
    long dribblerId,
    String dribblerName,
    String teamSide,
    long tacklerId,
    String tacklerName,
    String description,
    double x,
    double y
) implements MatchEvent {
    @Override
    public MatchEventType type() { return MatchEventType.DRIBBLE_LOST; }
}
