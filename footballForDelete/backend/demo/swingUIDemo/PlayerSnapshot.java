package org.example.footballmanager.demo.swingUIDemo;

/** Immutable per-tick player state used by replay/statistics consumers. */
public record PlayerSnapshot(
        String id,
        String label,
        String team,
        String role,
        Position position,
        Position target,
        boolean locked,
        double velocityX,
        double velocityY
) {}
