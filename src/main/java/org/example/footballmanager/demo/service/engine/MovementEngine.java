package org.example.footballmanager.demo.service.engine;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.model.Player;
import org.example.footballmanager.demo.service.model.Position;

import java.util.HashMap;
import java.util.Map;

/**
 * Player movement engine — smooth movement toward targets with collision avoidance.
 * Identical logic to demo/MovementEngine but using service model.
 */
public class MovementEngine {

    public static final double PLAYER_SPEED = 0.25;
    private static final double MIN_PLAYER_DISTANCE = 0.35;
    private static final double MAX_FATIGUE_SPEED_LOSS = 0.30; // max 30% speed reduction from fatigue
    private static final double IDLE_DRIFT_SPEED = 0.04; // idle drift toward ball when no tactical target

    private final MatchState state;
    private final Map<Player, Position> chaseDetours = new HashMap<>();

    public MovementEngine(MatchState state) {
        this.state = state;
    }

    public void moveAllTowardTargets() {
        // GK HARD INVARIANT: a goalkeeper is always snapped back into its own
        // goal zone at the start of every movement pass. No matter which path
        // drifted the keeper upfield (duel snap, loose-ball claim, pass pickup,
        // restart), it is pulled right back in front of its own goal each tick.
        // This is a belt-and-braces guard on top of the clamp in the move loop.
        for (Player p : state.getPlayers()) {
            if ("GK".equals(p.getRole()) && !p.isSentOff() && !p.isInjured()) {
                p.setPosition(new Position(
                        GoalkeeperMovementEngine.clampGkToZone(p, p.getPosition().getRow()),
                        p.getPosition().getColumn()));
            }
        }
        // HARD RULE: during any restart (set piece pending), opponents within
        // 1 cell of the ball are pushed back toward their own goal.
        if (state.isSetPiecePending()) {
            enforceRestartPushback();
        }
        for (Player p : state.getPlayers()) {
            if (p.isLocked() || p.isSentOff() || p.isInjured()) continue;
            if (state.isBlockedAfterDuel(p) && p != state.getBall().getCarrier()) {
                // A player recovering from a lost duel does NOT freeze — he drifts
                // back toward his own goal (the recovery is short, 3-4s) instead of
                // standing dead still while play continues around him.
                retreatTowardOwnGoal(p);
                continue;
            }
            Position target = p.getTarget();
            if (target == null) continue;

            boolean isCarrier = p == state.getBall().getCarrier();
            // Goalkeepers are exempt from the non-carrier 1-cell/round positional
            // cap: a keeper must cut the angle and track the ball laterally in
            // real time (a 14m cell is far too coarse for keeper footwork). Let
            // the goalkeeper move toward its reactive target at full speed.
            boolean isGoalkeeper = "GK".equals(p.getRole());
            if (!isCarrier && !isGoalkeeper && !isActiveChase(p)) {
                Position roundStart = state.getRoundStartPosition(p);
                int pace = state.getRoundPaceSkill(p);
                double maxDistance = pace / 20.0;
                if (roundStart != null) {
                    double alreadyMoved = Math.max(
                            Math.abs(p.getPosition().getRow() - roundStart.getRow()),
                            Math.abs(p.getPosition().getColumn() - roundStart.getColumn()));
                    if (alreadyMoved >= maxDistance - 1e-6) continue;
                }
            }

            Position current = p.getPosition();
            boolean activeChase = isActiveChase(p);
            Position detour = chaseDetours.get(p);
            if (activeChase && detour != null && SimUtils.distance(current, detour) <= 1e-6) {
                chaseDetours.remove(p);
                p.setTarget(state.getBall().getPosition());
                target = p.getTarget();
            }

            double moveSpeed = activeChase ? PLAYER_SPEED * 3 * fatigueSpeedMultiplier(p) : PLAYER_SPEED * fatigueSpeedMultiplier(p);
            // Threat-overridden defenders get a speed boost so they can close the
            // gap on the ball carrier. Without this, carrier and defender move at
            // the same speed (0.25 cells/tick) and the gap never closes — the
            // defender chases from behind forever. With 1.6x boost the defender
            // gains ~0.15 cells/tick when the carrier runs directly away, closing
            // a 1.0 cell gap in ~20 ticks (~10 seconds) — fast enough to reach
            // duel range (0.15 cells) before the carrier can carry far.
            if (p.isThreatOverrideActive() && !isCarrier) {
                moveSpeed *= 1.6;
            }
            // Carrier always uses the raw proposed position — collision avoidance
            // does NOT apply to the ball carrier. The carrier has ball priority;
            // other players yield to it. The carrier is the only player that moves
            // toward its target regardless of collisions.
            Position proposed = moveProposal(p, target, moveSpeed);
            Position safe;
            if (isCarrier) {
                safe = proposed;
            } else {
                safe = findSafePosition(p, proposed, target);
                // Active chasers (receivers/chasers) must never move backward
                // away from the ball: if collision avoidance pushes them to a
                // position farther from the target, fall back to proposed.
                if (activeChase && SimUtils.distance(safe, target) > SimUtils.distance(current, target)) {
                    safe = proposed;
                }
                if (activeChase && SimUtils.distance(safe, current) <= 1e-12) {
                    Position escape = findChaseDetour(p, target);
                    if (escape != null) {
                        chaseDetours.put(p, escape);
                        p.setTarget(escape);
                        target = escape;
                        safe = moveProposal(p, escape, moveSpeed);
                    }
                }
                if (activeChase && SimUtils.distance(safe, current) <= 1e-12) {
                    safe = proposed;
                }
            }
            // Clamp to the field of play — goal lines at row 1.0 (home) and
            // row 8.0 (away), touchlines at col 1.0/7.0. EVERY player,
            // including the ball carrier, stays within the field. Nothing may
            // appear past the goal lines or touchlines in the viewer.
            safe = clampToField(safe);
            // A goalkeeper must never leave its own goal area, regardless of the
            // target (chase, duel, restart, tactical) — right in front of goal.
            if ("GK".equals(p.getRole())) {
                safe = new Position(
                        GoalkeeperMovementEngine.clampGkToZone(p, safe.getRow()),
                        safe.getColumn());
            }
            p.setPosition(safe);
            if (SimUtils.distance(safe, target) < 1e-6) {
                if (activeChase && chaseDetours.remove(p) != null) {
                    p.setTarget(state.getBall().getPosition());
                } else {
                    p.setTarget(null);
                }
            }
        }

        // Idle drift: players with no target drift slightly toward the ball.
        // Goalkeepers are EXCLUDED — a keeper must hold its goal-side reactive
        // position, never drift out toward a loose/far ball.
        Position ballPos = state.getBall().getPosition();
        for (Player p : state.getPlayers()) {
            if (p.isLocked() || p.isSentOff() || p.isInjured()) continue;
            if ("GK".equals(p.getRole())) continue;
            if (p == state.getBall().getCarrier()) continue;
            if (state.isBlockedAfterDuel(p)) continue;
            if (isActiveChase(p)) continue;
            Position target = p.getTarget();
            if (target != null) continue; // has a real target, skip
            // Drift toward ball at reduced speed
            Position current = p.getPosition();
            double dx = ballPos.getColumn() - current.getColumn();
            double dy = ballPos.getRow() - current.getRow();
            double dist = Math.hypot(dx, dy);
            if (dist < 0.5) continue; // too close to ball, no drift
            double driftSpeed = IDLE_DRIFT_SPEED;
            double step = Math.min(driftSpeed, dist);
            Position proposed = new Position(
                    SimUtils.clamp(current.getRow() + dy / dist * step, 1.0, 8.0),
                    SimUtils.clamp(current.getColumn() + dx / dist * step, 1.0, 6.9));
            if (!wouldOverlap(p, proposed)) {
                p.setPosition(proposed);
            }
        }
    }

