package org.example.footballmanager.newLogic.model.event;

public record ShotMissedEvent(
    int minute,
    int tick,
    long chainId,
    long shooterId,
    String shooterName,
    String teamSide,
    double xG,
    String description,
    double x,
    double y
) implements MatchEvent {
    @Override
    public MatchEventType type() { return MatchEventType.SHOT_MISSED; }
}
