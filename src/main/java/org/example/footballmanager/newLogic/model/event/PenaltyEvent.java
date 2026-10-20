package org.example.footballmanager.newLogic.model.event;

public record PenaltyEvent(
    int minute,
    int tick,
    long takerId,
    String takerName,
    String teamSide,
    boolean scored,
    boolean saved,
    double xG
) implements MatchEvent {
    @Override
    public MatchEventType type() { return MatchEventType.PENALTY; }
}
