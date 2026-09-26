package org.example.footballmanager.newLogic.sim.util;

import org.example.footballmanager.newLogic.sim.model.Position;

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
     * <p>Consolidated here because the same private helper had been copy-pasted into three places
     * ({@code GoalPhysical}, {@code BallPhysicsEngine}, {@code BallPhysicsProbe}). Degenerate
     * segments fall back to point distance.
     */
    public static double pointSegmentDistance(Position a, Position b, Position point) {
        if (a == null || b == null || point == null) return Double.MAX_VALUE;
        double dr = b.getRow() - a.getRow();
        double dc = b.getColumn() - a.getColumn();
        double lenSq = dr * dr + dc * dc;
        if (lenSq < 1e-9) return distance(point, a);
        double t = ((point.getRow() - a.getRow()) * dr + (point.getColumn() - a.getColumn()) * dc) / lenSq;
        t = Math.max(0.0, Math.min(1.0, t));
        return distance(point, new Position(a.getRow() + dr * t, a.getColumn() + dc * t));
    }
}
