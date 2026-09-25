package org.example.footballmanager.newLogic.sim.engine;

import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.PitchEnvironment;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.model.Position;
import org.example.footballmanager.newLogic.sim.util.SimUtils;

/**
 * Movement engine — handles player movement toward targets with collision avoidance.
 * Moves players from A to B each tick, respects pace limits.
 * Players are clamped to the pitch after every move (they cannot leave the field).
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
    /**
     * Claiming-reach radius: a restart taker or loose-ball chaser (the player
     * "claiming" a stopped ball) is exempt from opponent separation once within
     * this distance of the ball spot, so he can lunge THROUGH a wall-ring of
     * players pressing the ball (they sit at ~MIN_PLAYER_DISTANCE, just outside
     * PICKUP_R) and land exactly ON the ball. Without this, a ring of 2-3
     * players around a dead ball blocks the taker from reaching ON_BALL_EPS and
     * the restart/chase never completes -> match freezes for the whole half.
     */
    public static final double CLAIM_REACH_RADIUS = 0.7;
    public static final double MAX_FATIGUE_SPEED_LOSS = 0.30; // max 30% speed loss from fatigue

    /**
     * Lateral speed multiplier for goalkeepers. A keeper is not a slow
     * outfield player with a high pace value — his job is short, fast,
     * side-to-side movement in a set position, which the generic pace cap
     * (pace 11 -> 0.41 cells/tick) could not express.
     */
    public static final double GOALKEEPER_MOVEMENT_FACTOR = 1.9;
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
            p.setVelX(0.0);
            p.setVelY(0.0);
            if (p.isLocked() || p.isSentOff() || p.isInjured()) continue;

            // RIGID RULE (user 2026-09-23): a player who just struck the ball
            // (PASS/SHOT/CLEAR) stays rooted for the strike tick — the ball must
            // be visibly leaving his feet before he moves. Without this the
            // passer/shotter ran off to his tactical spot in the SAME tick the
            // ball left, so the ball looked frozen while the player glided away.
            if (p.getStrikeHoldTicks() > 0) {
                p.setStrikeHoldTicks(p.getStrikeHoldTicks() - 1);
                continue;
            }

            boolean isChaser = p == chaser;
            boolean isCarrier = p == state.getCarrier();
            Position current = p.getPosition();

            // RIGID RULE (user 2026-09-17): an off-ball carrier walks onto the ball
            // BEFORE any action starts — his target is the ball itself, and he runs
            // at normal pace (the carrier slow-down applies only once he is ON the
            // ball, i.e. continuous possession/dribbling).
            boolean carrierOffBall = isCarrier
                    && SimUtils.distance(current, state.getBall().getPosition()) > BallPhysicsEngine.ON_BALL_EPS;

            // A claiming player (restart taker / loose-ball chaser) within reach of
            // the stopped ball may push through a wall-ring onto the exact ball spot
            // (see CLAIM_REACH_RADIUS javadoc). Otherwise the dead ball with no one
            // able to step onto it would freeze the match.
            boolean nearClaimSpot = (isChaser || p == state.getRestartTaker())
                    && SimUtils.distance(current, state.getBall().getPosition()) <= CLAIM_REACH_RADIUS;

            Position target = isChaser ? state.getBall().getPosition() : p.getTarget();
            if (carrierOffBall) target = state.getBall().getPosition();
            if (target == null) continue;

            double pace = state.getRoundPaceSkill(p);
            double playerSpeed = playerSpeedFor(pace) * FatigueSystem.speedFactor(p);

            // Goalkeepers shuffle far quicker than outfield players. A keeper is
            // paced by his ability to move laterally in a set position, not by
            // his outfield pace, so the generic pace cap left him unable to
            // track a ball served into the far corner — he was always late.
            if (p.isGoalkeeper()) {
                playerSpeed *= GOALKEEPER_MOVEMENT_FACTOR;
            }

            // Carrier with ball moves slightly slower
            if (isCarrier && !carrierOffBall) {
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

            // Apply movement — step capped so the player EXACTLY reaches the
            // target instead of overshooting it and oscillating around it.
            // Previously the player always stepped the full playerSpeed, which
            // on the final approach (dist < playerSpeed) sailed past the target
            // and snapped back forever — the whole team jittered up/down within
            // the same cell with every tick re-decide.
            double step = Math.min(playerSpeed, dist);
            double moveX = (dx / dist) * step;
            double moveY = (dy / dist) * step;

            // Final approach: land exactly on the target (avoids floating-point
            // wiggle keeping the movement loop alive on the next tick).
            if (step >= dist - 1e-12) {
                moveX = dx;
                moveY = dy;
            }

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
            // RIGID RULE: the arriving carrier is allowed to reach the EXACT ball
            // spot even when another player sits within MIN_PLAYER_DISTANCE of it —
            // otherwise the 0.35 separation floor would bounce him forever just off
            // the ball and the on-ball decision gate would deadlock (match freeze).
            // He physically claims the ball spot; the other player's own separation
            // (computed when that player moves) clears them away. The claiming
            // taker/chaser gets the same right within CLAIM_REACH_RADIUS so a
            // wall-ring of players pressing the ball can never starve a restart.
            Position resolved = (carrierOffBall || nearClaimSpot)
                    ? newPosition
                    : separateFromOpponents(p, newPosition, state);
            newPosition = resolved;

            // FIELD BOUNDARY (user 2026-09-23): players NEVER leave the pitch.
            // Previously the MR/ML wide midfielders chased loose balls past the
            // touchline into the OOB zone; a throw-in pitched to that off-pitch
            // receiver then travelled ALONG the touchline outside the field for
            // the whole pass (ball in OOB for 4+ ticks) and every one of those
            // passes ended in another throw-in — a circular OOB churn. Clamping
            // the final position to the playing surface (goal lines 1.0/8.0,
            // touchlines 1.0/7.0) keeps throw-in takers (who walk to ON the
            // line) legal while no player can ever receive a ball from OOB.
            newPosition = new Position(
                    SimUtils.clamp(newPosition.getRow(), PitchEnvironment.HOME_GOAL_LINE, PitchEnvironment.AWAY_GOAL_LINE),
                    SimUtils.clamp(newPosition.getColumn(), PitchEnvironment.LEFT_TOUCHLINE, PitchEnvironment.RIGHT_TOUCHLINE)
            );

            p.setPosition(newPosition);
            p.setVelX(newPosition.getColumn() - current.getColumn());
            p.setVelY(newPosition.getRow() - current.getRow());

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