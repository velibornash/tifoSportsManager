package org.example.footballmanager.demo.service.proposal.engine;

import org.example.footballmanager.demo.service.proposal.model.MatchState;
import org.example.footballmanager.demo.service.proposal.model.Player;
import org.example.footballmanager.demo.service.proposal.model.Position;
import org.example.footballmanager.demo.service.proposal.util.SimUtils;

/**
 * Movement engine — handles player movement toward targets with collision avoidance.
 * Moves players from A to B each tick, respects pace limits.
 * NO field boundary clamping — players may move off the pitch.
 */
public class MovementEngine {

    public static final double PLAYER_SPEED_BASE = 0.75; // pace 20 = 0.75 cells/tick
    public static final double CARRIER_FACTOR = 0.90; // carrier moves slightly slower
    public static final double MIN_PLAYER_DISTANCE = 0.35; // minimum distance before wall block
    public static final double MAX_FATIGUE_SPEED_LOSS = 0.30; // max 30% speed loss from fatigue
    public static final double IDLE_DRIFT_SPEED = 0.04; // idle drift toward ball

    // NOTE: no chase/sprint/press multiplier. User rule (2026-09-14): players
    // move pace-capped at ALL times — the ONLY exceptions are the carrier
    // (slower, CARRIER_FACTOR above) and celebrations (not part of play).
    // Chasing a loose ball / pressing a threat is NOT faster than normal
    // running: the pace skill decides speed, the override only decides target.

    /**
     * Move all players toward their targets for one tick.
     */
    public void moveAllTowardTargets(MatchState state) {
        // Loose-ball chase: when the ball is stopped, has no carrier, no pending
        // receiver, no restart taker, and is not awaiting an OOB restart, the
        // single nearest available player sprints to the ball so a loose ball
        // is never left dead on the pitch.
        Player chaser = looseBallChaser(state);

        for (Player p : state.getPlayers()) {
            if (p.isLocked() || p.isSentOff() || p.isInjured()) continue;

            boolean isChaser = p == chaser;
            Position target = isChaser ? state.getBall().getPosition() : p.getTarget();
            if (target == null) continue;

            Position current = p.getPosition();
            double pace = state.getRoundPaceSkill(p);
            double playerSpeed = playerSpeedFor(pace);

            // Carrier with ball moves slightly slower
            boolean isCarrier = p == state.getCarrier();
            if (isCarrier) {
                playerSpeed *= CARRIER_FACTOR;
            }

            // Chaser moves at the same pace-capped speed as any other player
            // (user rule 2026-09-14: only target overrides, never speed boosts).

            // Calculate movement vector toward target
            double dx = target.getColumn() - current.getColumn();
            double dy = target.getRow() - current.getRow();
            double dist = Math.sqrt(dx * dx + dy * dy);

            // Don't move if already at target
            if (dist < 1e-6) {
                if (!isChaser) p.setTarget(null);
                continue;
            }

            // Apply movement
            double moveX = (dx / dist) * playerSpeed;
            double moveY = (dy / dist) * playerSpeed;

            // Opposing-player separation: never let an opponent and this player
            // occupy the same spot. If the proposed position would sit inside an
            // opponent's personal space, slide to the nearest free point on a
            // ring around them (away from the ball side). This prevents the
            // degenerate "both DCLs co-located at center → instant re-intercept"
            // loop without overriding tactical targets.
            Position newPosition = new Position(
                    current.getRow() + moveY,
                    current.getColumn() + moveX
            );
            Position resolved = separateFromOpponents(p, newPosition, state);
            newPosition = resolved;

            p.setPosition(newPosition);

            // If reached target, clear it
            if (SimUtils.distance(newPosition, target) < 1e-6) {
                if (!isChaser) p.setTarget(null);
            }
        }
    }

    /** Slide a proposed position away from any opposing player occupying it. */
    private Position separateFromOpponents(Player p, Position proposed, MatchState state) {
        Position best = proposed;
        double bestPush = 0;
        for (Player other : state.getPlayers()) {
            if (other == p || other.isUnavailable() || other.isLocked()) continue;
            if (other.getTeam().equals(p.getTeam())) continue; // same team may wall
            double d = SimUtils.distance(other.getPosition(), proposed);
            if (d < MIN_PLAYER_DISTANCE && d > 1e-9) {
                double push = MIN_PLAYER_DISTANCE - d;
                if (push > bestPush) {
                    bestPush = push;
                    double dr = proposed.getRow() - other.getPosition().getRow();
                    double dc = proposed.getColumn() - other.getPosition().getColumn();
                    double len = Math.hypot(dr, dc);
                    best = new Position(
                            other.getPosition().getRow() + dr / len * MIN_PLAYER_DISTANCE,
                            other.getPosition().getColumn() + dc / len * MIN_PLAYER_DISTANCE
                    );
                }
            }
        }
        return best;
    }

    /** Nearest available player to a stopped loose ball, or null. */
    private Player looseBallChaser(MatchState state) {
        if (state.getCarrier() != null
                || state.getPendingReceiver() != null
                || state.getRestartTaker() != null
                || state.getOobPending() != null) {
            return null;
        }
        if (state.getBall().getSpeed() > BallPhysicsEngine.STOP_SPEED) return null;

        Player best = null;
        double bestD = Double.MAX_VALUE;
        for (Player p : state.getPlayers()) {
            if (p.isUnavailable() || p.isLocked()) continue;
            double d = SimUtils.distance(p.getPosition(), state.getBall().getPosition());
            if (d < bestD) { bestD = d; best = p; }
        }
        return bestD <= 4.0 ? best : null; // only chase from a sensible range
    }

    /**
     * Find safe position for a player considering collision with others.
     * (Currently unused — collision handled by ball engine for ball contacts)
     */
    public Position findSafePosition(Player p, Position proposed, Position target,
                                      MatchState state) {
        Position current = p.getPosition();

        for (Player other : state.getPlayers()) {
            if (other == p || other.isSentOff() || other.isInjured()) continue;

            double otherDist = SimUtils.distance(other.getPosition(), proposed);
            if (otherDist < MIN_PLAYER_DISTANCE) {
                if (SimUtils.distance(current, target) <= SimUtils.distance(proposed, target)) {
                    return current;
                }
            }
        }

        return proposed;
    }

    /** Calculate player speed based on pace (1..20). */
    public static double playerSpeedFor(double pace) {
        return (pace / 20.0) * PLAYER_SPEED_BASE;
    }
}