    private boolean isActiveChase(Player player) {
        return state.isActiveChaser(player);
    }

    /**
     * HARD RULE — restart pushback (corePrinciples: set pieces).
     *
     * During ANY restart (goal kick, corner, throw-in, free kick) an opponent who
     * is closer than MIN_RESTART_DISTANCE (1 cell) to the ball must drop back
     * toward their own goal until they are at least MIN_RESTART_DISTANCE away.
     * This guarantees the restart is never contested from inside 1 cell.
     */
    public static final double MIN_RESTART_DISTANCE = 1.0;

    public void enforceRestartPushback() {
        Position ballPos = state.getBall().getPosition();
        double ballRow = ballPos.getRow();
        // The team executing the restart: the designated set-piece taker, or the
        // current carrier. Opponents of that team are pushed back — the taker's
        // OWN team (including the taker walking to the ball) is never pushed.
        Player taker = state.getFreeKickTaker();
        Player carrier = state.getBall().getCarrier();
        String restartTeam = null;
        if (carrier != null) restartTeam = carrier.getTeam();
        else if (taker != null) restartTeam = taker.getTeam();
        if (restartTeam == null) return;

        for (Player p : state.getPlayers()) {
            if (p.isSentOff() || p.isInjured()) continue;
            if (p.getTeam().equals(restartTeam)) continue;
            double dist = SimUtils.distance(p.getPosition(), ballPos);
            if (dist < MIN_RESTART_DISTANCE) {
                boolean pHome = "HOME".equals(p.getTeam());
                // Push the opponent AWAY from the ball toward their OWN goal.
                // ownRow is the row of the player's own goal line (HOME defends
                // row 1, AWAY defends row 7). Moving along sign(ownRow - ballRow)
                // increases the row-distance from the ball in the direction of
                // the player's own half.
                double ownRow = pHome ? 1.0 : 7.0;
                double dir = Math.signum(ownRow - ballRow);
                double push = MIN_RESTART_DISTANCE - dist + 0.1;
                double pushedRow = ballRow + push * dir;
                pushedRow = SimUtils.clamp(pushedRow, 1.0, 7.0);
                p.setPosition(new Position(pushedRow, p.getPosition().getColumn()));
                p.setTarget(null);
            }
        }
    }

