package org.tifo.footballmanager.newLogic.sim.util;

import org.tifo.footballmanager.newLogic.sim.model.Position;

/** Shared utility helpers for the simulation engine. */
public final class SimUtils {

    private SimUtils() {}

    public static double distance(Position a, Position b) {
        double dr = a.getRow() - b.getRow();
        double dc = a.getColumn() - b.getColumn();
        return Math.sqrt(dr * dr + dc * dc);
    }

    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Check if two positions are within a given radius. */
    public static boolean withinRadius(Position a, Position b, double radius) {
        return distance(a, b) <= radius;
    }

    /** Linear interpolation between two values. */
    public static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /** Clamp a value to a range, ensuring it stays within bounds. */
    public static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Compact position formatting for logs, e.g. (4.0,3.5). */
    public static String formatPos(Position pos) {
        return pos == null ? "?" : "(%.1f,%.1f)".formatted(pos.getRow(), pos.getColumn());
    }

    /**
     * Shortest distance from a point to the line segment {@code a-b}.
     *
     * <p>One implementation, and that is the point of it: the same arithmetic had been copy-pasted into
     * {@code GoalPhysical}, {@code BallPhysicsEngine} and {@code BallPhysicsProbe} as
     * {@code pointSegmentDist(row, column, p1, p2)}, and a fourth time into {@code CleanDecisionEngine} as
     * {@code pointToLineDistance(p, a, b)} - which is the same function under a different name, since a
     * line segment is what it measures against and there was never a line.
     *
     * <p>Degenerate segments fall back to point distance. The primitive overload is the one the engine
     * calls: the ball's path is a pair of doubles and not a {@link Position}, and boxing it into one to
     * measure it would allocate on every tick of every match.
     */
    public static double pointSegmentDistance(double row, double column, Position a, Position b) {
        if (a == null || b == null) return Double.MAX_VALUE;
        double dr = b.getRow() - a.getRow();
        double dc = b.getColumn() - a.getColumn();
        double lenSq = dr * dr + dc * dc;
        if (lenSq < 1e-9) return Math.hypot(row - a.getRow(), column - a.getColumn());
        double t = ((row - a.getRow()) * dr + (column - a.getColumn()) * dc) / lenSq;
        t = Math.max(0.0, Math.min(1.0, t));
        return Math.hypot(row - (a.getRow() + dr * t), column - (a.getColumn() + dc * t));
    }

    /** {@link #pointSegmentDistance(double, double, Position, Position)} for a point that is already one. */
    public static double pointSegmentDistance(Position a, Position b, Position point) {
        if (a == null || b == null || point == null) return Double.MAX_VALUE;
        return pointSegmentDistance(point.getRow(), point.getColumn(), a, b);
    }
}
