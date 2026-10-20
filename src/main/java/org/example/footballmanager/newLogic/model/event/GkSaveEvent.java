package org.example.footballmanager.newLogic.model.event;

public record GkSaveEvent(
    int minute,
    int tick,
    long chainId,
    long goalkeeperId,
    String goalkeeperName,
    String teamSide,
    long shooterId,
    String shooterName,
    double xG,
    String description,
    double x,
    double y
) implements MatchEvent {
    @Override
    public MatchEventType type() { return MatchEventType.GK_SAVE; }
}
