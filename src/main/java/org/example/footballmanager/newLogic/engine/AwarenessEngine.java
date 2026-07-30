package org.example.footballmanager.newLogic.engine;

import org.example.footballmanager.newLogic.model.*;

import java.util.*;

public final class AwarenessEngine {

    private static final double THREAT_RADIUS = 12.0;
    private static final double PRESSURE_RADIUS = 8.0;
    private static final double MARKING_RADIUS = 15.0;

    private final Map<Long, PlayerAwareness> awarenessMap = new HashMap<>();

    public void update(MatchState state) {
        awarenessMap.clear();

        for (PlayerSnapshot snap : state.playerSnapshots) {
            PlayerAwareness awareness = computeAwareness(snap, state);
            awarenessMap.put(snap.playerId(), awareness);
        }
    }

    public PlayerAwareness get(long playerId) {
        return awarenessMap.getOrDefault(playerId, PlayerAwareness.EMPTY);
    }

    private PlayerAwareness computeAwareness(PlayerSnapshot snap, MatchState state) {
        double distToBall = snap.distanceToPoint(state.ball.x(), state.ball.y());
        boolean ballFree = state.carrierId == null && !state.ballInTransit;

        PlayerSnapshot nearestOpponent = findNearestOpponent(snap, state);
        double distToNearestOpponent = nearestOpponent != null
            ? snap.distanceTo(nearestOpponent)
            : Double.MAX_VALUE;

        int pressureCount = countOpponentsInRadius(snap, state, PRESSURE_RADIUS);
        double pressureLevel = Math.min(1.0, pressureCount / 3.0);

        PlayerSnapshot markingTarget = findMarkingTarget(snap, state);

        boolean isBallCarrier = state.carrierId != null && state.carrierId == snap.playerId();

        return new PlayerAwareness(
            distToBall,
            ballFree,
            nearestOpponent != null ? nearestOpponent.playerId() : null,
            distToNearestOpponent,
            pressureLevel,
            markingTarget != null ? markingTarget.playerId() : null,
            isBallCarrier
        );
    }

    private PlayerSnapshot findNearestOpponent(PlayerSnapshot snap, MatchState state) {
        PlayerSnapshot nearest = null;
        double minDist = Double.MAX_VALUE;

        for (PlayerSnapshot other : state.playerSnapshots) {
            if (other.teamSide().equals(snap.teamSide())) continue;
            double dist = snap.distanceTo(other);
            if (dist < minDist) {
                minDist = dist;
                nearest = other;
            }
        }
        return nearest;
    }

    private int countOpponentsInRadius(PlayerSnapshot snap, MatchState state, double radius) {
        int count = 0;
        for (PlayerSnapshot other : state.playerSnapshots) {
            if (other.teamSide().equals(snap.teamSide())) continue;
            double dist = snap.distanceTo(other);
            if (dist <= radius) count++;
        }
        return count;
    }

    private PlayerSnapshot findMarkingTarget(PlayerSnapshot snap, MatchState state) {
        if (snap.position() == Position.GK) return null;
        if (snap.position() == Position.ATT || snap.position() == Position.WNG) return null;

        PlayerSnapshot best = null;
        double bestScore = -1;

        for (PlayerSnapshot other : state.playerSnapshots) {
            if (other.teamSide().equals(snap.teamSide())) continue;
            if (other.position() == Position.GK) continue;

            double dist = snap.distanceTo(other);
            if (dist > MARKING_RADIUS) continue;

            double score = 1.0 / (1.0 + dist);
            if (other.position() == Position.ATT) score *= 2.0;
            if (other.position() == Position.WNG) score *= 1.5;

            if (score > bestScore) {
                bestScore = score;
                best = other;
            }
        }
        return best;
    }

    public record PlayerAwareness(
        double distToBall,
        boolean ballFree,
        Long nearestOpponentId,
        double distToNearestOpponent,
        double pressureLevel,
        Long markingTargetId,
        boolean isBallCarrier
    ) {
        public static final PlayerAwareness EMPTY = new PlayerAwareness(
            Double.MAX_VALUE, false, null, Double.MAX_VALUE, 0.0, null, false
        );
    }
}