    private static Position moveProposal(Player p, Position target, double speed) {
        Position pos = p.getPosition();
        double dx = target.getColumn() - pos.getColumn();
        double dy = target.getRow() - pos.getRow();
        double dist = Math.hypot(dx, dy);
        if (dist <= speed) return target;
        double minRow = target.getRow() < 1 ? 0 : 1;
        double maxRow = target.getRow() > 7 ? 8 : 7;
        double minCol = target.getColumn() < 1 ? 0 : 1;
        double maxCol = target.getColumn() > 6 ? 6.9 : 6;
        return new Position(
                SimUtils.clamp(pos.getRow() + dy / dist * speed, minRow, maxRow),
                SimUtils.clamp(pos.getColumn() + dx / dist * speed, minCol, maxCol));
    }

    /**
     * A player recovering from a lost duel drifts back toward his own goal cell
     * at reduced speed (own goal: HOME row 1, AWAY row 7). This keeps the beaten
     * player alive on the pitch instead of standing frozen for the short cooldown.
     */
    private void retreatTowardOwnGoal(Player p) {
        Position pos = p.getPosition();
        boolean home = "HOME".equals(p.getTeam());
        double ownRow = home ? 1.0 : 8.0;
        double step = PLAYER_SPEED * 0.6;
        double dir = Math.signum(ownRow - pos.getRow());
        double newRow = SimUtils.clamp(pos.getRow() + dir * step, 1.0, 8.0);
        double newCol = SimUtils.clamp(pos.getColumn(), 1.0, 6.9);
        p.setPosition(new Position(newRow, newCol));
        p.setTarget(null);
    }

    private Position findSafePosition(Player p, Position proposed, Position target) {
        if (!wouldOverlap(p, proposed)) return proposed;
        Position current = p.getPosition();
        double dx = proposed.getColumn() - current.getColumn();
        double dy = proposed.getRow() - current.getRow();
        double len = Math.hypot(dx, dy);
        if (len < 1e-6) return current;

        double step = Math.min(PLAYER_SPEED, len);
        double perpX = -dy / len * step;
        double perpY = dx / len * step;

        Position best = null;
        double bestScore = Double.MAX_VALUE;

        Position[] candidates = {
            clampPos(current.getRow() + perpY, current.getColumn() + perpX),
            clampPos(current.getRow() - perpY, current.getColumn() - perpX),
            clampPos(current.getRow() + perpY * 0.5, current.getColumn() + perpX * 0.5),
            clampPos(current.getRow() - perpY * 0.5, current.getColumn() - perpX * 0.5),
            clampPos(current.getRow(), current.getColumn() + dx),
            clampPos(current.getRow() + dy, current.getColumn()),
            clampPos(current.getRow() + step, current.getColumn()),
            clampPos(current.getRow() - step, current.getColumn()),
            clampPos(current.getRow(), current.getColumn() + step),
            clampPos(current.getRow(), current.getColumn() - step),
            clampPos(current.getRow() + step, current.getColumn() + step),
            clampPos(current.getRow() + step, current.getColumn() - step),
            clampPos(current.getRow() - step, current.getColumn() + step),
            clampPos(current.getRow() - step, current.getColumn() - step),
        };

        for (Position alt : candidates) {
            if (!wouldOverlap(p, alt)) {
                double score = SimUtils.distance(alt, target);
                if (score < bestScore) {
                    best = alt;
                    bestScore = score;
                }
            }
        }
        return best != null ? best : current;
    }

