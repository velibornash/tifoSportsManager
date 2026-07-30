package org.example.footballmanager.newLogic.model.event;

public record ShotBlockedEvent(
    int minute,
    int tick,
    long chainId,
    long shooterId,
    String shooterName,
    String teamSide,
    long blockerId,
    String blockerName,
    double xG,
    String description,
    double x,
    double y
) implements MatchEvent {
    @Override
    public MatchEventType type() { return MatchEventType.SHOT_BLOCKED; }
}
