package org.example.footballmanager.newLogic.model.event;

public record ShotSavedEvent(
    int minute,
    int tick,
    long chainId,
    long shooterId,
    String shooterName,
    String teamSide,
    long goalkeeperId,
    String goalkeeperName,
    double xG,
    String description,
    double x,
    double y
) implements MatchEvent {
    @Override
    public MatchEventType type() { return MatchEventType.SHOT_SAVED; }
}