    private boolean wouldOverlap(Player p, Position candidate) {
        for (Player other : state.getPlayers()) {
            if (other == p) continue;
            if (SimUtils.distance(candidate, other.getPosition()) < MIN_PLAYER_DISTANCE) {
                return true;
            }
        }
        return false;
    }

    private Position findChaseDetour(Player chaser, Position ballTarget) {
        Position current = chaser.getPosition();
        Position direct = moveProposal(chaser, ballTarget, PLAYER_SPEED);
        Player blocker = null;
        double blockerDist = Double.MAX_VALUE;
        for (Player other : state.getPlayers()) {
            if (other == chaser) continue;
            if (SimUtils.distance(direct, other.getPosition()) < MIN_PLAYER_DISTANCE
                    || SimUtils.distance(current, other.getPosition()) < MIN_PLAYER_DISTANCE + PLAYER_SPEED) {
                double d = SimUtils.distance(other.getPosition(), direct);
                if (d < blockerDist) {
                    blockerDist = d;
                    blocker = other;
                }
            }
        }
        if (blocker == null) return null;

        double rowDelta = blocker.getPosition().getRow() - current.getRow();
        double colDelta = blocker.getPosition().getColumn() - current.getColumn();
        double length = Math.hypot(rowDelta, colDelta);
        if (length < 1e-9) length = 1;
        double sideRow = -colDelta / length;
        double sideCol = rowDelta / length;
        Position[] candidates = {
                clampPos(blocker.getPosition().getRow() + sideRow * 0.55,
                        blocker.getPosition().getColumn() + sideCol * 0.55),
                clampPos(blocker.getPosition().getRow() - sideRow * 0.55,
                        blocker.getPosition().getColumn() - sideCol * 0.55),
                clampPos(blocker.getPosition().getRow() + sideRow * 0.8,
                        blocker.getPosition().getColumn() + sideCol * 0.8),
                clampPos(blocker.getPosition().getRow() - sideRow * 0.8,
                        blocker.getPosition().getColumn() - sideCol * 0.8)
        };
        Position best = null;
        double bestScore = Double.MAX_VALUE;
        for (Position candidate : candidates) {
            if (!wouldOverlap(chaser, candidate)) {
                double score = SimUtils.distance(current, candidate)
                        + SimUtils.distance(candidate, ballTarget) * 0.15;
                if (score < bestScore) {
                    best = candidate;
                    bestScore = score;
                }
            }
        }
        return best;
    }

    /**
     * Clamp a position to the field of play. Goal lines at row 1.0 (home) and
     * row 8.0 (away); touchlines at col 1.0 and 7.0. A player on the field can
     * reach the exact goal line / touchline but never cross into OOB.
     */
    private static Position clampToField(Position pos) {
        // Touchlines at col 1.0 and 7.0. A player's CENTER must stay inside the
        // line (col ≤ 6.9) so his body radius never pokes past the 7.0 touchline.
        double r = SimUtils.clamp(pos.getRow(), 1.0, 8.0);
        double c = SimUtils.clamp(pos.getColumn(), 1.0, 6.9);
        return new Position(r, c);
    }

    private static Position clampPos(double row, double col) {
        return new Position(SimUtils.clamp(row, 1.0, 8.0), SimUtils.clamp(col, 1.0, 6.9));
    }

    /** Fatigue reduces movement speed — tired players are slower (corePrinciples §11). */
    private double fatigueSpeedMultiplier(Player p) {
        return 1.0 - p.getFatigue() * MAX_FATIGUE_SPEED_LOSS;
    }
}
