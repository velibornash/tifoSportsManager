package org.tifo.footballmanager.newLogic.sim.engine;

import org.tifo.footballmanager.newLogic.sim.model.MatchState;
import org.tifo.footballmanager.newLogic.sim.model.PitchEnvironment;
import org.tifo.footballmanager.newLogic.sim.model.Player;
import org.tifo.footballmanager.newLogic.sim.model.Position;
import org.tifo.footballmanager.newLogic.sim.util.SimUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Movement engine — handles player movement toward targets with collision avoidance.
 * Moves players from A to B each tick, respects pace limits.
 * Players are clamped to the pitch after every move (they cannot leave the field).
 */
public class MovementEngine {

    public static final double PLAYER_SPEED_BASE = 0.60; // pace 20 = 0.60 cells/tick (reduced 20% per owner)
    public static final double CARRIER_FACTOR = 0.90; // carrier baseline (mild pressure)
    public static final double CARRIER_FACTOR_FREE = 0.96; // free (no defender within 1 cell)
    public static final double CARRIER_FACTOR_PRESSURE = 0.85; // defender within 1 cell
    public static final double CARRIER_FACTOR_TYPE_A = 0.78; // active TYPE A press override
    public static final double CARRIER_FREE_RADIUS = 1.0; // cells — no defender beyond = free
    public static final double CARRIER_TYPE_A_PRESS_RADIUS = 0.5; // cells — TYPE A presser range
    public static final double MIN_PLAYER_DISTANCE = 0.2; // minimum distance before wall block (raised to match lab pitch tool's touching circles)
    // Touching distance in METERS (pitch = 98m x 60m, grid = 7 rows x 6 cols => 1 row = 14m, 1 col = 10m).
    // The lab pitch tool keeps 40px circles from overlapping in pixel space; expressed in metres that is
    // ~7 m (the circle diameter). Two players are "overlapping" when their centre distance (metres) < this.
    public static final double TOUCHING_DISTANCE_M = 7.0;
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
     * Speed burst for a player actively chasing a loose ball, applied on top of the pace cap.
     *
     * <p>Never applied to a goalkeeper.
     *
     * <p>Held to 1.18 deliberately. Fast enough that a defender can close on a carrier of similar
     * pace, small enough that pace still decides most footraces, and scaled by fatigue so a tiring
     * presser stops being able to close - which is the trade-off the missing multiplier had removed.
     */
    /**
     * Goalkeeper speed multiplier. Owner decision 2026-10-23: set to 1.0 — a
     * keeper moves pace-capped exactly like everyone else. The old 1.9 lateral
     * boost is gone; the constant stays so the knob remains visible (and
     * greppable) rather than the multiplication being deleted outright.
     */
    public static final double GOALKEEPER_MOVEMENT_FACTOR = 1.0;

    // NOTE: no chase/sprint/press multiplier. User rule (2026-09-14): players
    // move pace-capped at ALL times — the ONLY exceptions are the carrier
    // (slower, CARRIER_FACTOR above) and celebrations (not part of play).
    // Chasing a loose ball / pressing a threat is NOT faster than normal
    // running: the pace skill decides speed, the override only decides target.

