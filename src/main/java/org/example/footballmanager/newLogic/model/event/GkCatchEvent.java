package org.example.footballmanager.newLogic.model.event;

public record GkCatchEvent(
    int minute,
    int tick,
    long chainId,
    long goalkeeperId,
    String goalkeeperName,
    String teamSide,
    String description,
    double x,
    double y
) implements MatchEvent {
    @Override
    public MatchEventType type() { return MatchEventType.GK_CATCH; }
}
