package org.example.footballmanager.newLogic.model.event;

public record DribbleEvent(
    int minute,
    int tick,
    long chainId,
    long dribblerId,
    String dribblerName,
    String teamSide,
    long defenderId,
    String defenderName,
    String description,
    double x,
    double y
) implements MatchEvent {
    @Override
    public MatchEventType type() { return MatchEventType.DRIBBLE; }
}
