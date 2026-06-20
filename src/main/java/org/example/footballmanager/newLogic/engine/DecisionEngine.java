package org.example.footballmanager.newLogic.engine;

import org.example.footballmanager.newLogic.model.*;

import java.util.*;
import java.util.stream.Collectors;

public final class DecisionEngine {

    private static final Random RNG = new Random();
    private static final double FINAL_THIRD_X_HOME = 66.0;
    private static final double FINAL_THIRD_X_AWAY = 34.0;
    private static final double SHOT_TRIGGER_DISTANCE = 24.5;

    public enum Action { PASS, SHOT, DRIBBLE }

    public record Decision(Action action, Player targetPlayer) {}

    public static Decision decide(Player carrier, MatchState state) {
        String team = state.teamSideOf(carrier.id());
        List<Player> teammates = getTeammates(carrier, state);
        double goalDist = estimateGoalDistance(carrier, state, team);
        List<Player> nearbyDefenders = getNearbyDefenders(carrier, state, team);
        double pressure = Math.min(1.0, nearbyDefenders.size() / 4.0);
        double pm = carrier.skills().playmaking() / 20.0;
        int possessionAge = state.possessionAgeTicks;
        boolean buildUp = isBuildUpPhase(team, getX(carrier, state), goalDist);
        boolean earlyPossession = possessionAge < 8;
        boolean settledPossession = possessionAge >= 4;

        // Direct shot priority — less likely for low playmaking (doesn't see the opportunity)
        if (state.possessionAgeTicks >= 8 && settledPossession && !buildUp && state.possessionPhase == MatchState.PossessionPhase.BOX_CHAOS
            && isDirectShotPriority(carrier, state, team, goalDist, pressure)
            && RNG.nextDouble() < 0.022 + pm * 0.06) {
            return new Decision(Action.SHOT, null);
        }

        Player bestTarget = selectBestPassTarget(carrier, teammates, state, team, goalDist, pm);

        double passScore = passScore(carrier, bestTarget, pressure, state, team, goalDist, pm);
        double shotScore = shotScore(carrier, goalDist, pressure, state, team);
        double dribbleScore = dribbleScore(carrier, pressure, state, team, pm);

        if (buildUp) {
            passScore *= 2.05;
            shotScore *= 0.0;
            dribbleScore *= earlyPossession ? 1.05 : 0.82;
        } else if (goalDist < 20.0) {
            shotScore *= earlyPossession ? 0.18 : 0.62;
        } else if (goalDist < 26.0) {
            shotScore *= 0.22;
        } else {
            shotScore = 0.0;
        }

        if (earlyPossession) {
            passScore *= 1.12;
            dribbleScore *= 1.08;
        } else if (state.possessionPhase == MatchState.PossessionPhase.PROGRESSION) {
            shotScore *= 0.38;
            passScore *= 1.05;
        } else if (state.possessionPhase == MatchState.PossessionPhase.FINAL_THIRD) {
            shotScore *= 0.72;
            passScore *= 0.88;
        } else if (state.possessionPhase == MatchState.PossessionPhase.BOX_CHAOS) {
            shotScore *= 1.15;
        }

        if (nearbyDefenders.isEmpty()) dribbleScore += 1.5;

        // Boost shot in final third
        boolean finalThird = isInShotZone(team, getX(carrier, state));
        if (finalThird) {
            if (bestTarget != null && !isForwardPass(carrier, bestTarget, state, team)) {
                passScore *= 0.08;
            }
            shotScore *= 0.72;
            dribbleScore *= 1.08;
        }

        if (pressure > 0.35) {
            passScore *= 1.08 + pressure * 0.18;
            dribbleScore *= 0.88;
        }

        double total = passScore + shotScore + dribbleScore;
        if (total <= 0) return new Decision(Action.DRIBBLE, null);

        double r = RNG.nextDouble() * total;
        if (r < passScore && bestTarget != null) return new Decision(Action.PASS, bestTarget);
        if (r < passScore + shotScore) return new Decision(Action.SHOT, null);
        return new Decision(Action.DRIBBLE, null);
    }

    private static double passScore(Player passer, Player target, double pressure, MatchState state, String team, double goalDist, double pm) {
        if (target == null) return 0;
        double score = 0.85 + (pressure < 0.3 ? 0.22 : pressure > 0.7 ? 0.15 : 0);
        score += passer.skills().passing() / 26.0;
        score += pm * 0.42;
        if (goalDist < 24.5 && isInShotZone(team, getX(passer, state))) score *= 0.86;
        return Math.max(0, score);
    }

