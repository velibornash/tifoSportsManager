package org.example.footballmanager.newLogic.model.event;

public record GkDistributionEvent(
    int minute,
    int tick,
    long chainId,
    long goalkeeperId,
    String goalkeeperName,
    String teamSide,
    long receiverId,
    String receiverName,
    String description,
    double x,
    double y
) implements MatchEvent {
    @Override
    public MatchEventType type() { return MatchEventType.GK_DISTRIBUTION; }
}
