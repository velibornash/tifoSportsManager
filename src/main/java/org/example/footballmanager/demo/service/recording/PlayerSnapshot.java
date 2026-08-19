package org.example.footballmanager.demo.service.recording;

import org.example.footballmanager.demo.service.model.Position;

/**
 * Immutable player state at a specific tick for replay.
 */
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