    private static double shotScore(Player player, double goalDist, double pressure, MatchState state, String team) {
        if (player.position() == Position.GK) return 0;
        if (goalDist > 22) return 0;

        // Only clear chances - restrict to ~20 shots per match max
        double score = goalDist < 8 ? 0.12 : goalDist < 12 ? 0.08 : goalDist < 16 ? 0.05
            : goalDist < 19 ? 0.03 : goalDist < 23 ? 0.01 : 0;

        score += switch (player.position()) {
            case ATT -> 0.05;
            case WNG -> 0.02;
            case MID -> 0.005;
            case DEF -> -0.28;
            default -> 0;
        };
        score += player.skills().shooting() / 70.0;
        if (isInShotZone(team, getX(player, state))) score *= 1.02;
        if (pressure < 0.2) score *= 1.01;
        else if (pressure > 0.55) score *= 0.35;
        return Math.max(0, score);
    }

    private static double dribbleScore(Player player, double pressure, MatchState state, String team, double pm) {
        double base = (pressure > 0.45) ? pressure * 0.92 : 0;
        base += player.skills().technique() / 22.0 + player.skills().pace() / 34.0;
        base *= Math.max(0.45, 1.0 - pm * 0.45);
        if (pressure < 0.3) {
            base *= 0.72;
        }
        return base;
    }

    static Player selectBestPassTarget(Player passer, List<Player> teammates, MatchState state, String team, double goalDist, double pm) {
        if (teammates.isEmpty()) return null;
        var passerSnap = state.snapshotById(passer.id());
        if (passerSnap == null) return null;

        // Higher playmaking = more candidates considered (sees more options)
        int candidateLimit = Math.min(5 + (int)Math.round(pm * 5), teammates.size());
        if (candidateLimit < 1) return null;

        List<Player> candidates = teammates.stream()
            .sorted(Comparator.comparingDouble(p -> passerSnap.distanceToPoint(getX(p, state), getY(p, state))))
            .limit(candidateLimit)
            .toList();

        if (candidates.isEmpty()) return null;

        // Weighted random among top candidates — playmaking affects weight sharpness
        // Higher pm = sharper weights (best target strongly preferred)
        // Lower pm = flatter weights (more random, worse choice)
        double sharpness = 0.3 + pm * 1.7;
        double totalWeight = 0;
        double[] weights = new double[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            weights[i] = Math.max(0.1, passTargetWeight(passer, candidates.get(i), state, team, pm));
            weights[i] = Math.pow(weights[i], sharpness);
            totalWeight += weights[i];
        }
        double r = RNG.nextDouble() * totalWeight;
        for (int i = 0; i < candidates.size(); i++) {
            r -= weights[i];
            if (r <= 0) return candidates.get(i);
        }
        return candidates.getLast();
    }

    private static double passTargetWeight(Player passer, Player receiver, MatchState state, String team, double pm) {
        var passerSnap = state.snapshotById(passer.id());
        var receiverSnap = state.snapshotById(receiver.id());
        if (passerSnap == null || receiverSnap == null) return 0;

        double dist = passerSnap.distanceTo(receiverSnap);
        double progress = "HOME".equals(team) ? receiverSnap.x() - passerSnap.x() : passerSnap.x() - receiverSnap.x();
        double score = Math.max(0, 12.0 - Math.abs(dist - 12.0)) * 0.05;
        score += progress * 0.30;
        boolean buildUp = isBuildUpPhase(team, passerSnap.x(), estimateGoalDistance(passer, state, team));
        int possessionAge = state.possessionAgeTicks;
        boolean earlyPossession = possessionAge < 5;
        // Build-up should accept short backward/side passes; final third keeps forward bias.
        if (isForwardPass(passer, receiver, state, team)) {
            score += buildUp ? (earlyPossession ? 0.05 + pm * 0.14 : 0.18 + pm * 0.32) : 0.30 + pm * 0.55;
        } else if (buildUp) {
            score += earlyPossession ? 0.70 + (1.0 - pm) * 0.28 : 0.42 + (1.0 - pm) * 0.25;
        } else {
            score -= 0.55;
        }
        if (receiver.position() == Position.ATT || receiver.position() == Position.WNG) score += 0.60;
        if (passer.position() == Position.MID && receiver.position() == Position.MID && progress < 3.0) score -= 0.70;
        if (dist > 28.0) score -= 0.8;
        if (buildUp && dist <= 16.0) score += earlyPossession ? 0.55 : 0.40;

        // Offside risk: high playmaking = more willing to attempt through balls
        double offsideLine = calculateOffsideLine(state, team);
        if (isClearlyOffside(receiver, passer, state, team, offsideLine)) {
            double offsideRisk = 0.08 + pm * 0.35;
            double passingFactor = passer.skills().passing() / 20.0;
            offsideRisk += passingFactor * 0.10;
            // Apply a meaningful penalty (reduce target attractiveness), capped at 60%
            double offsidePenalty = Math.min(0.6, offsideRisk);
            score *= (1.0 - offsidePenalty);
        }
        return Math.max(0.1, score);
    }

