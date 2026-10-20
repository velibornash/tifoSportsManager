package org.example.footballmanager.newLogic.model.event;

public record ThroughBallEvent(
    int minute,
    int tick,
    long chainId,
    long passerId,
    String passerName,
    long receiverId,
    String receiverName,
    String teamSide,
    double distance,
    String description,
    double x,
    double y
) implements MatchEvent {
    @Override
    public MatchEventType type() { return MatchEventType.THROUGH_BALL; }
}
