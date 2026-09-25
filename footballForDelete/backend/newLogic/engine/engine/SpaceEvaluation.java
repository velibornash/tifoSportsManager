package org.example.footballmanager.newLogic.engine;

import org.example.footballmanager.newLogic.model.MatchState;
import org.example.footballmanager.newLogic.model.PlayerSnapshot;
import org.example.footballmanager.newLogic.model.Position;

import java.util.HashMap;
import java.util.Map;

public final class SpaceEvaluation {

    private final Map<Long, SpaceData> spaceMap = new HashMap<>();

    public void update(MatchState state) {
        spaceMap.clear();

        for (PlayerSnapshot snap : state.playerSnapshots) {
            SpaceData data = computeSpace(snap, state);
            spaceMap.put(snap.playerId(), data);
        }
    }

    public SpaceData get(long playerId) {
        return spaceMap.getOrDefault(playerId, SpaceData.EMPTY);
    }

    private SpaceData computeSpace(PlayerSnapshot snap, MatchState state) {
        double openSpace = calculateOpenSpace(snap, state);
        double pressure = calculatePressure(snap, state);
        double passingLane = calculatePassingLane(snap, state);
        double shootingLane = calculateShootingLane(snap, state);
        double defensiveCover = calculateDefensiveCover(snap, state);

        return new SpaceData(openSpace, pressure, passingLane, shootingLane, defensiveCover);
    }

    private double calculateOpenSpace(PlayerSnapshot snap, MatchState state) {
        double minDist = Double.MAX_VALUE;
        for (PlayerSnapshot other : state.playerSnapshots) {
            if (other.playerId() == snap.playerId()) continue;
            double dist = snap.distanceTo(other);
            if (dist < minDist) minDist = dist;
        }
        return Math.min(1.0, minDist / 15.0);
    }

    private double calculatePressure(PlayerSnapshot snap, MatchState state) {
        int opponentCount = 0;
        for (PlayerSnapshot other : state.playerSnapshots) {
            if (other.teamSide().equals(snap.teamSide())) continue;
            double dist = snap.distanceTo(other);
            if (dist < 8.0) opponentCount++;
        }
        return Math.min(1.0, opponentCount / 3.0);
    }

    private double calculatePassingLane(PlayerSnapshot snap, MatchState state) {
        if (state.carrierId == null) return 0.5;
        PlayerSnapshot carrier = state.snapshotById(state.carrierId);
        if (carrier == null) return 0.5;

        double dist = snap.distanceTo(carrier);
        if (dist > 30.0) return 0.3;

        int blockers = 0;
        for (PlayerSnapshot other : state.playerSnapshots) {
            if (other.teamSide().equals(snap.teamSide())) continue;
            if (isInPassingLane(carrier, snap, other)) blockers++;
        }

        return Math.max(0.0, 1.0 - (blockers * 0.3));
    }

    private double calculateShootingLane(PlayerSnapshot snap, MatchState state) {
        double goalX = snap.teamSide().equals("HOME") ? 96.0 : 4.0;
        double goalY = 50.0;

        double distToGoal = snap.distanceToPoint(goalX, goalY);
        if (distToGoal > 30.0) return 0.0;

        int blockers = 0;
        for (PlayerSnapshot other : state.playerSnapshots) {
            if (other.teamSide().equals(snap.teamSide())) continue;
            if (isInShootingLane(snap, goalX, goalY, other)) blockers++;
        }

        return Math.max(0.0, 1.0 - (blockers * 0.25));
    }

    private double calculateDefensiveCover(PlayerSnapshot snap, MatchState state) {
        if (snap.position() != Position.DEF) return 0.5;

        int nearbyDefenders = 0;
        for (PlayerSnapshot other : state.playerSnapshots) {
            if (!other.teamSide().equals(snap.teamSide())) continue;
            if (other.playerId() == snap.playerId()) continue;
            if (other.position() != Position.DEF) continue;
            double dist = snap.distanceTo(other);
            if (dist < 15.0) nearbyDefenders++;
        }

        return Math.min(1.0, nearbyDefenders / 3.0);
    }

    private boolean isInPassingLane(PlayerSnapshot from, PlayerSnapshot to, PlayerSnapshot blocker) {
        double dx = to.x() - from.x();
        double dy = to.y() - from.y();
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 0.1) return false;

        double nx = dx / len;
        double ny = dy / len;

        double bx = blocker.x() - from.x();
        double by = blocker.y() - from.y();

        double proj = bx * nx + by * ny;
        if (proj < 0 || proj > len) return false;

        double perpX = bx - proj * nx;
        double perpY = by - proj * ny;
        double perpDist = Math.sqrt(perpX * perpX + perpY * perpY);

        return perpDist < 3.0;
    }

    private boolean isInShootingLane(PlayerSnapshot shooter, double goalX, double goalY, PlayerSnapshot blocker) {
        double dx = goalX - shooter.x();
        double dy = goalY - shooter.y();
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 0.1) return false;

        double nx = dx / len;
        double ny = dy / len;

        double bx = blocker.x() - shooter.x();
        double by = blocker.y() - shooter.y();

        double proj = bx * nx + by * ny;
        if (proj < 0 || proj > len) return false;

        double perpX = bx - proj * nx;
        double perpY = by - proj * ny;
        double perpDist = Math.sqrt(perpX * perpX + perpY * perpY);

        return perpDist < 2.5;
    }

    public record SpaceData(
        double openSpace,
        double pressure,
        double passingLane,
        double shootingLane,
        double defensiveCover
    ) {
        public static final SpaceData EMPTY = new SpaceData(0.5, 0.5, 0.5, 0.5, 0.5);
    }
}
