package org.example.footballmanager.newLogic.engine;

import org.example.footballmanager.newLogic.model.*;

import java.util.List;

public final class SpaceAnalyzer {

    private static final double PRESSURE_RADIUS = 8.0;
    private static final double LANE_RADIUS = 6.0;
    private static final double THREAT_RADIUS = 12.0;

    public SpaceInfo analyze(PlayerSnapshot player, MatchState state) {
        double distToBall = player.distanceToPoint(state.ball.x(), state.ball.y());
        boolean ballFree = state.carrierId == null && !state.ballInTransit;

        double pressure = computePressure(player, state);
        double openness = computeOpenness(player, state);
        double passLaneScore = computePassLaneScore(player, state);
        double shotLaneScore = computeShotLaneScore(player, state);
        boolean underThreat = pressure > 0.5;
        double nearestOppDist = computeNearestOpponentDist(player, state);
        double nearestTeamDist = computeNearestTeammateDist(player, state);

        return new SpaceInfo(pressure, openness, passLaneScore, shotLaneScore,
            underThreat, nearestOppDist, nearestTeamDist);
    }

    private double computePressure(PlayerSnapshot player, MatchState state) {
        int nearOpponents = 0;
        for (PlayerSnapshot opp : state.playerSnapshots) {
            if (opp.teamSide().equals(player.teamSide())) continue;
            if (player.distanceTo(opp) <= PRESSURE_RADIUS) nearOpponents++;
        }
        return Math.min(1.0, nearOpponents / 3.0);
    }

    private double computeOpenness(PlayerSnapshot player, MatchState state) {
        double minDist = Double.MAX_VALUE;
        for (PlayerSnapshot opp : state.playerSnapshots) {
            if (opp.teamSide().equals(player.teamSide())) continue;
            double d = player.distanceTo(opp);
            if (d < minDist) minDist = d;
        }
        if (minDist == Double.MAX_VALUE) return 1.0;
        // Continuous scale: fully open at ~10m, completely closed at 0m.
        return Math.max(0.0, Math.min(1.0, minDist / 10.0));
    }

    private double computePassLaneScore(PlayerSnapshot player, MatchState state) {
        double score = 0.0;
        for (PlayerSnapshot teammate : state.playerSnapshots) {
            if (!teammate.teamSide().equals(player.teamSide())) continue;
            if (teammate.playerId() == player.playerId()) continue;
            double dist = player.distanceTo(teammate);
            if (dist > 20 || dist < 2) continue;

            boolean laneClear = true;
            for (PlayerSnapshot opp : state.playerSnapshots) {
                if (opp.teamSide().equals(player.teamSide())) continue;
                if (isNearLine(player, teammate, opp, LANE_RADIUS)) {
                    laneClear = false;
                    break;
                }
            }
            if (laneClear) score += 1.0 / (1.0 + dist);
        }
        return Math.min(1.0, score);
    }

    private double computeShotLaneScore(PlayerSnapshot player, MatchState state) {
        double goalX = player.teamSide().equals("HOME") ? 96.0 : 4.0;
        double goalY = 50.0;

        for (PlayerSnapshot opp : state.playerSnapshots) {
            if (opp.teamSide().equals(player.teamSide())) continue;
            if (isNearLine(player,
                new PlayerSnapshot(0, "AI", player.teamSide(), player.position(), goalX, goalY, "NORMAL", false),
                opp, 4.0)) {
                return 0.2;
            }
        }
        return 0.9;
    }

    private boolean isNearLine(PlayerSnapshot a, PlayerSnapshot b, PlayerSnapshot p, double tolerance) {
        double dx = b.x() - a.x();
        double dy = b.y() - a.y();
        double lenSq = dx * dx + dy * dy;
        if (lenSq == 0) return a.distanceTo(p) <= tolerance;
        double t = Math.max(0, Math.min(1,
            ((p.x() - a.x()) * dx + (p.y() - a.y()) * dy) / lenSq));
        double projX = a.x() + t * dx;
        double projY = a.y() + t * dy;
        return Math.hypot(p.x() - projX, p.y() - projY) <= tolerance;
    }

    private double computeNearestOpponentDist(PlayerSnapshot player, MatchState state) {
        double minDist = Double.MAX_VALUE;
        for (PlayerSnapshot opp : state.playerSnapshots) {
            if (opp.teamSide().equals(player.teamSide())) continue;
            double d = player.distanceTo(opp);
            if (d < minDist) minDist = d;
        }
        return minDist;
    }

    private double computeNearestTeammateDist(PlayerSnapshot player, MatchState state) {
        double minDist = Double.MAX_VALUE;
        for (PlayerSnapshot tm : state.playerSnapshots) {
            if (!tm.teamSide().equals(player.teamSide())) continue;
            if (tm.playerId() == player.playerId()) continue;
            double d = player.distanceTo(tm);
            if (d < minDist) minDist = d;
        }
        return minDist;
    }
}
