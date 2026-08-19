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

    public static final double PLAYER_SPEED = 0.015;
    private static final double MIN_PLAYER_DISTANCE = 0.35;

    private final MatchState state;
    private final Map<Player, Position> chaseDetours = new HashMap<>();

    public MovementEngine(MatchState state) {
        this.state = state;
    }

    public void moveAllTowardTargets() {
        for (Player p : state.getPlayers()) {
            if (p.isLocked()) continue;
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

            Position proposed = moveProposal(p, target, PLAYER_SPEED);
            Position safe = findSafePosition(p, proposed, target);
            if (activeChase && SimUtils.distance(safe, current) <= 1e-12) {
                Position escape = findChaseDetour(p, target);
                if (escape != null) {
                    chaseDetours.put(p, escape);
                    p.setTarget(escape);
                    target = escape;
                    safe = moveProposal(p, escape, PLAYER_SPEED);
                }
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
    }

    private boolean isActiveChase(Player player) {
        return state.isActiveChaser(player);
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
        double maxCol = target.getColumn() > 6 ? 7 : 6;
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

    private static Position clampPos(double row, double col) {
        if (row == 8.0) return new Position(8.0, SimUtils.clamp(col, 1, 6));
        if (row == 0.0) return new Position(0.0, SimUtils.clamp(col, 1, 6));
        return new Position(SimUtils.clamp(row, 1, 7), SimUtils.clamp(col, 1, 6));
    }
}