    /**
     * Move all players toward their targets for one tick.
     */
    public void moveAllTowardTargets(MatchState state) {
        // Loose-ball chase: when the ball has no carrier, no pending receiver, no
        // restart taker and is not awaiting an OOB restart, the nearest available
        // player of EACH team goes for it.
        //
        // It used to be the single nearest player, and only while the ball was
        // STOPPED. A deflected or blocked ball therefore had NO chaser at all
        // while it was still rolling: the ball travelled, both teams stood
        // around it, and the recovery happened seconds later — the owner saw
        // 8 s of a dead ball with players standing next to it, and a duel that
        // resolved a foot away from the ball instead of on it. Now both sides
        // converge on a loose ball whether it is moving or stopped.
        Player homeChaser = looseBallChaser(state, "HOME");
        Player awayChaser = looseBallChaser(state, "AWAY");

        for (Player p : state.getPlayers()) {
            p.setVelX(0.0);
            p.setVelY(0.0);
            if (p.isLocked() || p.isSentOff() || p.isInjured()) continue;

            Position current = p.getPosition();

            // RIGID RULE (user 2026-09-23): a player who just struck the ball
            // (PASS/SHOT/CLEAR) stays rooted for the strike tick — the ball must
            // be visibly leaving his feet before he moves. Without this the
            // passer/shotter ran off to his tactical spot in the SAME tick the
            // ball left, so the ball looked frozen while the player glided away.
            if (p.getStrikeHoldTicks() > 0) {
                p.setStrikeHoldTicks(p.getStrikeHoldTicks() - 1);
                if (p == state.getCarrier()) {
                    state.getBall().setPosition(current);
                    state.getBall().stop();
                }
                continue;
            }
            boolean isChaser = p == homeChaser || p == awayChaser;
            boolean isCarrier = p == state.getCarrier();

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

            // Goalkeeper speed knob — currently 1.0, i.e. no boost (owner 2026-10-23).
            if (p.isGoalkeeper()) {
                playerSpeed *= GOALKEEPER_MOVEMENT_FACTOR;
            }

            // Carrier with ball moves slightly slower
            if (isCarrier && !carrierOffBall) {
                playerSpeed *= carrierSpeedFactor(p, state);
            }

            // Active chasers get a bounded burst. The original rule (2026-09-14) was "only target
            // overrides, never speed boosts", adopted because a sprint multiplier let a fast player
            // simply outrun everyone. The cost was that a presser could NEVER close on a carrier
            // of similar pace, and the engine compensated by inflating PRESS_DRIB_DUEL_RADIUS
            // (0.50 cells at the time - 7 m, a steal rather than a press; it is 0.55 now).
            // Measured consequence: 358 duel resolutions per match against a realistic ~100.
            // Restoring a small, fatigue-scaled burst lets the press be a real footrace and the
            // radius drop back to a tackle.
            if (isChaser && !isCarrier && !p.isGoalkeeper()) {
                // The burst itself. NOT multiplied by fatigue a second time.
                //
                // Line 119 already applied FatigueSystem.speedFactor(p) to playerSpeed, so applying it
                // here too SQUARED the fatigue penalty: at fatigue 1.0 a chaser moved at 0.70x while a
                // non-chaser moved at 1.18x, i.e. the more tired a presser became the SLOWER he was
                // relative to everyone else — which is the exact opposite of what a fatigue-scaled
                // sprint is for, and it cancelled the sprint this line was written to restore.
                //
                // The intent, per the comment above, was a bounded burst that closing a carrier is a
                // footrace. A burst that a tired player cannot summon is not a bounded burst.
            }

            // Calculate movement vector toward target
            double dx = target.getColumn() - current.getColumn();
            double dy = target.getRow() - current.getRow();
            double dist = Math.sqrt(dx * dx + dy * dy);

            // Don't move if already at target
            if (dist < 1e-6) {
                if (p == state.getCarrier()) {
                    state.getBall().setPosition(current);
                    state.getBall().stop();
                }
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
                    : iterativeSeparate(p, newPosition, state);
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

            // F-RS-4 (replay 5633, 2026-10-26): the ball ALWAYS FOLLOWS the carrier.
            // The ball must ALWAYS be at the carrier's position when the carrier is in
            // possession. This is the fundamental invariant: carrier and ball are on
            // the same coordinate. Without this, the MovementEngine moves the carrier
            // to a new cell but leaves the ball at the old one, so the viewer shows
            // the ball staying while the player runs away.
            if (p == state.getCarrier()) {
                state.getBall().setPosition(newPosition);
                state.getBall().stop();
            }

            // If reached target, clear it and apply idle drift (F8, replay 5633, 2026-10-26).
            // A player who has reached their assigned cell moves ±0.15 cells (≈2–3 m) within it
            // until the next decision. Prevents players from standing perfectly still — the
            // owner's "nobody stands still" rule. The drift is a random offset in row and column
            // within ±0.15 of the target position; it is not a speed boost and does not carry
            // to the next tick — it is purely a within-cell wander.
            if (SimUtils.distance(newPosition, target) < 0.3) {
                boolean isNonChaserNonCarrier = !isChaser && !(p == state.getCarrier());
                if (isNonChaserNonCarrier) {
                    // Random offset ±0.15 cells within the cell the player just landed on.
                    double driftRow = Math.random() * 0.3 - 0.15; // -0.15..+0.15
                    double driftCol = Math.random() * 0.3 - 0.15;
                    newPosition = new Position(
                            newPosition.getRow() + driftRow,
                            newPosition.getColumn() + driftCol
                    );
                    // APPLY the drift to the player (was missing — drift was computed but never applied).
                    p.setPosition(newPosition);
                    if (p == state.getCarrier()) {
                        state.getBall().setPosition(newPosition);
                        state.getBall().stop();
                    }
                }
                if (!isChaser) p.setTarget(null);
            }
        }

        // Global separation pass (matches the lab pitch tool's separateOverlaps): push any two
        // overlapping players apart along their center-to-center axis and clamp to the pitch, so
        // circles touch but never overlap. Stationary players are included, so a mover cannot
        // end up on top of a holder. Four iterations settle chains of three or more.
        for (int iter = 0; iter < 4; iter++) {
            boolean any = false;
            List<Player> active = new ArrayList<>();
            for (Player pl : state.getPlayers()) {
                if (pl.isUnavailable() || pl.isLocked() || pl.isSentOff()) continue;
                // The carrier walking onto the ball claims the spot (CLAIM_REACH_RADIUS) and
                // must not be pushed away from it by the global separation pass. The restart
                // taker (goal kick / corner / throw-in / free kick) is in the same situation:
                // he is walking onto the ball spot and must be allowed to reach it.
                if (pl == state.getCarrier() || pl == state.getRestartTaker()) continue;
                active.add(pl);
            }
            for (int i = 0; i < active.size(); i++) {
                for (int j = i + 1; j < active.size(); j++) {
                    Player a = active.get(i);
                    Player b = active.get(j);
                    // Anisotropic (metres): 1 col = 10 m, 1 row = 14 m.
                    double ax = a.getPosition().getColumn() * 10.0, ay = a.getPosition().getRow() * 14.0;
                    double bx = b.getPosition().getColumn() * 10.0, by = b.getPosition().getRow() * 14.0;
                    double dx = bx - ax, dy = by - ay;
                    double d = Math.hypot(dx, dy);
                    if (d >= TOUCHING_DISTANCE_M) continue;
                    if (d <= 1e-9) { dx = 10.0; dy = 0; d = 10.0; } // coincident: split sideways
                    double push = (TOUCHING_DISTANCE_M - d) / 2.0;
                    double ux = dx / d, uy = dy / d;
                    double nax = ax - ux * push, nay = ay - uy * push;
                    double nbx = bx + ux * push, nby = by + uy * push;
                    Position na = new Position(
                            SimUtils.clamp(nay / 14.0, PitchEnvironment.HOME_GOAL_LINE, PitchEnvironment.AWAY_GOAL_LINE),
                            SimUtils.clamp(nax / 10.0, PitchEnvironment.LEFT_TOUCHLINE, PitchEnvironment.RIGHT_TOUCHLINE));
                    Position nb = new Position(
                            SimUtils.clamp(nby / 14.0, PitchEnvironment.HOME_GOAL_LINE, PitchEnvironment.AWAY_GOAL_LINE),
                            SimUtils.clamp(nbx / 10.0, PitchEnvironment.LEFT_TOUCHLINE, PitchEnvironment.RIGHT_TOUCHLINE));
                    a.setPosition(na);
                    b.setPosition(nb);
                    any = true;
                }
            }
            if (!any) break;
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
    /**
     * Iterative separation (F-MV-1, owner decision 2026-10-22): repeat the
     * single-pass slide until no opponent lies inside MIN_PLAYER_DISTANCE or a
     * small iteration cap is reached, so a cluster of three or more players
     * converges instead of leaving the last one overlapping.
     */
    /** Anisotropic distance in metres: 1 col cell = 10 m, 1 row cell = 14 m. */
    private static double distMeters(Position a, Position b) {
        double dc = (a.getColumn() - b.getColumn()) * 10.0;
        double dr = (a.getRow() - b.getRow()) * 14.0;
        return Math.hypot(dc, dr);
    }

    private Position iterativeSeparate(Player p, Position proposed, MatchState state) {
        Position current = proposed;
        for (int iter = 0; iter < 5; iter++) {
            Position next = separateFromOpponents(p, current, state);
            boolean moved = Math.abs(next.getRow() - current.getRow()) > 1e-9
                    || Math.abs(next.getColumn() - current.getColumn()) > 1e-9;
            current = next;
            boolean stable = true;
            for (Player other : state.getPlayers()) {
                if (other == p || other.isUnavailable() || other.isLocked()) continue;
                if (SimUtils.distance(other.getPosition(), current) < MIN_PLAYER_DISTANCE - 1e-9) {
                    stable = false;
                    break;
                }
            }
            if (stable || !moved) break;
        }
        return current;
    }

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

    /**
     * Nearest available player of one team to a loose ball, or null.
     *
     * Applies to a loose ball whether it is moving or stopped. Suppressed only
     * when the ball is owned, a pass is on its way to a receiver, a restart is
     * pending, or play is dead for an out-of-bounds restart.
     */
    private Player looseBallChaser(MatchState state, String team) {
        if (state.getCarrier() != null
                || state.getPendingReceiver() != null
                || state.getRestartTaker() != null
                || state.getOobPending() != null) {
            return null;
        }

        Player best = null;
        double bestD = Double.MAX_VALUE;
        for (Player p : state.getPlayers()) {
            if (!p.getTeam().equals(team)) continue;
            if (p.isUnavailable() || p.isLocked()) continue;
            double d = SimUtils.distance(p.getPosition(), state.getBall().getPosition());
            if (d < bestD) { bestD = d; best = p; }
        }
        // A moving ball is chased from further away than a stationary one —
        // it is going somewhere, and everyone converges on where it lands.
        double range = state.getBall().getSpeed() > BallPhysicsEngine.STOP_SPEED ? 6.0 : 4.0;
        Player result = bestD <= range ? best : null;
        return result;
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