    static boolean isForwardPass(Player passer, Player receiver, MatchState state, String team) {
        var snapP = state.snapshotById(passer.id());
        var snapR = state.snapshotById(receiver.id());
        if (snapP == null || snapR == null) return false;
        return "HOME".equals(team) ? snapR.x() >= snapP.x() + 1.5 : snapR.x() <= snapP.x() - 1.5;
    }

    private static boolean isBackwardPassBlocked(Player passer, Player receiver, MatchState state, String team, double goalDist) {
        if (passer.position() != Position.ATT && passer.position() != Position.WNG) return false;
        if (goalDist > 25.0) return false;
        var snapP = state.snapshotById(passer.id());
        var snapR = state.snapshotById(receiver.id());
        if (snapP == null || snapR == null) return false;
        boolean backward = "HOME".equals(team) ? snapR.x() < snapP.x() - 0.5 : snapR.x() > snapP.x() + 0.5;
        if (goalDist <= 15.5) return true; // hard shot zone - shoot don't pass
        return backward;
    }

    private static boolean isBuildUpPhase(String team, double x, double goalDist) {
        boolean ownHalf = "HOME".equals(team) ? x < 58.0 : x > 42.0;
        return ownHalf || goalDist > 34.0;
    }

    static boolean isDirectShotPriority(Player player, MatchState state, String team, double goalDist, double pressure) {
        if (player.position() != Position.ATT && player.position() != Position.WNG) return false;
        return isInShotZone(team, getX(player, state)) && goalDist <= 19.5 && pressure <= 0.42;
    }

    private static boolean isClearlyOffside(Player receiver, Player passer, MatchState state, String team, double offsideLine) {
        var snapR = state.snapshotById(receiver.id());
        if (snapR == null) return false;
        boolean homeAttack = "HOME".equals(team);
        boolean inOppHalf = homeAttack ? snapR.x() > 50.0 : snapR.x() < 50.0;
        if (!inOppHalf) return false;
        boolean beyondLine = homeAttack ? snapR.x() > offsideLine + 0.5 : snapR.x() < offsideLine - 0.5;
        return beyondLine;
    }

    public static double calculateOffsideLine(MatchState state, String team) {
        String defTeam = state.oppositeTeam(team);
        var defSnaps = "HOME".equals(defTeam) ? state.homeSnapshots() : state.awaySnapshots();
        var xs = defSnaps.stream().filter(s -> s.position() != Position.GK).mapToDouble(PlayerSnapshot::x).sorted().toArray();
        if (xs.length == 0) return "HOME".equals(team) ? 95.0 : 5.0;
        return "HOME".equals(team) ? xs[xs.length - 1] : xs[0];
    }

    public static boolean isInShotZone(String team, double x) {
        return "HOME".equals(team) ? x >= FINAL_THIRD_X_HOME : x <= FINAL_THIRD_X_AWAY;
    }

    public static double estimateGoalDistance(Player player, MatchState state, String team) {
        var snap = state.snapshotById(player.id());
        if (snap == null) return 30;
        double goalX = "HOME".equals(team) ? 100.0 : 0.0;
        return Math.sqrt(Math.pow(goalX - snap.x(), 2) + Math.pow(50.0 - snap.y(), 2));
    }

    static List<Player> getTeammates(Player player, MatchState state) {
        String team = state.teamSideOf(player.id());
        return ("HOME".equals(team) ? state.homePlayers() : state.awayPlayers()).stream()
            .filter(p -> p.id() != player.id() && p.position() != Position.GK)
            .collect(Collectors.toList());
    }

    static List<Player> getNearbyDefenders(Player player, MatchState state, String team) {
        String defTeam = state.oppositeTeam(team);
        var defPlayers = "HOME".equals(defTeam) ? state.homePlayers() : state.awayPlayers();
        var snap = state.snapshotById(player.id());
        if (snap == null) return List.of();

        return defPlayers.stream()
            .filter(p -> p.position() != Position.GK)
            .filter(p -> {
                var ps = state.snapshotById(p.id());
                return ps != null && snap.distanceTo(ps) <= 12.0;
            })
            .sorted(Comparator.comparingDouble(p -> {
                var ps = state.snapshotById(p.id());
                return ps != null ? snap.distanceTo(ps) : Double.MAX_VALUE;
            }))
            .limit(3)
            .toList();
    }

    private static double getX(Player player, MatchState state) {
        var snap = state.snapshotById(player.id());
        return snap != null ? snap.x() : 50.0;
    }

    private static double getY(Player player, MatchState state) {
        var snap = state.snapshotById(player.id());
        return snap != null ? snap.y() : 50.0;
    }
}
