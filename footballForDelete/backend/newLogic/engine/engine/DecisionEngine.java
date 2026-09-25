package org.example.footballmanager.newLogic.engine;

import org.example.footballmanager.newLogic.model.MatchState;
import org.example.footballmanager.newLogic.model.PlayerSnapshot;
import org.example.footballmanager.newLogic.model.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public final class DecisionEngine {

    private static final Logger log = LoggerFactory.getLogger(DecisionEngine.class);

    private static final double SHOT_DISTANCE = 28.0;
    private static final double CROSS_DISTANCE_FROM_WING = 20.0;
    private static final double SHORT_PASS_MAX = 15.0;
    private static final double LONG_PASS_MIN = 15.0;
    private static final double LONG_PASS_MAX = 35.0;

    private final SpaceAnalyzer spaceAnalyzer = new SpaceAnalyzer();

    public DecisionEngine() {}

    /**
     * Deterministic action selection (PlayerDecisionDemo): every action gets a
     * situational score and the highest-scoring action always wins. There is no
     * random sampling when choosing the action — a better situational read and
     * better skills produce a better decision. Randomness is reserved for
     * execution (duels, shot outcome), never for selecting the action itself.
     */
    public BallAction decide(MatchState state, PlayerSnapshot carrier, AwarenessEngine awareness) {
        if (carrier == null) return BallAction.CARRY;

        // Live spatial analysis: pressure, openness, pass lanes and shot lanes.
        // Pure computation from the current state (no RNG), mirrors how the
        // reference demos score actions from the actual geometry on the pitch.
        SpaceInfo space = spaceAnalyzer.analyze(carrier, state);

        // Goalkeeper never carries/dribbles/shoots/crosses upfield — after a
        // save or loose-ball pickup the keeper distributes to a teammate or
        // clears. This keeps the keeper anchored to his goal line.
        if (carrier.position() == Position.GK) {
            Map<BallAction, Double> gkScores = new EnumMap<>(BallAction.class);
            gkScores.put(BallAction.SHORT_PASS, scoreShortPass(carrier, state, awareness, space));
            gkScores.put(BallAction.LONG_PASS, scoreLongPass(carrier, state, awareness, space));
            gkScores.put(BallAction.CLEAR, scoreClear(carrier, state, awareness, space));
            return gkScores.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(BallAction.SHORT_PASS);
        }

        Map<BallAction, Double> scores = new EnumMap<>(BallAction.class);

        scores.put(BallAction.CARRY, scoreCarry(carrier, state, awareness, space));
        scores.put(BallAction.DRIBBLE, scoreDribble(carrier, state, awareness, space));
        scores.put(BallAction.SHORT_PASS, scoreShortPass(carrier, state, awareness, space));
        scores.put(BallAction.LONG_PASS, scoreLongPass(carrier, state, awareness, space));
        scores.put(BallAction.CROSS, scoreCross(carrier, state, awareness, space));
        scores.put(BallAction.THROUGH_PASS, scoreThroughPass(carrier, state, awareness, space));
        scores.put(BallAction.SHOOT, scoreShoot(carrier, state, awareness, space));
        scores.put(BallAction.CLEAR, scoreClear(carrier, state, awareness, space));

        return scores.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(BallAction.CARRY);
    }

    public static double estimateGoalDistance(Object taker, MatchState state, String teamSide) {
        return 20.0;
    }

    private double scoreCarry(PlayerSnapshot carrier, MatchState state, AwarenessEngine awareness, SpaceInfo space) {
        double score = 0.25;
        AwarenessEngine.PlayerAwareness aw = awareness.get(carrier.playerId());
        int possessionTicks = carrier.getPossessionTicks();

        // Progressive penalty: decay after 15 ticks, encouraging distribution
        if (possessionTicks > 15) {
            score -= 0.015 * (possessionTicks - 15);
            if (aw.pressureLevel() > 0.6) {
                score += 0.04;
            }
        }

        double spaceVal = aw.distToNearestOpponent();
        if (spaceVal > 12.0) score += 0.20;
        else if (spaceVal > 8.0) score += 0.10;

        // Openness bonus
        score += space.getOpenness() * 0.05;

        // Positional bonuses
        if (carrier.position() == Position.ATT || carrier.position() == Position.WNG) {
            score += 0.08;
        }

        // Final third adjustment: carrying into the danger area
        boolean inFinalThird = carrier.teamSide().equals("HOME") ? carrier.x() > 66 : carrier.x() < 34;
        if (inFinalThird) {
            double distToGoal = distanceToGoal(carrier);
            if (distToGoal < 20.0) score += 0.10;
            else score -= 0.05;
        }

        score += carrier.technique() * 0.006;
        score += carrier.pace() * 0.008;

        return clamp(score);
    }

    private double scoreDribble(PlayerSnapshot carrier, MatchState state, AwarenessEngine awareness, SpaceInfo space) {
        double score = 0.15;
        AwarenessEngine.PlayerAwareness aw = awareness.get(carrier.playerId());
        int possessionTicks = carrier.getPossessionTicks();

        if (possessionTicks > 20) {
            score -= 0.02 * (possessionTicks - 20);
        }

        // Dribble is best when an opponent is within challenging distance (2-8m)
        double distToOpp = aw.distToNearestOpponent();
        if (distToOpp >= 2.0 && distToOpp <= 8.0) {
            score += 0.18;
        } else if (distToOpp > 10.0) {
            score += 0.05;
        }

        if (aw.pressureLevel() > 0.7) score -= 0.15;

        score += space.getOpenness() * 0.03;
        score += carrier.technique() * 0.012;
        score += carrier.dribbling() * 0.015;

        if (carrier.position() == Position.WNG || carrier.position() == Position.ATT) {
            score += 0.12;
        }

        boolean inFinalThird = carrier.teamSide().equals("HOME") ? carrier.x() > 66 : carrier.x() < 34;
        if (inFinalThird) {
            score += 0.10; // 1v1 dribble in attacking third is valuable
        }

        return clamp(score);
    }

    private double scoreShortPass(PlayerSnapshot carrier, MatchState state, AwarenessEngine awareness, SpaceInfo space) {
        double score = 0.55;
        AwarenessEngine.PlayerAwareness aw = awareness.get(carrier.playerId());

        if (aw.pressureLevel() > 0.8) score -= 0.30;
        else if (aw.pressureLevel() > 0.6) score -= 0.15;

        // Pass lane is the PRIMARY gate for short passing — a blocked lane
        // should make the pass unattractive even if the carrier has good skills.
        double passLane = space.getPassLaneScore();
        score += passLane * 0.35; // increased from 0.18
        if (passLane < 0.3) {
            score -= 0.30; // blocked lane penalty — can't pass through defenders
        }

        score += carrier.playmaking() * 0.012;
        score += carrier.passing() * 0.010;

        long nearbyTeammates = state.playerSnapshots.stream()
            .filter(s -> s.teamSide().equals(carrier.teamSide()) && s.playerId() != carrier.playerId())
            .filter(s -> carrier.distanceTo(s) < SHORT_PASS_MAX)
            .count();

        if (nearbyTeammates >= 2) score += 0.12;
        if (nearbyTeammates == 0) score -= 0.15;

        double distToGoal = distanceToGoal(carrier);
        double forwardProgression = 0.0;
        if (distToGoal < 15.0) {
            forwardProgression = 0.25;
        } else if (distToGoal < 20.0) {
            forwardProgression = 0.15;
        }
        score += forwardProgression;

        if (carrier.position() == Position.MID) score += 0.08;

        return clamp(score);
    }

    private double scoreLongPass(PlayerSnapshot carrier, MatchState state, AwarenessEngine awareness, SpaceInfo space) {
        double score = 0.16;
        AwarenessEngine.PlayerAwareness aw = awareness.get(carrier.playerId());

        score += carrier.passing() * 0.012;
        score += carrier.playmaking() * 0.010;

        // Space ahead means the switch / long ball can be weighted up.
        score += space.getOpenness() * 0.06;
        score += space.getPassLaneScore() * 0.06;

        boolean home = carrier.teamSide().equals("HOME");
        long forwardTeammates = state.playerSnapshots.stream()
            .filter(s -> s.teamSide().equals(carrier.teamSide()) && s.playerId() != carrier.playerId())
            .filter(s -> {
                double d = carrier.distanceTo(s);
                if (d < LONG_PASS_MIN || d > LONG_PASS_MAX) return false;
                // teammate must be meaningfully forward toward the opponent goal
                return home ? (s.x() - carrier.x()) > 5.0 : (carrier.x() - s.x()) > 5.0;
            })
            .count();

        if (forwardTeammates >= 1) score += 0.18;
        if (forwardTeammates == 0) score -= 0.12;

        if (aw.pressureLevel() > 0.7) score -= 0.10;

        if (carrier.position() == Position.MID) score += 0.05;

        return clamp(score);
    }

    private double scoreCross(PlayerSnapshot carrier, MatchState state, AwarenessEngine awareness, SpaceInfo space) {
        double distFromWing = Math.abs(carrier.y() - 50.0);
        if (distFromWing < CROSS_DISTANCE_FROM_WING) return 0.0;

        boolean inOpponentHalf = carrier.teamSide().equals("HOME") ? carrier.x() > 50 : carrier.x() < 50;
        if (!inOpponentHalf) return 0.0;

        double score = 0.40;
        score += carrier.passing() * 0.010;
        score += carrier.technique() * 0.008;

        long attackersInBox = state.playerSnapshots.stream()
            .filter(s -> s.teamSide().equals(carrier.teamSide()) && s.playerId() != carrier.playerId())
            .filter(s -> isInBox(s, carrier.teamSide()))
            .count();

        if (attackersInBox >= 1) score += 0.20;

        return clamp(score);
    }

    private double scoreThroughPass(PlayerSnapshot carrier, MatchState state, AwarenessEngine awareness, SpaceInfo space) {
        boolean inFinalThird = carrier.teamSide().equals("HOME") ? carrier.x() > 60 : carrier.x() < 40;
        if (!inFinalThird) return 0.0;

        // A through ball is only viable when a teammate is actually running in
        // behind the defense (inside the opponent's final third, ahead of the
        // carrier). Without a runner the pass has no target.
        if (!hasThroughRunner(carrier, state)) return 0.0;

        double score = 0.30;
        AwarenessEngine.PlayerAwareness aw = awareness.get(carrier.playerId());

        score += carrier.playmaking() * 0.015;
        score += carrier.vision() * 0.012;

        score += space.getOpenness() * 0.05;

        if (aw.pressureLevel() > 0.7) score -= 0.20;

        // Inside the box a through ball loses its value — shoot or pass instead
        if (distanceToGoal(carrier) < 15.0) score -= 0.25;

        if (carrier.position() == Position.MID || carrier.position() == Position.ATT) score += 0.05;

        return clamp(score);
    }

    private boolean hasThroughRunner(PlayerSnapshot carrier, MatchState state) {
        boolean home = carrier.teamSide().equals("HOME");
        for (PlayerSnapshot s : state.playerSnapshots) {
            if (!s.teamSide().equals(carrier.teamSide()) || s.playerId() == carrier.playerId()) continue;
            if (home ? (s.x() > 68 && s.x() > carrier.x()) : (s.x() < 32 && s.x() < carrier.x())) return true;
        }
        return false;
    }

    private double scoreShoot(PlayerSnapshot carrier, MatchState state, AwarenessEngine awareness, SpaceInfo space) {
        double distToGoal = distanceToGoal(carrier);
        if (distToGoal > SHOT_DISTANCE) return 0.0;

        double score = 0.50;
        AwarenessEngine.PlayerAwareness aw = awareness.get(carrier.playerId());

        // Shot lane is the PRIMARY gate for shooting — a blocked lane should
        // heavily discourage shooting even from close range.
        double shotLane = space.getShotLaneScore();
        score += shotLane * 0.35; // increased from 0.15
        if (shotLane < 0.4) {
            score -= 0.25; // blocked lane penalty
        }

        if (distToGoal < 8.0) score += 0.50;
        else if (distToGoal < 14.0) score += 0.35;
        else if (distToGoal < 20.0) score += 0.20;
        else if (distToGoal < 28.0) score += 0.10;

        if (carrier.position() == Position.ATT || carrier.position() == Position.WNG) {
            score += 0.10;
        }

        if (aw.pressureLevel() > 0.8) score -= 0.15;
        else if (aw.pressureLevel() > 0.6) score -= 0.05;

        return clamp(score);
    }

    private double scoreClear(PlayerSnapshot carrier, MatchState state, AwarenessEngine awareness, SpaceInfo space) {
        if (carrier.position() != Position.DEF) return 0.0;

        boolean inOwnThird = carrier.teamSide().equals("HOME") ? carrier.x() < 33 : carrier.x() > 67;
        if (!inOwnThird) return 0.0;

        AwarenessEngine.PlayerAwareness aw = awareness.get(carrier.playerId());
        if (aw.pressureLevel() < 0.5) return 0.0;

        double score = 0.55;
        score += carrier.passing() * 0.008;

        return clamp(score);
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
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
