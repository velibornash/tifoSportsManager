package org.example.footballmanager.newLogic.model.event;

public record LongBallEvent(
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
    public MatchEventType type() { return MatchEventType.LONG_BALL; }
}
