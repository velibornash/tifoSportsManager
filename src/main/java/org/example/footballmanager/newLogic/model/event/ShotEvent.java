package org.example.footballmanager.newLogic.model.event;

public record ShotEvent(
    int minute,
    int tick,
    long shooterId,
    String shooterName,
    String teamSide,
    boolean onTarget,
    boolean saved,
    double xG,
    double x,
    double y
) implements MatchEvent {
    public static ShotEvent onTarget(int minute, int tick, long shooterId, String shooterName, String teamSide, double xG, double x, double y) {
        return new ShotEvent(minute, tick, shooterId, shooterName, teamSide, true, true, xG, x, y);
    }

    public static ShotEvent goal(int minute, int tick, long shooterId, String shooterName, String teamSide, double xG, double x, double y) {
        return new ShotEvent(minute, tick, shooterId, shooterName, teamSide, true, false, xG, x, y);
    }

    public static ShotEvent missed(int minute, int tick, long shooterId, String shooterName, String teamSide, double xG, double x, double y) {
        return new ShotEvent(minute, tick, shooterId, shooterName, teamSide, false, false, xG, x, y);
    }

    @Override
    public MatchEventType type() { return saved ? MatchEventType.SHOT_ON_TARGET : MatchEventType.SHOT_OFF_TARGET; }
}
