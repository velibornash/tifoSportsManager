package org.example.footballmanager.newLogic.model.event;

public record ShotEvent(
    int minute,
    int tick,
    long shooterId,
    String shooterName,
    String teamSide,
    boolean onTarget,
    boolean saved,
    boolean isGoal,
    double xG,
    double x,
    double y
) implements MatchEvent {
    public static ShotEvent onTarget(int minute, int tick, long shooterId, String shooterName, String teamSide, double xG, double x, double y) {
        return new ShotEvent(minute, tick, shooterId, shooterName, teamSide, true, true, false, xG, x, y);
    }

    public static ShotEvent goal(int minute, int tick, long shooterId, String shooterName, String teamSide, double xG, double x, double y) {
        return new ShotEvent(minute, tick, shooterId, shooterName, teamSide, true, false, true, xG, x, y);
    }

    public static ShotEvent missed(int minute, int tick, long shooterId, String shooterName, String teamSide, double xG, double x, double y) {
        return new ShotEvent(minute, tick, shooterId, shooterName, teamSide, false, false, false, xG, x, y);
    }

    @Override
    public MatchEventType type() { 
        if (isGoal) return MatchEventType.GOAL;
        if (onTarget || saved) return MatchEventType.SHOT_ON_TARGET;
        return MatchEventType.SHOT_OFF_TARGET;
    }
}
