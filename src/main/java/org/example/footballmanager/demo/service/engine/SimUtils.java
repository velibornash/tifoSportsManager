package org.example.footballmanager.demo.service.engine;

import org.example.footballmanager.demo.service.model.Position;

/**
 * Shared geometry utilities for the simulation engines.
 */
public final class SimUtils {
    private SimUtils() {}

    public static double distance(Position a, Position b) {
        double dr = a.getRow() - b.getRow();
        double dc = a.getColumn() - b.getColumn();
        return Math.hypot(dr, dc);
    }

    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public static Position oneCellToward(Position from, Position to) {
        double dr = Math.signum(to.getRow() - from.getRow());
        double dc = Math.signum(to.getColumn() - from.getColumn());
        double nr = clamp(from.getRow() + dr, 1, 7);
        double nc = clamp(from.getColumn() + dc, 1, 6);
        return new Position(nr, nc);
    }
}
