package org.example.footballmanager.demo.service.proposal.engine;

import org.example.footballmanager.demo.service.proposal.model.MatchState;
import org.example.footballmanager.demo.service.proposal.model.Player;
import org.example.footballmanager.demo.service.proposal.model.Position;
import org.example.footballmanager.demo.service.proposal.util.SimUtils;

/**
 * Movement engine - handles player movement toward targets with collision avoidance.
 * Moves players from A to B each tick, respects pace limits, and handles field boundaries.
 */
public class MovementEngine {

    public static final double PLAYER_SPEED_BASE = 0.75; // pace 20 = 0.75 cells/tick
    public static final double CARRIER_FACTOR = 0.90; // carrier moves slightly slower
    public static final double CHASE_SPRINT_MULTIPLIER = 1.30; // active chasers sprint
    public static final double PRESS_SPRINT_MULTIPLIER = 1.30; // threat override pressers
    public static final double MIN_PLAYER_DISTANCE = 0.35; // minimum distance before wall block
    public static final double MAX_FATIGUE_SPEED_LOSS = 0.30; // max 30% speed loss from fatigue
    public static final double IDLE_DRIFT_SPEED = 0.04; // idle drift toward ball

    /**
     * Move all players toward their targets for one tick.
     */
    public void moveAllTowardTargets(MatchState state) {
        for (Player p : state.getPlayers()) {
            if (p.isLocked() || p.isSentOff() || p.isInjured()) continue;

            Position target = p.getTarget();
            if (target == null) continue;

            Position current = p.getPosition();
            double pace = state.getRoundPaceSkill(p);
            double playerSpeed = playerSpeedFor(pace);

            // Carrier with ball moves slightly slower
            boolean isCarrier = p == state.getBall().getCarrier();
            if (isCarrier) {
                playerSpeed *= CARRIER_FACTOR;
            }

            // Calculate movement vector toward target
            double dx = target.getColumn() - current.getColumn();
            double dy = target.getRow() - current.getRow();
            double dist = Math.sqrt(dx * dx + dy * dy);

            // Don't move if already at target
            if (dist < 1e-6) {
                p.setTarget(null);
                continue;
            }

            // Apply movement
            double moveX = (dx / dist) * playerSpeed;
            double moveY = (dy / dist) * playerSpeed;

            Position newPosition = new Position(
                SimUtils.clamp(current.getRow() + moveY, 1.0, 8.0),
                SimUtils.clamp(current.getColumn() + moveX, 0.9, 7.0)
            );

            p.setPosition(newPosition);

            // If reached target, clear it
            if (SimUtils.distance(newPosition, target) < 1e-6) {
                p.setTarget(null);
            }
        }
    }

    /**
     * Find safe position for a player considering collision with others.
     */
    public Position findSafePosition(Player p, Position proposed, Position target,
                                      MatchState state) {
        Position current = p.getPosition();

        // Check for nearby players
        for (Player other : state.getPlayers()) {
            if (other == p || other.isSentOff() || other.isInjured()) continue;

            double otherDist = SimUtils.distance(other.getPosition(), proposed);
            if (otherDist < MIN_PLAYER_DISTANCE) {
                // Player would collide - try to find alternative
                // Simple: keep current position if blocked
                if (SimUtils.distance(current, target) <= SimUtils.distance(proposed, target)) {
                    return current;
                }
            }
        }

        return proposed;
    }

    /**
     * Calculate player speed based on pace.
     */
    public static double playerSpeedFor(double pace) {
        return (pace / 20.0) * PLAYER_SPEED_BASE;
    }
}