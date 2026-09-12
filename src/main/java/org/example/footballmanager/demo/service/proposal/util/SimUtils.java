package org.example.footballmanager.demo.service.proposal.util;

import org.example.footballmanager.demo.service.proposal.model.Position;

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
}