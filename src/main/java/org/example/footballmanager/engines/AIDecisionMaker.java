package org.example.footballmanager.engines;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.dto.PlayerPositionDTO;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.MatchRuntime;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Position;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * Ball-carrier decision logic for the realistic engine.
 *
 * The goals here are narrow and practical:
 * - prefer short local passes instead of unrealistic cross-pitch recycling
 * - make forwards aggressive in the final third
 * - avoid backward passes from the shooting zone unless there is no credible action
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AIDecisionMaker {

    private static final double PASS_CLEAR_RADIUS = 4.5;
    private static final double PASS_PATH_TOLERANCE = 2.2;
    private static final double FINAL_THIRD_X_HOME = 66.0;
    private static final double FINAL_THIRD_X_AWAY = 34.0;

    private final Random random = new Random();

    public Decision makeDecision(Player player, MatchRuntime rt, Match match, int minute) {
        String team = getTeam(player, rt);
        List<Player> teamPlayers = getTeammates(player, rt);
        double goalDistance = estimateGoalDistance(player, rt, team);
        List<Player> nearbyDefenders = getNearbyDefenders(player, rt, team);
        double defensivePressure = Math.min(1.0, nearbyDefenders.size() / 4.0);

        // Bilo koji napadač može da šutira u težoj zoni šuta
        if (isAggressiveFinalThirdPlayer(player) && isHardShotZone(player, rt, team, goalDistance)) {
            return new Decision(ActionType.SHOT, null);
        }

        Player bestPassTarget = selectBestPassReceiver(player, teamPlayers, rt, team, goalDistance);

        double passScore = calculatePassScore(player, bestPassTarget, defensivePressure, rt, team);
        double shotScore = calculateShotScore(player, goalDistance, defensivePressure, rt, team);
        double dribbleScore = calculateDribbleScore(player, defensivePressure, rt, team);

        if (isDirectShotPriority(player, rt, team, goalDistance, defensivePressure)) {
            return new Decision(ActionType.SHOT, null);
        }

        if (isAggressiveFinalThirdPlayer(player) && isInShotZone(team, getPlayerX(player, rt))) {
            if (bestPassTarget != null && !isForwardPass(player, bestPassTarget, rt, team)) {
                passScore *= 0.05;
            }
            shotScore *= 1.85;  // Increased from 1.35 to encourage shots
            dribbleScore *= 0.85;  // Slightly reduced
        }

        double totalScore = passScore + shotScore + dribbleScore;
        if (totalScore <= 0.0) {
            return new Decision(ActionType.DRIBBLE, null);
        }

        passScore /= totalScore;
        shotScore /= totalScore;
        dribbleScore /= totalScore;

        log.debug("Decision for {}: PASS={:.2f}, SHOT={:.2f}, DRIBBLE={:.2f}",
                player.getName(), passScore, shotScore, dribbleScore);

        ActionType action;
        Player targetPlayer = null;

        if (shotScore >= passScore && shotScore >= dribbleScore && shotScore > 0.18 && goalDistance < 38.0) {
            action = ActionType.SHOT;
        } else if (passScore >= dribbleScore && bestPassTarget != null) {
            action = ActionType.PASS;
            targetPlayer = bestPassTarget;
        } else {
            action = ActionType.DRIBBLE;
        }

        if (action == ActionType.PASS && targetPlayer != null && shouldBlockBackwardPass(player, targetPlayer, rt, team, goalDistance)) {
            if (shotScore >= dribbleScore * 0.9 && goalDistance < 30.0) {
                return new Decision(ActionType.SHOT, null);
            }
            return new Decision(ActionType.DRIBBLE, null);
        }

        return new Decision(action, targetPlayer);
    }

    private double calculatePassScore(Player player, Player bestTarget, double defensivePressure, MatchRuntime rt, String team) {
        if (bestTarget == null) {
            return 0.0;
        }

        double baseScore = 0.85;
        if (defensivePressure < 0.3) {
            baseScore += 0.22;
        } else if (defensivePressure > 0.7) {
            baseScore += 0.15;
        }

        baseScore += player.getSkills().getPassing() / 26.0;
        baseScore += Math.max(0.0, calculatePassTargetScore(player, bestTarget, rt, team));
        return Math.max(0.0, baseScore);
    }

    private double calculateShotScore(Player player, double goalDistance, double defensivePressure,
                                      MatchRuntime rt, String team) {
        if (player.getPosition() == Position.GK) {
            return 0.0;
        }

        double baseScore;
        if (goalDistance < 8) {
            baseScore = 1.0;
        } else if (goalDistance < 16) {
            baseScore = 0.90;
        } else if (goalDistance < 22) {
            baseScore = 0.70;
        } else if (goalDistance < 28) {
            baseScore = 0.42;
        } else if (goalDistance < 34) {
            baseScore = 0.18;
        } else {
            return 0.0;
        }

        if (player.getPosition() == Position.ATT) {
            baseScore += 0.30;
        } else if (player.getPosition() == Position.WNG) {
            baseScore += 0.18;
        } else if (player.getPosition() == Position.MID) {
            baseScore += 0.12;
        } else if (player.getPosition() == Position.DEF) {
            baseScore -= 0.28;
        }

        baseScore += player.getSkills().getStriker() / 28.0;

        if (isInShotZone(team, getPlayerX(player, rt))) {
            baseScore *= 1.20;
        }
        if (defensivePressure < 0.2) {
            baseScore *= 1.30;
        } else if (defensivePressure > 0.7) {
            baseScore *= 0.76;
        }

        return Math.max(0.0, baseScore);
    }

    private double calculateDribbleScore(Player player, double defensivePressure, MatchRuntime rt, String team) {
        double baseScore = 0.0;

        if (defensivePressure > 0.45) {
            baseScore = defensivePressure * 0.92;
        }

        baseScore += player.getSkills().getTechnique() / 22.0;
        baseScore += player.getSkills().getPace() / 34.0;

        if (isAggressiveFinalThirdPlayer(player) && isInShotZone(team, getPlayerX(player, rt))) {
            baseScore *= 1.18;
        }

        return Math.max(0.0, baseScore);
    }

    private Player selectBestPassReceiver(Player passer, List<Player> teammates, MatchRuntime rt, String team, double goalDistance) {
        if (teammates.isEmpty()) {
            return null;
        }

        PlayerPositionDTO passerPos = getPlayerPosition(passer, rt);
        if (passerPos == null) {
            return null;
        }

        List<Player> nearest = teammates.stream()
                .map(player -> Map.entry(player, getPlayerPosition(player, rt)))
                .filter(entry -> entry.getValue() != null)
                .sorted(Comparator.comparingDouble(entry -> distance(passerPos, entry.getValue())))
                .limit(5)
                .map(Map.Entry::getKey)
                .toList();

        if (nearest.isEmpty()) {
            return null;
        }

        int minPool = Math.min(2, nearest.size());
        int maxPool = Math.min(5, nearest.size());
        int candidateCount = minPool;
        if (maxPool > minPool) {
            candidateCount += random.nextInt(maxPool - minPool + 1);
        }

        List<Player> shuffledNearest = new ArrayList<>(nearest);
        Collections.shuffle(shuffledNearest, random);
        List<Player> candidatePool = new ArrayList<>(shuffledNearest.subList(0, candidateCount));

        List<Player> filteredCandidates = candidatePool.stream()
                .filter(player -> !shouldBlockBackwardPass(passer, player, rt, team, goalDistance))
                .filter(player -> !isReceiverOnCooldown(player, rt, team))
                .filter(player -> calculatePassTargetScore(passer, player, rt, team) > -1.5)
                .toList();
        if (!filteredCandidates.isEmpty()) {
            return filteredCandidates.get(random.nextInt(filteredCandidates.size()));
        }

        List<Player> safeOutlets = nearest.stream()
                .filter(player -> !shouldBlockBackwardPass(passer, player, rt, team, goalDistance))
                .filter(player -> !isReceiverOnCooldown(player, rt, team))
                .filter(player -> calculateSafeOutletScore(passer, player, rt, team) > 0.15)
                .limit(3)
                .toList();
        if (!safeOutlets.isEmpty()) {
            return safeOutlets.get(random.nextInt(safeOutlets.size()));
        }

        return nearest.get(random.nextInt(nearest.size()));
    }

    private double calculatePassTargetScore(Player passer, Player receiver, MatchRuntime rt, String team) {
        PlayerPositionDTO passerPos = getPlayerPosition(passer, rt);
        PlayerPositionDTO receiverPos = getPlayerPosition(receiver, rt);
        if (passerPos == null || receiverPos == null) {
            return -10.0;
        }

        List<PlayerPositionDTO> opponents = getOpponentPositions(team, rt);
        double distance = distance(passerPos, receiverPos);
        double progress = team.equals("HOME") ? receiverPos.getX() - passerPos.getX() : passerPos.getX() - receiverPos.getX();
        boolean clearLane = isPassClear(passerPos, receiverPos, PASS_CLEAR_RADIUS, opponents);
        double nearestOpponent = nearestOpponentDistance(receiverPos, opponents);

        double score = 0.0;
        score += clearLane ? 0.95 : -0.90;
        score += Math.max(0.0, 8.0 - Math.abs(distance - 12.0)) * 0.07;
        score += nearestOpponent * 0.06;
        score += progress * 0.16;

        if (receiver.getPosition() == Position.ATT) {
            score += 0.72;
        } else if (receiver.getPosition() == Position.WNG) {
            score += 0.58;
        } else if (receiver.getPosition() == Position.MID) {
            score += 0.36;
        } else if (receiver.getPosition() == Position.DEF) {
            score -= 0.48;
        }

        if (isForwardPass(passer, receiver, rt, team)) {
            score += 0.42;
        } else {
            score -= 0.55;
        }

        if (isInShotZone(team, passerPos.getX())) {
            if (isBackwardAcrossHalf(passerPos, receiverPos, team)) {
                score -= 3.5;
            } else if (!isForwardPass(passer, receiver, rt, team)) {
                score -= 1.4;
            }
        }

        if (receiver.getPosition() == Position.MID && passer.getPosition() == Position.MID && nearCenter(receiverPos)) {
            score -= 0.60;
        }
        if (receiver.getPosition() == Position.MID
                && passer.getPosition() == Position.MID
                && nearCenter(receiverPos)
                && nearCenter(passerPos)
                && progress < 4.0) {
            return -10.0;
        }

        if (distance > 24.0) {
            score -= 0.45;
        }

        score += calculateRecentPassMemoryPenalty(passer, receiver, rt);

        return score;
    }

    private double calculateSafeOutletScore(Player passer, Player receiver, MatchRuntime rt, String team) {
        PlayerPositionDTO passerPos = getPlayerPosition(passer, rt);
        PlayerPositionDTO receiverPos = getPlayerPosition(receiver, rt);
        if (passerPos == null || receiverPos == null) {
            return -10.0;
        }

        double distance = distance(passerPos, receiverPos);
        double progress = team.equals("HOME") ? receiverPos.getX() - passerPos.getX() : passerPos.getX() - receiverPos.getX();
        double score = 0.0;
        score += Math.max(0.0, 16.0 - distance) * 0.08;
        score += progress * 0.08;
        if (!isBackwardAcrossHalf(passerPos, receiverPos, team)) {
            score += 0.20;
        }
        return score;
    }

    private boolean isDirectShotPriority(Player player, MatchRuntime rt, String team, double goalDistance, double defensivePressure) {
        if (!isAggressiveFinalThirdPlayer(player)) {
            return false;
        }
        double x = getPlayerX(player, rt);
        return isInShotZone(team, x) && goalDistance <= 28.0 && defensivePressure <= 0.9;
    }

    private boolean isHardShotZone(Player player, MatchRuntime rt, String team, double goalDistance) {
        double x = getPlayerX(player, rt);
        return isInShotZone(team, x) && goalDistance <= 25.0;
    }

    private boolean shouldBlockBackwardPass(Player passer, Player receiver, MatchRuntime rt, String team, double goalDistance) {
        PlayerPositionDTO passerPos = getPlayerPosition(passer, rt);
        PlayerPositionDTO receiverPos = getPlayerPosition(receiver, rt);
        if (passerPos == null || receiverPos == null) {
            return false;
        }
        // Bilo koji napadač u hard shot zone-u trebao bi da šutira
        if (isAggressiveFinalThirdPlayer(passer) && isHardShotZone(passer, rt, team, goalDistance)) {
            return true;
        }
        if (!isAggressiveFinalThirdPlayer(passer)) {
            return false;
        }
        if (goalDistance > 25.0 && !isInShotZone(team, passerPos.getX())) {
            return false;
        }
        if (isBackwardAcrossHalf(passerPos, receiverPos, team)) {
            return true;
        }
        return !isForwardPass(passer, receiver, rt, team) && distance(passerPos, receiverPos) > 8.0;
    }

    private boolean isReceiverOnCooldown(Player receiver, MatchRuntime rt, String team) {
        Integer lastReceiverId = "HOME".equals(team) ? rt.homeLastReceiverId : rt.awayLastReceiverId;
        return lastReceiverId != null && lastReceiverId == Math.toIntExact(receiver.getId());
    }

    private double calculateRecentPassMemoryPenalty(Player passer, Player receiver, MatchRuntime rt) {
        int passerId = Math.toIntExact(passer.getId());
        int receiverId = Math.toIntExact(receiver.getId());
        double penalty = 0.0;

        if (isSamePair(passerId, receiverId, rt.lastPassFromId, rt.lastPassToId)) {
            penalty -= 1.2;
        }
        if (isReversePair(passerId, receiverId, rt.lastPassFromId, rt.lastPassToId)) {
            penalty -= 2.6;
        }
        if (isSamePair(passerId, receiverId, rt.previousPassFromId, rt.previousPassToId)) {
            penalty -= 0.8;
        }
        if (isReversePair(passerId, receiverId, rt.previousPassFromId, rt.previousPassToId)) {
            penalty -= 1.7;
        }

        return penalty;
    }

    private boolean isSamePair(int passerId, int receiverId, Integer fromId, Integer toId) {
        return fromId != null && toId != null && fromId == passerId && toId == receiverId;
    }

    private boolean isReversePair(int passerId, int receiverId, Integer fromId, Integer toId) {
        return fromId != null && toId != null && fromId == receiverId && toId == passerId;
    }

    private boolean isBackwardAcrossHalf(PlayerPositionDTO passerPos, PlayerPositionDTO receiverPos, String team) {
        if ("HOME".equals(team)) {
            return passerPos.getX() > 50.0 && receiverPos.getX() < 50.0;
        }
        return passerPos.getX() < 50.0 && receiverPos.getX() > 50.0;
    }

    private boolean isForwardPass(Player passer, Player receiver, MatchRuntime rt, String team) {
        PlayerPositionDTO passerPos = getPlayerPosition(passer, rt);
        PlayerPositionDTO receiverPos = getPlayerPosition(receiver, rt);
        if (passerPos == null || receiverPos == null) {
            return false;
        }
        return "HOME".equals(team)
                ? receiverPos.getX() >= passerPos.getX() + 1.5
                : receiverPos.getX() <= passerPos.getX() - 1.5;
    }

    private boolean isInShotZone(String team, double x) {
        return "HOME".equals(team) ? x >= FINAL_THIRD_X_HOME : x <= FINAL_THIRD_X_AWAY;
    }

    private boolean isAggressiveFinalThirdPlayer(Player player) {
        return player.getPosition() == Position.ATT
                || player.getPosition() == Position.WNG
                || player.getPosition() == Position.MID;
    }

    private double estimateGoalDistance(Player player, MatchRuntime rt, String team) {
        PlayerPositionDTO position = getPlayerPosition(player, rt);
        if (position == null) {
            return 30.0;
        }

        double goalX = "HOME".equals(team) ? 100.0 : 0.0;
        double goalY = 50.0;
        double dx = goalX - position.getX();
        double dy = goalY - position.getY();
        return Math.sqrt(dx * dx + dy * dy);
    }

    private List<Player> getTeammates(Player player, MatchRuntime rt) {
        String team = getTeam(player, rt);
        List<Player> teammates = team.equals("HOME") ? rt.homePlayers : rt.awayPlayers;

        return teammates.stream()
                .filter(p -> !p.equals(player))
                .filter(p -> p.getPosition() != Position.GK)
                .toList();
    }

    private List<Player> getNearbyDefenders(Player player, MatchRuntime rt, String team) {
        String oppositeTeam = team.equals("HOME") ? "AWAY" : "HOME";
        List<Player> defenders = oppositeTeam.equals("HOME") ? rt.homePlayers : rt.awayPlayers;

        PlayerPositionDTO playerPos = getPlayerPosition(player, rt);
        if (playerPos == null) {
            return List.of();
        }

        return defenders.stream()
                .filter(defender -> defender.getPosition() != Position.GK)
                .map(defender -> Map.entry(defender, getPlayerPosition(defender, rt)))
                .filter(entry -> entry.getValue() != null)
                .filter(entry -> distance(playerPos, entry.getValue()) <= 12.0)
                .sorted(Comparator.comparingDouble(entry -> distance(playerPos, entry.getValue())))
                .map(Map.Entry::getKey)
                .limit(3)
                .toList();
    }

    private List<PlayerPositionDTO> getOpponentPositions(String team, MatchRuntime rt) {
        String oppositeTeam = team.equals("HOME") ? "AWAY" : "HOME";
        return rt.players.stream()
                .filter(pos -> Objects.equals(pos.getTeam(), oppositeTeam))
                .toList();
    }

    private boolean isPassClear(PlayerPositionDTO passer, PlayerPositionDTO receiver, double clearRadius, List<PlayerPositionDTO> opponents) {
        boolean receiverMarked = opponents.stream().anyMatch(opp -> distance(receiver, opp) < clearRadius);
        if (receiverMarked) {
            return false;
        }

        for (PlayerPositionDTO opponent : opponents) {
            if (isPointCloseToLineSegment(passer, receiver, opponent, PASS_PATH_TOLERANCE)) {
                return false;
            }
        }
        return true;
    }

    private boolean isPointCloseToLineSegment(PlayerPositionDTO a, PlayerPositionDTO b, PlayerPositionDTO point, double tolerance) {
        double abx = b.getX() - a.getX();
        double aby = b.getY() - a.getY();
        double apx = point.getX() - a.getX();
        double apy = point.getY() - a.getY();
        double abLenSq = abx * abx + aby * aby;
        if (abLenSq < 0.0001) {
            return distance(a, point) <= tolerance;
        }
        double projection = Math.max(0.0, Math.min(1.0, (apx * abx + apy * aby) / abLenSq));
        double closestX = a.getX() + projection * abx;
        double closestY = a.getY() + projection * aby;
        double dx = point.getX() - closestX;
        double dy = point.getY() - closestY;
        return Math.sqrt(dx * dx + dy * dy) <= tolerance;
    }

    private double nearestOpponentDistance(PlayerPositionDTO receiver, List<PlayerPositionDTO> opponents) {
        return opponents.stream()
                .mapToDouble(opp -> distance(receiver, opp))
                .min()
                .orElse(12.0);
    }

    private boolean nearCenter(PlayerPositionDTO pos) {
        return Math.abs(pos.getX() - 50.0) <= 10.0 && Math.abs(pos.getY() - 50.0) <= 18.0;
    }

    private double getPlayerX(Player player, MatchRuntime rt) {
        PlayerPositionDTO position = getPlayerPosition(player, rt);
        return position != null ? position.getX() : 50.0;
    }

    private PlayerPositionDTO getPlayerPosition(Player player, MatchRuntime rt) {
        return rt.players.stream()
                .filter(pos -> pos.getId() == player.getId())
                .findFirst()
                .orElse(null);
    }

    private double distance(PlayerPositionDTO a, PlayerPositionDTO b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        return Math.sqrt(dx * dx + dy * dy);
    }

    private String getTeam(Player player, MatchRuntime rt) {
        if (rt.homePlayers.contains(player)) {
            return "HOME";
        } else if (rt.awayPlayers.contains(player)) {
            return "AWAY";
        }
        return "UNKNOWN";
    }

    @Data
    public static class Decision {
        private final ActionType action;
        private final Player targetPlayer;
    }

    public enum ActionType {
        PASS,
        SHOT,
        DRIBBLE
    }

    @Data
    private static class PlayerScore {
        private final Player player;
        private final double score;
    }
}
