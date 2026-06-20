package org.example.footballmanager.newLogic.model.event;

public record FoulEvent(
    int minute,
    int tick,
    long takerId,
    String takerName,
    long victimId,
    String victimName,
    String teamSide,
    boolean penaltyFoul,
    double x,
    double y
) implements MatchEvent {
    @Override
    public MatchEventType type() { return MatchEventType.FOUL; }
}
