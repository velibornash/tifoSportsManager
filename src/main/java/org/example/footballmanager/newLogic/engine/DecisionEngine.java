package org.example.footballmanager.newLogic.engine;

import org.example.footballmanager.newLogic.model.MatchState;
import org.example.footballmanager.newLogic.model.PlayerSnapshot;
import org.example.footballmanager.newLogic.model.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public final class DecisionEngine {

    private static final Logger log = LoggerFactory.getLogger(DecisionEngine.class);
    private static final Random RNG = new Random();

    private static final double SHOT_DISTANCE = 28.0;
    private static final double CROSS_DISTANCE_FROM_WING = 20.0;
    private static final double SHORT_PASS_MAX = 15.0;
    private static final double LONG_PASS_MIN = 15.0;
    private static final double LONG_PASS_MAX = 35.0;

    public DecisionEngine() {}

    public BallAction decide(MatchState state, PlayerSnapshot carrier, AwarenessEngine awareness) {
        if (carrier == null) return BallAction.CARRY;

        Map<BallAction, Double> scores = new EnumMap<>(BallAction.class);

        scores.put(BallAction.CARRY, scoreCarry(carrier, state, awareness));
        scores.put(BallAction.DRIBBLE, scoreDribble(carrier, state, awareness));
        scores.put(BallAction.SHORT_PASS, scoreShortPass(carrier, state, awareness));
        scores.put(BallAction.LONG_PASS, scoreLongPass(carrier, state, awareness));
        scores.put(BallAction.CROSS, scoreCross(carrier, state, awareness));
        scores.put(BallAction.THROUGH_PASS, scoreThroughPass(carrier, state, awareness));
        scores.put(BallAction.SHOOT, scoreShoot(carrier, state, awareness));
        scores.put(BallAction.CLEAR, scoreClear(carrier, state, awareness));

        BallAction best = scores.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(BallAction.CARRY);

        return best;
    }

    public static double estimateGoalDistance(Object taker, MatchState state, String teamSide) {
        return 20.0;
    }

    private double scoreCarry(PlayerSnapshot carrier, MatchState state, AwarenessEngine awareness) {
        double score = 0.45; // Reduced base score
        AwarenessEngine.PlayerAwareness aw = awareness.get(carrier.playerId());

        if (aw.pressureLevel() < 0.3) score += 0.15;
        if (aw.pressureLevel() > 0.7) score -= 0.25;

        score += carrier.technique() * 0.008;
        score += carrier.pace() * 0.006;

        boolean inOpponentHalf = carrier.teamSide().equals("HOME") ? carrier.x() > 50 : carrier.x() < 50;
        if (inOpponentHalf) score += 0.05;

        // Reduce carry score significantly in final third to encourage shooting
        boolean inFinalThird = carrier.teamSide().equals("HOME") ? carrier.x() > 66 : carrier.x() < 34;
        if (inFinalThird) score -= 0.35;

        if (carrier.position() == Position.ATT || carrier.position() == Position.WNG) {
            if (inFinalThird && aw.pressureLevel() < 0.5) score += 0.10;
        }

        return Math.max(0.0, Math.min(1.0, score));
    }

    private double scoreDribble(PlayerSnapshot carrier, MatchState state, AwarenessEngine awareness) {
        double score = 0.37;
        AwarenessEngine.PlayerAwareness aw = awareness.get(carrier.playerId());

        if (aw.distToNearestOpponent() > 5.0) score += 0.25;
        if (aw.pressureLevel() > 0.5) score -= 0.20;

        score += carrier.technique() * 0.015;
        score += carrier.dribbling() * 0.012;

        if (carrier.position() == Position.WNG || carrier.position() == Position.ATT) {
            score += 0.10;
        }

        return Math.max(0.0, Math.min(1.0, score));
    }

    private double scoreShortPass(PlayerSnapshot carrier, MatchState state, AwarenessEngine awareness) {
        double score = 0.50; // Reduced base score
        AwarenessEngine.PlayerAwareness aw = awareness.get(carrier.playerId());

        if (aw.pressureLevel() > 0.6) score -= 0.20;

        score += carrier.playmaking() * 0.010;
        score += carrier.passing() * 0.008;

        long nearbyTeammates = state.playerSnapshots.stream()
            .filter(s -> s.teamSide().equals(carrier.teamSide()) && s.playerId() != carrier.playerId())
            .filter(s -> carrier.distanceTo(s) < SHORT_PASS_MAX)
            .count();

        if (nearbyTeammates >= 2) score += 0.08;
        if (nearbyTeammates == 0) score -= 0.35;

        double distToGoal = distanceToGoal(carrier);
        // Reduce short pass score significantly in final third to encourage shooting
        if (distToGoal < 15.0) score -= 0.45;
        else if (distToGoal < 20.0) score -= 0.30;
        else if (distToGoal < 28.0) score -= 0.15;

        return Math.max(0.0, Math.min(1.0, score));
    }

    private double scoreLongPass(PlayerSnapshot carrier, MatchState state, AwarenessEngine awareness) {
        double score = 0.62;

        score += carrier.passing() * 0.012;
        score += carrier.playmaking() * 0.010;

        long farTeammates = state.playerSnapshots.stream()
            .filter(s -> s.teamSide().equals(carrier.teamSide()) && s.playerId() != carrier.playerId())
            .filter(s -> {
                double d = carrier.distanceTo(s);
                return d >= LONG_PASS_MIN && d <= LONG_PASS_MAX;
            })
            .count();

        if (farTeammates >= 1) score += 0.20;
        if (farTeammates == 0) score -= 0.30;

        return Math.max(0.0, Math.min(1.0, score));
    }

    private double scoreCross(PlayerSnapshot carrier, MatchState state, AwarenessEngine awareness) {
        double score = 0.0;

        double distFromWing = Math.abs(carrier.y() - 50.0);
        if (distFromWing < CROSS_DISTANCE_FROM_WING) return 0.0;

        boolean inOpponentHalf = carrier.teamSide().equals("HOME") ? carrier.x() > 50 : carrier.x() < 50;
        if (!inOpponentHalf) return 0.0;

        score = 0.50;
        score += carrier.passing() * 0.010;
        score += carrier.technique() * 0.008;

        long attackersInBox = state.playerSnapshots.stream()
            .filter(s -> s.teamSide().equals(carrier.teamSide()) && s.playerId() != carrier.playerId())
            .filter(s -> isInBox(s, carrier.teamSide()))
            .count();

        if (attackersInBox >= 1) score += 0.25;

        return Math.max(0.0, Math.min(1.0, score));
    }

    private double scoreThroughPass(PlayerSnapshot carrier, MatchState state, AwarenessEngine awareness) {
        double score = 0.0;
        AwarenessEngine.PlayerAwareness aw = awareness.get(carrier.playerId());

        boolean inFinalThird = carrier.teamSide().equals("HOME") ? carrier.x() > 60 : carrier.x() < 40;
        if (!inFinalThird) return 0.0;

        score = 0.45;
        score += carrier.playmaking() * 0.015;
        score += carrier.vision() * 0.012;

        if (aw.pressureLevel() > 0.7) score -= 0.20;

        return Math.max(0.0, Math.min(1.0, score));
    }

    private double scoreShoot(PlayerSnapshot carrier, MatchState state, AwarenessEngine awareness) {
        double score = 0.0;
        AwarenessEngine.PlayerAwareness aw = awareness.get(carrier.playerId());

        double distToGoal = distanceToGoal(carrier);
        if (distToGoal > SHOT_DISTANCE) return 0.0;

        score = 0.75; // Increased base score significantly

        if (distToGoal < 8.0) score += 0.20;
        else if (distToGoal < 14.0) score += 0.15;
        else if (distToGoal < 20.0) score += 0.10;

        score += carrier.shooting() * 0.020;
        score += carrier.technique() * 0.012;

        if (carrier.position() == Position.ATT || carrier.position() == Position.WNG) {
            score += 0.10;
        }

        if (aw.pressureLevel() > 0.8) score -= 0.05;

        return Math.max(0.0, Math.min(1.0, score));
    }

    private double scoreClear(PlayerSnapshot carrier, MatchState state, AwarenessEngine awareness) {
        double score = 0.0;
        AwarenessEngine.PlayerAwareness aw = awareness.get(carrier.playerId());

        if (carrier.position() != Position.DEF) return 0.0;

        boolean inOwnThird = carrier.teamSide().equals("HOME") ? carrier.x() < 33 : carrier.x() > 67;
        if (!inOwnThird) return 0.0;

        if (aw.pressureLevel() < 0.5) return 0.0;

        score = 0.60;
        score += carrier.passing() * 0.008;

        return Math.max(0.0, Math.min(1.0, score));
    }

    private static double distanceToGoal(PlayerSnapshot snap) {
        double goalX = snap.teamSide().equals("HOME") ? 96.0 : 4.0;
        double goalY = 50.0;
        return snap.distanceToPoint(goalX, goalY);
    }

    private static boolean isInBox(PlayerSnapshot snap, String teamSide) {
        boolean inX = teamSide.equals("HOME") ? snap.x() > 78 : snap.x() < 22;
        boolean inY = snap.y() > 30 && snap.y() < 70;
        return inX && inY;
    }

    public enum BallAction {
        CARRY,
        DRIBBLE,
        SHORT_PASS,
        LONG_PASS,
        CROSS,
        THROUGH_PASS,
        SHOOT,
        CLEAR
    }
}
