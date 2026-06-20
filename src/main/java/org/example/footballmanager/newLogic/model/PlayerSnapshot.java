package org.example.footballmanager.newLogic.model;

public record PlayerSnapshot(
    long playerId,
    String name,
    String teamSide,
    Position position,
    double x,
    double y,
    String state,
    boolean hasBall
) {
    public double distanceTo(PlayerSnapshot other) {
        return Math.sqrt(Math.pow(x - other.x, 2) + Math.pow(y - other.y, 2));
    }

    public double distanceToPoint(double px, double py) {
        return Math.sqrt(Math.pow(x - px, 2) + Math.pow(y - py, 2));
    }
}
