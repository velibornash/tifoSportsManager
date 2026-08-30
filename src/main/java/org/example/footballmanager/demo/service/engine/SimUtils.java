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

    /** Closest point on segment [a,b] to point p. */
    public static Position closestPointOnSegment(Position p, Position a, Position b) {
        double abx = b.getColumn() - a.getColumn();
        double aby = b.getRow() - a.getRow();
        double apx = p.getColumn() - a.getColumn();
        double apy = p.getRow() - a.getRow();
        double lenSq = abx * abx + aby * aby;
        double t = (lenSq == 0.0) ? 0.0
                : Math.max(0.0, Math.min(1.0, (apx * abx + apy * aby) / lenSq));
        return new Position(a.getRow() + t * aby, a.getColumn() + t * abx);
    }

    /** Distance from point p to segment [a,b]. */
    public static double pointSegmentDistance(Position p, Position a, Position b) {
        Position closest = closestPointOnSegment(p, a, b);
        return distance(p, closest);
    }
}
