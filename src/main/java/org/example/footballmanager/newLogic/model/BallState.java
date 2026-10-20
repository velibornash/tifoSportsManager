package org.example.footballmanager.newLogic.model;

public record BallState(
    double x,
    double y,
    double z
) {
    public static BallState at(double x, double y) {
        return new BallState(x, y, 0);
    }

    public static BallState at(double x, double y, double z) {
        return new BallState(x, y, z);
    }

    public BallState withX(double x) { return new BallState(x, y, z); }
    public BallState withY(double y) { return new BallState(x, y, z); }
    public BallState withZ(double z) { return new BallState(x, y, z); }

    public double distanceTo(BallState other) {
        return Math.sqrt(Math.pow(x - other.x, 2) + Math.pow(y - other.y, 2));
    }

    public double distanceToPoint(double px, double py) {
        return Math.sqrt(Math.pow(x - px, 2) + Math.pow(y - py, 2));
    }
}
