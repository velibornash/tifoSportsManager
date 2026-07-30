package org.example.footballmanager.newLogic.model.event;

public record ReceiveEvent(
    int minute,
    int tick,
    long chainId,
    long receiverId,
    String receiverName,
    String teamSide,
    String description,
    double x,
    double y
) implements MatchEvent {
    @Override
    public MatchEventType type() { return MatchEventType.RECEIVE; }
}
