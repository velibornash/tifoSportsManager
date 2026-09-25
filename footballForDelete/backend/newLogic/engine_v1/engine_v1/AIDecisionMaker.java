package org.example.footballmanager.newLogic.engine_v1;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.dto.PlayerPositionDTO;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchRuntime;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
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
        double offsideLine = calculateOffsideLine(rt, team);
        MatchRuntime.PossessionPhase possessionPhase = rt.possessionPhase != null
                ? rt.possessionPhase
                : MatchRuntime.PossessionPhase.TRANSITION;
        String slotKey = player.getId() != null ? rt.playerSlotKeys.get(Math.toIntExact(player.getId())) : null;

        // U opasnoj zoni, ako nema pritiska, šut je apsolutni prioritet
        if (isDirectShotPriority(player, rt, team, goalDistance, defensivePressure)
                && possessionPhase != MatchRuntime.PossessionPhase.BUILD_UP) {
            return new Decision(ActionType.SHOT, null);
        }

        // Bilo koji napadač može da šutira u težoj zoni šuta ako ima prostora
        if (isAggressiveFinalThirdPlayer(player)
                && possessionPhase.ordinal() >= MatchRuntime.PossessionPhase.FINAL_THIRD.ordinal()
                && isHardShotZone(player, rt, team, goalDistance)
                && defensivePressure < 0.6) {
            return new Decision(ActionType.SHOT, null);
        }

        Player bestPassTarget = selectBestPassReceiver(player, teamPlayers, rt, team, goalDistance, offsideLine, slotKey, possessionPhase);

        double passScore = calculatePassScore(player, bestPassTarget, defensivePressure, rt, team, possessionPhase, slotKey);
        double shotScore = calculateShotScore(player, goalDistance, defensivePressure, rt, team, possessionPhase, slotKey);
        double dribbleScore = calculateDribbleScore(player, defensivePressure, rt, team, possessionPhase, slotKey);

        // BONUS FOR DRIBBLE IF SPACE IS CLEAR
        if (nearbyDefenders.isEmpty()) {
            dribbleScore += 1.5; // Encourage carrying the ball forward if no one is marking
        }

        if (isAggressiveFinalThirdPlayer(player)
                && possessionPhase.ordinal() >= MatchRuntime.PossessionPhase.FINAL_THIRD.ordinal()
                && isInShotZone(team, getPlayerX(player, rt))) {
            // Drastično smanjujemo šansu za pas ako nije progresivan (napred)
            if (bestPassTarget != null && !isForwardPass(player, bestPassTarget, rt, team)) {
                passScore *= 0.02; // Smanjeno sa 0.05
            }
            shotScore *= 1.16;
            dribbleScore *= 0.90;
        }

        if (isAggressiveFinalThirdPlayer(player) && goalDistance <= 24.5 && isCentralGoalThreat(player, rt)) {
            shotScore *= goalDistance <= 18.0 ? 1.24 : 1.40;
            passScore *= goalDistance <= 20.0 ? 0.82 : 0.90;
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

        double shotDecisionThreshold = goalDistance <= 16.0 ? 0.20
                : goalDistance <= 20.0 ? 0.23
                : goalDistance <= 24.0 ? 0.27
                : 0.32;
        if (possessionPhase == MatchRuntime.PossessionPhase.FINAL_THIRD) {
            shotDecisionThreshold -= 0.02;
        } else if (possessionPhase == MatchRuntime.PossessionPhase.BOX_CHAOS) {
            shotDecisionThreshold -= 0.04;
        }
        if (shotScore >= passScore && shotScore >= dribbleScore && shotScore > shotDecisionThreshold && goalDistance < 26.5) {
            action = ActionType.SHOT;
        } else if (passScore >= dribbleScore && bestPassTarget != null) {
            action = ActionType.PASS;
            targetPlayer = bestPassTarget;
        } else {
            action = ActionType.DRIBBLE;
        }

        if (action == ActionType.PASS && targetPlayer != null && shouldBlockBackwardPass(player, targetPlayer, rt, team, goalDistance)) {
            // Ako je pas unazad blokiran, pokušaj šut ako je iole smislen, inače driblaj napred
            if (shotScore >= 0.18 && goalDistance < 18.5) {
                return new Decision(ActionType.SHOT, null);
            }
            return new Decision(ActionType.DRIBBLE, null);
        }

        return new Decision(action, targetPlayer);
    }

    private double calculatePassScore(Player player,
                                      Player bestTarget,
                                      double defensivePressure,
                                      MatchRuntime rt,
                                      String team,
                                      MatchRuntime.PossessionPhase possessionPhase,
                                      String slotKey) {
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
        baseScore += Math.max(0.0, calculatePassTargetScore(player, bestTarget, rt, team, slotKey, possessionPhase));
        baseScore *= switch (possessionPhase) {
            case BUILD_UP -> 1.28;
            case PROGRESSION -> 1.14;
            case FINAL_THIRD -> 0.94;
            case BOX_CHAOS -> 0.82;
            case TRANSITION -> 1.02;
        };
        baseScore *= resolvePassProfileFactor(slotKey, bestTarget, rt, team);
        return Math.max(0.0, baseScore);
    }

    private double calculateShotScore(Player player, double goalDistance, double defensivePressure,
                                      MatchRuntime rt, String team, MatchRuntime.PossessionPhase possessionPhase, String slotKey) {
        if (player.getPosition() == Position.GK) {
            return 0.0;
        }

        double baseScore;
        if (goalDistance < 8) {
            baseScore = 1.04;
        } else if (goalDistance < 15) {
            baseScore = 0.90;
        } else if (goalDistance < 19) {
            baseScore = 0.62;
        } else if (goalDistance < 23) {
            baseScore = 0.42;
        } else if (goalDistance < 27) {
            baseScore = 0.17;
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
            baseScore *= 1.06;
        }
        if (defensivePressure < 0.2) {
            baseScore *= 1.10;
        } else if (defensivePressure > 0.55) {
            baseScore *= 0.64;
        }
        if (goalDistance <= 24.5 && isCentralGoalThreat(player, rt)) {
            baseScore *= goalDistance <= 18.0 ? 1.10 : 1.22;
        }
        if (goalDistance <= 13.5) {
            baseScore *= 1.12;
        }

        baseScore *= switch (possessionPhase) {
            case BUILD_UP -> goalDistance <= 14.0 ? 0.72 : 0.10;
            case PROGRESSION -> goalDistance <= 16.5 ? 0.88 : 0.42;
            case FINAL_THIRD -> 1.08;
            case BOX_CHAOS -> 1.22;
            case TRANSITION -> goalDistance <= 18.0 ? 1.05 : 0.74;
        };
        baseScore *= resolveShotProfileFactor(slotKey, goalDistance);

        return Math.max(0.0, baseScore);
    }

    private double calculateDribbleScore(Player player,
                                         double defensivePressure,
                                         MatchRuntime rt,
                                         String team,
                                         MatchRuntime.PossessionPhase possessionPhase,
                                         String slotKey) {
        double baseScore = 0.0;

        if (defensivePressure > 0.45) {
            baseScore = defensivePressure * 0.92;
        }

        baseScore += player.getSkills().getTechnique() / 22.0;
        baseScore += player.getSkills().getPace() / 34.0;

        if (isAggressiveFinalThirdPlayer(player) && isInShotZone(team, getPlayerX(player, rt))) {
            baseScore *= 1.18;
        }

        baseScore *= switch (possessionPhase) {
            case BUILD_UP -> 0.92;
            case PROGRESSION -> 1.16;
            case FINAL_THIRD -> 1.10;
            case BOX_CHAOS -> 0.86;
            case TRANSITION -> 1.18;
        };
        baseScore *= resolveDribbleProfileFactor(slotKey);

        return Math.max(0.0, baseScore);
    }

    private Player selectBestPassReceiver(Player passer,
                                          List<Player> teammates,
                                          MatchRuntime rt,
                                          String team,
                                          double goalDistance,
                                          double offsideLine,
                                          String passerSlotKey,
                                          MatchRuntime.PossessionPhase possessionPhase) {
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
                .limit(possessionPhase == MatchRuntime.PossessionPhase.BUILD_UP ? 8 : 7)
                .map(Map.Entry::getKey)
                .toList();

        if (nearest.isEmpty()) {
            return null;
        }

        List<Player> filteredCandidates = nearest.stream()
                .filter(player -> !shouldBlockBackwardPass(passer, player, rt, team, goalDistance))
                .filter(player -> !isReceiverOnCooldown(player, rt, team))
                .filter(player -> !isClearlyOffside(player, passer, rt, team, offsideLine)) // BLOCK OFFSIDE PASSES
                .filter(player -> calculatePassTargetScore(passer, player, rt, team, passerSlotKey, possessionPhase) > -2.2)
                .toList();
        if (!filteredCandidates.isEmpty()) {
            List<Player> ranked = filteredCandidates.stream()
                    .sorted(Comparator.comparingDouble(player ->
                            -calculatePassTargetScore(passer, player, rt, team, passerSlotKey, possessionPhase)))
                    .limit(possessionPhase == MatchRuntime.PossessionPhase.BUILD_UP ? 5 : 4)
                    .toList();

            Player best = ranked.getFirst();
            if (ranked.size() == 1) {
                return best;
            }

            double topScore = calculatePassTargetScore(passer, best, rt, team, passerSlotKey, possessionPhase);
            for (int i = 1; i < ranked.size(); i++) {
                Player alt = ranked.get(i);
                double altScore = calculatePassTargetScore(passer, alt, rt, team, passerSlotKey, possessionPhase);
                double tolerance = possessionPhase == MatchRuntime.PossessionPhase.BUILD_UP ? 0.55 : 0.35;
                if (altScore + tolerance >= topScore && random.nextDouble() < 0.22) {
                    return alt;
                }
            }
            return best;
        }

        List<Player> safeOutlets = nearest.stream()
                .filter(player -> !shouldBlockBackwardPass(passer, player, rt, team, goalDistance))
                .filter(player -> !isReceiverOnCooldown(player, rt, team))
                .filter(player -> !isClearlyOffside(player, passer, rt, team, offsideLine))
                .filter(player -> calculateSafeOutletScore(passer, player, rt, team) > 0.15)
                .limit(3)
                .toList();
        if (!safeOutlets.isEmpty()) {
            return safeOutlets.get(random.nextInt(safeOutlets.size()));
        }

        return nearest.get(random.nextInt(nearest.size()));
    }

    private boolean isClearlyOffside(Player receiver, Player passer, MatchRuntime rt, String team, double offsideLine) {
        PlayerPositionDTO receiverPos = getPlayerPosition(receiver, rt);
        PlayerPositionDTO passerPos = getPlayerPosition(passer, rt);
        if (receiverPos == null || passerPos == null) return false;

        boolean homeAttack = "HOME".equals(team);
        boolean inOppositionHalf = homeAttack ? receiverPos.getX() > 50.0 : receiverPos.getX() < 50.0;
        if (!inOppositionHalf) return false;

        boolean aheadOfBall = homeAttack ? receiverPos.getX() > passerPos.getX() + 0.5 : receiverPos.getX() < passerPos.getX() - 0.5;
        boolean beyondLine = homeAttack ? receiverPos.getX() > offsideLine + 0.5 : receiverPos.getX() < offsideLine - 0.5;

        return aheadOfBall && beyondLine;
    }

    private double calculateOffsideLine(MatchRuntime rt, String team) {
        List<Player> defenders = "HOME".equals(team) ? rt.awayPlayers : rt.homePlayers;
        List<Double> xPositions = defenders.stream()
                .filter(p -> p.getPosition() != Position.GK)
                .map(p -> getPlayerX(p, rt))
                .sorted()
                .toList();

        if (xPositions.isEmpty()) return "HOME".equals(team) ? 95.0 : 5.0;

        if ("HOME".equals(team)) {
            // Last defender (highest X)
            return xPositions.get(xPositions.size() - 1);
        } else {
            // Last defender (lowest X)
            return xPositions.get(0);
        }
    }

    private double calculatePassTargetScore(Player passer,
                                            Player receiver,
                                            MatchRuntime rt,
                                            String team,
                                            String passerSlotKey,
                                            MatchRuntime.PossessionPhase possessionPhase) {
        PlayerPositionDTO passerPos = getPlayerPosition(passer, rt);
        PlayerPositionDTO receiverPos = getPlayerPosition(receiver, rt);
        if (passerPos == null || receiverPos == null) {
            return -10.0;
        }
        String receiverSlotKey = receiver.getId() != null ? rt.playerSlotKeys.get(Math.toIntExact(receiver.getId())) : null;

        List<PlayerPositionDTO> opponents = getOpponentPositions(team, rt);
        double distance = distance(passerPos, receiverPos);
        double progress = team.equals("HOME") ? receiverPos.getX() - passerPos.getX() : passerPos.getX() - receiverPos.getX();
        boolean clearLane = isPassClear(passerPos, receiverPos, PASS_CLEAR_RADIUS, opponents);
        double nearestOpponent = nearestOpponentDistance(receiverPos, opponents);

        double score = 0.0;
        score += clearLane ? 1.5 : -3.0; // Even heavier penalty for blocked lanes
        score += Math.max(0.0, 8.0 - Math.abs(distance - 12.0)) * 0.05;
        score += nearestOpponent * 0.12; // Favor passing to players with space
        score += progress * switch (possessionPhase) {
            case BUILD_UP -> 0.20;
            case PROGRESSION -> 0.34;
            case FINAL_THIRD, BOX_CHAOS -> 0.45;
            case TRANSITION -> 0.38;
        };

        if (receiver.getPosition() == Position.ATT) {
            score += possessionPhase.ordinal() >= MatchRuntime.PossessionPhase.FINAL_THIRD.ordinal() ? 1.2 : 0.55;
        } else if (receiver.getPosition() == Position.WNG) {
            score += 0.95;
        } else if (receiver.getPosition() == Position.MID) {
            score += possessionPhase == MatchRuntime.PossessionPhase.BUILD_UP ? 0.78 : 0.40;
        } else if (receiver.getPosition() == Position.DEF) {
            score += possessionPhase == MatchRuntime.PossessionPhase.BUILD_UP ? 0.34 : -1.5;
        }

        if (isForwardPass(passer, receiver, rt, team)) {
            score += switch (possessionPhase) {
                case BUILD_UP -> 0.40;
                case PROGRESSION -> 0.76;
                case FINAL_THIRD, BOX_CHAOS -> 1.0;
                case TRANSITION -> 0.90;
            };
        } else {
            score -= switch (possessionPhase) {
                case BUILD_UP -> 0.70;
                case PROGRESSION -> 1.30;
                case FINAL_THIRD, BOX_CHAOS -> 2.50;
                case TRANSITION -> 1.40;
            };
        }

        if (isInShotZone(team, passerPos.getX())) {
            if (isBackwardAcrossHalf(passerPos, receiverPos, team)) {
                score -= 5.0; // Block backward across half completely
            } else if (!isForwardPass(passer, receiver, rt, team) && !isCutbackPass(team, passerPos, receiverPos)) {
                score -= 2.5; // Very strong penalty for non-forward pass in shot zone
            }
        }

        // Avoid infinite loops between midfielders
        if (receiver.getPosition() == Position.MID && passer.getPosition() == Position.MID && progress < 3.0
                && possessionPhase.ordinal() >= MatchRuntime.PossessionPhase.PROGRESSION.ordinal()) {
            score -= 1.5;
        }
        
        if (distance > 28.0) {
            score -= possessionPhase == MatchRuntime.PossessionPhase.BUILD_UP ? 1.25 : 0.8;
        }

        score += resolvePassLaneProfileBonus(passerSlotKey, receiverSlotKey, passerPos, receiverPos, team);
        score += resolveFinalThirdChanceCreationBonus(rt, passerSlotKey, receiverSlotKey, passerPos, receiverPos, team, nearestOpponent);
        score += calculateRecentPassMemoryPenalty(passer, receiver, rt);

        return score;
    }

    private double resolvePassProfileFactor(String slotKey, Player bestTarget, MatchRuntime rt, String team) {
        double factor = switch (slotFamily(slotKey)) {
            case FULLBACK, WIDEMID, WINGER -> 1.02;
            case HALFSPACE_MID, CENTRAL_MID, DM -> 1.10;
            case AM, CENTRAL_STRIKER -> 0.98;
            case SPLIT_STRIKER -> 0.94;
            case CENTER_BACK -> 1.06;
            default -> 1.0;
        };
        return Math.max(0.82, Math.min(1.18, factor));
    }

    private double resolveShotProfileFactor(String slotKey, double goalDistance) {
        return switch (slotFamily(slotKey)) {
            case CENTRAL_STRIKER -> goalDistance <= 18.0 ? 1.22 : 1.12;
            case SPLIT_STRIKER -> goalDistance <= 16.0 ? 1.16 : 1.06;
            case AM -> 1.08;
            case WINGER -> goalDistance <= 14.0 ? 0.96 : 0.82;
            case FULLBACK, CENTER_BACK, DM -> 0.72;
            default -> 1.0;
        };
    }

    private double resolveDribbleProfileFactor(String slotKey) {
        return switch (slotFamily(slotKey)) {
            case WINGER -> 1.22;
            case AM, SPLIT_STRIKER -> 1.14;
            case FULLBACK, WIDEMID -> 1.06;
            case CENTRAL_STRIKER -> 1.02;
            case CENTER_BACK, DM -> 0.84;
            default -> 1.0;
        };
    }

    /**
     * Debug decision used by the AI Decision Debugger mode.
     * Deterministic, explainable scoring that maps to PASS/SHOT/DRIBBLE.
     */
    public Decision makeDebugDecision(Player player, MatchRuntime rt, Match match, int minute) {
        String team = getTeam(player, rt);
        PlayerPositionDTO pos = getPlayerPosition(player, rt);
        if (pos == null) return new Decision(ActionType.DRIBBLE, null);

        java.util.function.BiFunction<PlayerPositionDTO, PlayerPositionDTO, Double> dist = (a, b) -> {
            double dx = a.getX() - b.getX(); double dy = a.getY() - b.getY(); return Math.sqrt(dx*dx + dy*dy);
        };

        // teammates and opponents
        var teammates = getTeammates(player, rt);
        var opponents = getOpponentPositions(team, rt);

        // SHOT score
        double goalX = "HOME".equals(team) ? 100.0 : 0.0;
        double dx = goalX - pos.getX();
        double dy = 50.0 - pos.getY();
        double goalDist = Math.sqrt(dx*dx + dy*dy);
        int nearbyDefenders = getNearbyDefenders(player, rt, team).size();
        double shotScore = 0.0;
        if (player.getPosition() != Position.GK && goalDist <= 28.0) {
            shotScore = Math.max(0.0, 2.0 - (goalDist / 20.0)) - (nearbyDefenders * 0.35);
        }

        // PASS_SHORT
        Player bestShort = null; double bestShortScore = -1.0;
        for (var p : teammates) {
            var pPos = getPlayerPosition(p, rt);
            if (pPos == null) continue;
            double d = dist.apply(pos, pPos);
            if (d <= 15.0 && d > 0.1) {
                double nearestOpp = opponents.stream().mapToDouble(o -> dist.apply(pPos, o)).min().orElse(12.0);
                double score = (15.0 - d) * 0.06 + (nearestOpp * 0.03);
                if (score > bestShortScore) { bestShortScore = score; bestShort = p; }
            }
        }
        double passShortScore = Math.max(0.0, bestShortScore);

        // PASS_LONG
        Player bestLong = null; double bestLongScore = -1.0;
        for (var p : teammates) {
            var pPos = getPlayerPosition(p, rt);
            if (pPos == null) continue;
            double d = dist.apply(pos, pPos);
            if (d > 15.0 && d <= 35.0) {
                double forward = "HOME".equals(team) ? pPos.getX() - pos.getX() : pos.getX() - pPos.getX();
                double nearestOpp = opponents.stream().mapToDouble(o -> dist.apply(pPos, o)).min().orElse(12.0);
                double score = Math.max(0.0, forward / 20.0) + (nearestOpp * 0.02);
                if (score > bestLongScore) { bestLongScore = score; bestLong = p; }
            }
        }
        double passLongScore = Math.max(0.0, bestLongScore);

        // THROUGH
        Player bestThrough = null; double bestThroughScore = -1.0;
        for (var p : teammates) {
            var pPos = getPlayerPosition(p, rt);
            if (pPos == null) continue;
            double forward = "HOME".equals(team) ? pPos.getX() - pos.getX() : pos.getX() - pPos.getX();
            if (forward > 6.5) {
                double nearestOpp = opponents.stream().mapToDouble(o -> dist.apply(pPos, o)).min().orElse(12.0);
                double score = 1.2 + (nearestOpp * 0.04) + (forward / 20.0);
                if (score > bestThroughScore) { bestThroughScore = score; bestThrough = p; }
            }
        }
        double throughScore = Math.max(0.0, bestThroughScore);

        double carryScore = (nearbyDefenders == 0) ? 0.6 : 0.05;
        double dribbleScore = 0.2 + (0.1 * Math.max(0, 3 - nearbyDefenders));

        // Select best
        double max = shotScore; String action = "SHOT"; Player chosen = null;
        if (passShortScore > max) { max = passShortScore; action = "PASS_SHORT"; chosen = bestShort; }
        if (passLongScore > max) { max = passLongScore; action = "PASS_LONG"; chosen = bestLong; }
        if (throughScore > max) { max = throughScore; action = "THROUGH_BALL"; chosen = bestThrough; }
        if (carryScore > max) { max = carryScore; action = "CARRY"; chosen = null; }
        if (dribbleScore > max) { max = dribbleScore; action = "DRIBBLE"; chosen = null; }

        if ("SHOT".equals(action)) return new Decision(ActionType.SHOT, null);
        if ("PASS_SHORT".equals(action) || "PASS_LONG".equals(action) || "THROUGH_BALL".equals(action)) return new Decision(ActionType.PASS, chosen);
        return new Decision(ActionType.DRIBBLE, null);
    }

    private double resolvePassLaneProfileBonus(String passerSlotKey,
                                               String receiverSlotKey,
                                               PlayerPositionDTO passerPos,
                                               PlayerPositionDTO receiverPos,
                                               String team) {
        if (receiverSlotKey == null || passerPos == null || receiverPos == null) {
            return 0.0;
        }
        SlotFamily passerFamily = slotFamily(passerSlotKey);
        SlotFamily receiverFamily = slotFamily(receiverSlotKey);
        boolean forwardPass = "HOME".equals(team)
                ? receiverPos.getX() > passerPos.getX()
                : receiverPos.getX() < passerPos.getX();

        if (passerFamily == SlotFamily.FULLBACK && (receiverFamily == SlotFamily.WINGER || receiverFamily == SlotFamily.WIDEMID) && forwardPass) {
            return 0.55;
        }
        if ((passerFamily == SlotFamily.DM || passerFamily == SlotFamily.CENTRAL_MID || passerFamily == SlotFamily.HALFSPACE_MID)
                && (receiverFamily == SlotFamily.AM || receiverFamily == SlotFamily.CENTRAL_STRIKER || receiverFamily == SlotFamily.SPLIT_STRIKER)
                && forwardPass) {
            return 0.48;
        }
        if (passerFamily == SlotFamily.AM && (receiverFamily == SlotFamily.CENTRAL_STRIKER || receiverFamily == SlotFamily.SPLIT_STRIKER) && forwardPass) {
            return 0.62;
        }
        if (passerFamily == SlotFamily.CENTER_BACK && (receiverFamily == SlotFamily.FULLBACK || receiverFamily == SlotFamily.DM || receiverFamily == SlotFamily.CENTRAL_MID)) {
            return 0.26;
        }
        return 0.0;
    }

    private SlotFamily slotFamily(String slotKey) {
        if (slotKey == null || slotKey.isBlank()) {
            return SlotFamily.GENERIC;
        }
        if ("DL".equals(slotKey) || "DR".equals(slotKey)) {
            return SlotFamily.FULLBACK;
        }
        if ("DCL".equals(slotKey) || "DCR".equals(slotKey) || "DC".equals(slotKey)) {
            return SlotFamily.CENTER_BACK;
        }
        if ("DM".equals(slotKey) || "DML".equals(slotKey) || "DMR".equals(slotKey)) {
            return SlotFamily.DM;
        }
        if ("CML".equals(slotKey) || "CMR".equals(slotKey)) {
            return SlotFamily.HALFSPACE_MID;
        }
        if ("CM".equals(slotKey)) {
            return SlotFamily.CENTRAL_MID;
        }
        if ("AML".equals(slotKey) || "AMR".equals(slotKey) || "AMC".equals(slotKey)) {
            return SlotFamily.AM;
        }
        if ("ML".equals(slotKey) || "MR".equals(slotKey)) {
            return SlotFamily.WIDEMID;
        }
        if ("WL".equals(slotKey) || "WR".equals(slotKey)) {
            return SlotFamily.WINGER;
        }
        if ("STL".equals(slotKey) || "STR".equals(slotKey)) {
            return SlotFamily.SPLIT_STRIKER;
        }
        if ("ST".equals(slotKey)) {
            return SlotFamily.CENTRAL_STRIKER;
        }
        return SlotFamily.GENERIC;
    }

    private enum SlotFamily {
        FULLBACK,
        CENTER_BACK,
        DM,
        HALFSPACE_MID,
        CENTRAL_MID,
        AM,
        WIDEMID,
        WINGER,
        CENTRAL_STRIKER,
        SPLIT_STRIKER,
        GENERIC
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
        PlayerPositionDTO pos = getPlayerPosition(player, rt);
        double x = pos != null ? pos.getX() : getPlayerX(player, rt);
        if (pos != null && isWideCutbackCarrier(team, pos) && rt.possessionPhase != MatchRuntime.PossessionPhase.BOX_CHAOS) {
            return false;
        }
        return isInShotZone(team, x) && goalDistance <= 19.5 && defensivePressure <= 0.42;
    }

    private boolean isHardShotZone(Player player, MatchRuntime rt, String team, double goalDistance) {
        double x = getPlayerX(player, rt);
        return isInShotZone(team, x) && goalDistance <= 15.5;
    }

    private boolean shouldBlockBackwardPass(Player passer, Player receiver, MatchRuntime rt, String team, double goalDistance) {
        PlayerPositionDTO passerPos = getPlayerPosition(passer, rt);
        PlayerPositionDTO receiverPos = getPlayerPosition(receiver, rt);
        if (passerPos == null || receiverPos == null) {
            return false;
        }
        if (isCutbackPass(team, passerPos, receiverPos)) {
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

    private double resolveFinalThirdChanceCreationBonus(MatchRuntime rt,
                                                        String passerSlotKey,
                                                        String receiverSlotKey,
                                                        PlayerPositionDTO passerPos,
                                                        PlayerPositionDTO receiverPos,
                                                        String team,
                                                        double nearestOpponent) {
        if (rt.possessionPhase.ordinal() < MatchRuntime.PossessionPhase.FINAL_THIRD.ordinal()) {
            return 0.0;
        }

        double bonus = 0.0;
        if (isWideCutbackCarrier(team, passerPos) && isCutbackPass(team, passerPos, receiverPos)) {
            bonus += 2.1;
            if (slotFamily(receiverSlotKey) == SlotFamily.CENTRAL_STRIKER
                    || slotFamily(receiverSlotKey) == SlotFamily.SPLIT_STRIKER
                    || slotFamily(receiverSlotKey) == SlotFamily.AM) {
                bonus += 0.9;
            }
            if (nearestOpponent >= 4.5) {
                bonus += 0.4;
            }
        }

        if ((slotFamily(passerSlotKey) == SlotFamily.WINGER || slotFamily(passerSlotKey) == SlotFamily.FULLBACK)
                && (slotFamily(receiverSlotKey) == SlotFamily.AM
                || slotFamily(receiverSlotKey) == SlotFamily.CENTRAL_STRIKER
                || slotFamily(receiverSlotKey) == SlotFamily.SPLIT_STRIKER)
                && isInsideBoxChannel(team, receiverPos)) {
            bonus += 0.7;
        }

        if (slotFamily(passerSlotKey) == SlotFamily.AM
                && (slotFamily(receiverSlotKey) == SlotFamily.CENTRAL_STRIKER || slotFamily(receiverSlotKey) == SlotFamily.SPLIT_STRIKER)
                && isInsideBoxChannel(team, receiverPos)) {
            bonus += 0.55;
        }

        return bonus;
    }

    private boolean isWideCutbackCarrier(String team, PlayerPositionDTO passerPos) {
        if (passerPos == null) {
            return false;
        }
        boolean deepFinalThird = "HOME".equals(team) ? passerPos.getX() >= 78.0 : passerPos.getX() <= 22.0;
        return deepFinalThird && Math.abs(passerPos.getY() - 50.0) >= 20.0;
    }

    private boolean isCutbackPass(String team, PlayerPositionDTO passerPos, PlayerPositionDTO receiverPos) {
        if (passerPos == null || receiverPos == null) {
            return false;
        }
        if (!isWideCutbackCarrier(team, passerPos)) {
            return false;
        }
        boolean backwardOrSquare = "HOME".equals(team)
                ? receiverPos.getX() <= passerPos.getX() - 1.0
                : receiverPos.getX() >= passerPos.getX() + 1.0;
        boolean centralReceiver = Math.abs(receiverPos.getY() - 50.0) <= 20.0;
        return backwardOrSquare && centralReceiver;
    }

    private boolean isInsideBoxChannel(String team, PlayerPositionDTO receiverPos) {
        if (receiverPos == null) {
            return false;
        }
        boolean advanced = "HOME".equals(team) ? receiverPos.getX() >= 80.0 : receiverPos.getX() <= 20.0;
        return advanced && Math.abs(receiverPos.getY() - 50.0) <= 20.0;
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
            penalty -= 2.5; // Strong penalty for repeating same pass
        }
        if (isReversePair(passerId, receiverId, rt.lastPassFromId, rt.lastPassToId)) {
            penalty -= 6.0; // MASSIVE penalty for returning the ball immediately (ping-pong)
        }
        if (isSamePair(passerId, receiverId, rt.previousPassFromId, rt.previousPassToId)) {
            penalty -= 1.5;
        }
        if (isReversePair(passerId, receiverId, rt.previousPassFromId, rt.previousPassToId)) {
            penalty -= 3.5;
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

    private boolean isCentralGoalThreat(Player player, MatchRuntime rt) {
        PlayerPositionDTO position = getPlayerPosition(player, rt);
        return position != null && Math.abs(position.getY() - 50.0) <= 18.0;
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
