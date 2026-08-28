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
        // HARD RULE: during any restart (set piece pending), opponents within
        // 1 cell of the ball are pushed back toward their own goal.
        if (state.isSetPiecePending()) {
            enforceRestartPushback();
        }
        for (Player p : state.getPlayers()) {
            if (p.isLocked() || p.isSentOff() || p.isInjured()) continue;
            if (state.isBlockedAfterDuel(p) && p != state.getBall().getCarrier()) continue;
            Position target = p.getTarget();
            if (target == null) continue;

            boolean isCarrier = p == state.getBall().getCarrier();
            if (!isCarrier && !isActiveChase(p)) {
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
            Position proposed = moveProposal(p, target, moveSpeed);
            Position safe = findSafePosition(p, proposed, target);
            if (activeChase && SimUtils.distance(safe, current) <= 1e-12) {
                Position escape = findChaseDetour(p, target);
                if (escape != null) {
                    chaseDetours.put(p, escape);
                    p.setTarget(escape);
                    target = escape;
                    safe = moveProposal(p, escape, moveSpeed);
                }
            }
            // Clamp to playable area (rows 1-7, cols 1-6) — players must never
            // appear at OOB columns 0/7 or rows 0/8 in the viewer.
            safe = clampToField(safe);
            p.setPosition(safe);
            if (SimUtils.distance(safe, target) < 1e-6) {
                if (activeChase && chaseDetours.remove(p) != null) {
                    p.setTarget(state.getBall().getPosition());
                } else {
                    p.setTarget(null);
                }
            }
        }

        // Idle drift: players with no target drift slightly toward the ball
        Position ballPos = state.getBall().getPosition();
        for (Player p : state.getPlayers()) {
            if (p.isLocked() || p.isSentOff() || p.isInjured()) continue;
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
            double driftSpeed = IDLE_DRIFT_SPEED * fatigueSpeedMultiplier(p);
            double step = Math.min(driftSpeed, dist);
            Position proposed = new Position(
                    SimUtils.clamp(current.getRow() + dy / dist * step, 1, 7),
                    SimUtils.clamp(current.getColumn() + dx / dist * step, 1, 6));
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
        double minCol = target.getColumn() < 1 ? 1 : 1;
        double maxCol = target.getColumn() > 6 ? 6 : 6;
        return new Position(
                SimUtils.clamp(pos.getRow() + dy / dist * speed, minRow, maxRow),
                SimUtils.clamp(pos.getColumn() + dx / dist * speed, minCol, maxCol));
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

    /** Clamp a position to the playable field area (rows 1-7, cols 1-6). */
    private static Position clampToField(Position pos) {
        double r = SimUtils.clamp(pos.getRow(), 1.0, 7.0);
        double c = SimUtils.clamp(pos.getColumn(), 1.0, 6.0);
        return new Position(r, c);
    }

    private static Position clampPos(double row, double col) {
        if (row == 8.0) return new Position(8.0, SimUtils.clamp(col, 1, 6));
        if (row == 0.0) return new Position(0.0, SimUtils.clamp(col, 1, 6));
        return new Position(SimUtils.clamp(row, 1, 7), SimUtils.clamp(col, 1, 6));
    }

    private double fatigueSpeedMultiplier(Player p) {
        return 1.0 - p.getFatigue() * MAX_FATIGUE_SPEED_LOSS;
    }
}
