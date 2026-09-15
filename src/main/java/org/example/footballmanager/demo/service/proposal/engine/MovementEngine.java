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
    public static final double CARRIER_FACTOR = 0.90; // carrier baseline (mild pressure)
    public static final double CARRIER_FACTOR_FREE = 0.96; // free (no defender within 1 cell)
    public static final double CARRIER_FACTOR_PRESSURE = 0.85; // defender within 1 cell
    public static final double CARRIER_FACTOR_TYPE_A = 0.78; // active TYPE A press override
    public static final double CARRIER_FREE_RADIUS = 1.0; // cells — no defender beyond = free
    public static final double CARRIER_TYPE_A_PRESS_RADIUS = 0.5; // cells — TYPE A presser range
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
                playerSpeed *= carrierSpeedFactor(p, state);
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

    /**
     * Slide around a wall that blocks the proposed move — an opponent (P4#1)
     * OR a teammate (P4#4). Perpendicular go-around is preferred: the player
     * slides along the tangent of the wall circle in its own movement
     * direction, so a dribbler rounds the wall instead of being pushed
     * straight backward into pressure. A straight push-away ring is only the
     * fallback when neither perpendicular direction improves clearance.
     */
    private Position separateFromOpponents(Player p, Position proposed, MatchState state) {
        // Movement-direction unit vector (0,0 when no movement) — used to pick
        // the perpendicular (tangent) slide for the go-around, not the raw
        // wall-carrier vector.
        Position current = p.getPosition();
        double dirRow = proposed.getRow() - current.getRow();
        double dirCol = proposed.getColumn() - current.getColumn();
        double dirLen = Math.hypot(dirRow, dirCol);
        double uRow = dirLen > 1e-9 ? dirRow / dirLen : 0;
        double uCol = dirLen > 1e-9 ? dirCol / dirLen : 0;
        double perpRow = -uCol;
        double perpCol = uRow;

        Position best = proposed;
        double bestClearance = 0;
        for (Player other : state.getPlayers()) {
            if (other == p || other.isUnavailable() || other.isLocked()) continue;
            double d = SimUtils.distance(other.getPosition(), proposed);
            if (d >= MIN_PLAYER_DISTANCE || d <= 1e-9) continue;

            double push = MIN_PLAYER_DISTANCE - d;

            // 1) Perpendicular go-around (P4#1 / P4#4) — try both signs of the
            //    tangent; keep the one that opens up the most free space.
            for (int s = -1; s <= 1; s += 2) {
                Position slide = new Position(
                        proposed.getRow() + s * perpRow * push,
                        proposed.getColumn() + s * perpCol * push
                );
                double clearance = SimUtils.distance(other.getPosition(), slide);
                if (clearance > Math.max(d, bestClearance)) {
                    bestClearance = clearance;
                    best = slide;
                }
            }

            // 2) Straight push-away fallback — only used when the perpendicular
            //    slides cannot open any space (fully walled).
            double dr = proposed.getRow() - other.getPosition().getRow();
            double dc = proposed.getColumn() - other.getPosition().getColumn();
            double len = Math.hypot(dr, dc);
            if (len > 1e-9) {
                Position straight = new Position(
                        other.getPosition().getRow() + dr / len * MIN_PLAYER_DISTANCE,
                        other.getPosition().getColumn() + dc / len * MIN_PLAYER_DISTANCE
                );
                double clearance = SimUtils.distance(other.getPosition(), straight);
                if (clearance > bestClearance) {
                    bestClearance = clearance;
                    best = straight;
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

    /**
     * Carrier speed factor — modulates carrier pace by pressure:
     * faster when FREE (no opponent within CARRIER_FREE_RADIUS), slower
     * under pressure (opponent within that radius), slowest under active
     * TYPE A press (opponent with threatOverrideActive within
     * CARRIER_TYPE_A_PRESS_RADIUS).
     */
    public static double carrierSpeedFactor(Player carrier, MatchState state) {
        double nearest = Double.MAX_VALUE;
        boolean typeAPress = false;
        for (Player other : state.getPlayers()) {
            if (other == carrier || other.isUnavailable() || other.isLocked()) continue;
            if (other.getTeam().equals(carrier.getTeam())) continue;
            double d = SimUtils.distance(other.getPosition(), carrier.getPosition());
            if (d < nearest) nearest = d;
            if (d <= CARRIER_TYPE_A_PRESS_RADIUS && other.isThreatOverrideActive()) {
                typeAPress = true;
            }
        }
        if (typeAPress) return CARRIER_FACTOR_TYPE_A;
        if (nearest > CARRIER_FREE_RADIUS) return CARRIER_FACTOR_FREE;
        return CARRIER_FACTOR_PRESSURE;
    }
}