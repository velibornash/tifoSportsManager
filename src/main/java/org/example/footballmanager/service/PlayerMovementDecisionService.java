package org.example.footballmanager.service;

import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.dto.BallPositionDTO;
import org.example.footballmanager.dto.PlayerPositionDTO;
import org.example.footballmanager.model.MatchRuntime;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.engines.DuelCalculator;
import org.example.footballmanager.util.match.MatchContext;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Service
@Slf4j
public class PlayerMovementDecisionService {

    /** Shot outcome returned to caller (MatchEngine) for event creation */
    public enum ShotOutcome { GOAL, SAVED, MISSED, PENDING }

    /** Last shot outcome, read by MatchEngine after updateBallPosition */
    public ShotOutcome lastShotOutcome = ShotOutcome.PENDING;

    double smoothFactor = 0.14; // 0.0 = no movement, 1.0 = teleport
    private static final double MIN_MOVEMENT_THRESHOLD = 0.5;
    private static final int MAX_RETREAT_TICKS = 8;
    private static final double DEEP_RETREAT_FORCE = 25.0;
    private static final double RETREAT_FORCE = 12.0;
    private static final double ATTACKER_PULL_WEIGHT = 0.05;
    private static final double DUEL_DISTANCE = 3.8;
    private static final double INTERCEPTION_DISTANCE = 1.9;
    private static final double LOOSE_BALL_PICKUP_DISTANCE = 6.0;
    private static final double PRESS_DISTANCE = 26.0;
    private final Random random = new Random();

    private List<PlayerPositionDTO> findNearbyPlayers(PlayerPositionDTO carrier, List<PlayerPositionDTO> players, int numberOfPlayers) {
        List<PlayerPositionDTO> candidates = players.stream()
                .filter(p -> p.getId() != carrier.getId() && p.getTeam().equals(carrier.getTeam()))
                .sorted(Comparator.comparingDouble(p -> distance(carrier, p)))
                .limit(numberOfPlayers)
                .toList();
        if (candidates.isEmpty()) return null;
        return candidates;
    }

    public PlayerPositionDTO findDangerousOpponent(List<PlayerPositionDTO> homePlayers, List<PlayerPositionDTO> awayPlayers, boolean attacksRight) {
        double x;
        List<PlayerPositionDTO> resultPlayersList;
        List<PlayerPositionDTO> otherPlayersList;
        if (attacksRight) {
            x = 0.00;
            resultPlayersList = awayPlayers;
            otherPlayersList = homePlayers;
        } else {
            x = 100.0;
            resultPlayersList = homePlayers;
            otherPlayersList = awayPlayers;
        }
        List<PlayerPositionDTO> finalOtherPlayersList = otherPlayersList;
        List<PlayerPositionDTO> candidates = resultPlayersList.stream()
                .filter(p -> {
                    List<PlayerPositionDTO> nearby = findNearbyPlayers(p, finalOtherPlayersList, 1);
                    return nearby != null && !nearby.isEmpty() && distance(p, nearby.getFirst()) > 5;
                })
                .sorted(Comparator.comparingDouble(p -> Math.abs(distance(p.getX(), x))))
                .toList();
        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    /**
     * Checks whether the passing lane to a teammate is clear:
     * 1. No opponent within clearRadius around the receiver
     * 2. No opponent on the line between passer and receiver (with tolerance)
     */
    public boolean isPassClear(PlayerPositionDTO passer, PlayerPositionDTO receiver, double clearRadius, List<PlayerPositionDTO> opponentPlayers) {
        final double PATH_TOLERANCE = 2.0;

        // 1. Check area around receiver
        boolean receiverMarked = opponentPlayers.stream()
                .anyMatch(opp -> distance(receiver, opp) <= clearRadius);
        if (receiverMarked) return false;

        // 2. Check passing lane
        for (PlayerPositionDTO opp : opponentPlayers) {
            if (isPointCloseToLineSegment(passer, receiver, opp, PATH_TOLERANCE)) {
                return false;
            }
        }
        return true;
    }

    private boolean isPointCloseToLineSegment(PlayerPositionDTO a, PlayerPositionDTO b, PlayerPositionDTO p, double maxDistance) {
        double lengthSquared = distanceSquared(a, b);
        if (lengthSquared == 0) return distance(a, p) <= maxDistance;
        double t = Math.max(0, Math.min(1,
                ((p.getX() - a.getX()) * (b.getX() - a.getX()) + (p.getY() - a.getY()) * (b.getY() - a.getY())) / lengthSquared));
        double projX = a.getX() + t * (b.getX() - a.getX());
        double projY = a.getY() + t * (b.getY() - a.getY());
        return distance(p, new PlayerPositionDTO(0, null, projX, projY, 0, 0)) <= maxDistance;
    }

    private double distanceSquared(PlayerPositionDTO a, PlayerPositionDTO b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        return dx * dx + dy * dy;
    }
    public void movePlayerByRole(PlayerPositionDTO p, List<PlayerPositionDTO> players, Random random, boolean attacksRight, MatchRuntime rt) {
        // Skip movement during stoppages
        if (rt.activeStoppage != null) {
            handleStoppagePositioning(p, players, random, attacksRight, rt);
            return;
        }

        double oldX = p.getX();
        double oldY = p.getY();

        // 1. If ball is free (no carrier) -> pull towards ball
        if (rt.currentCarrier == null) {
            pullTowardsBall(p, rt);
        } else if (isAttacker(p)) {
            // Gentle pull towards carrier for attackers
            pullTowardsCarrier(p, rt);
        }

        // 2. Offside tolerance - if activated, skip role movement and idle
        boolean offsidesActivated = applyOffsideTolerance(p, players, attacksRight, rt);

        if (!offsidesActivated) {
            // 3. Role-based movement
            applyRoleMovement(p, players, random, attacksRight, rt);
            applyContextReaction(p, players, rt);

            // 4. If barely moved, apply idle jitter
            double movementDelta = Math.abs(p.getX() - oldX) + Math.abs(p.getY() - oldY);
            if (movementDelta < MIN_MOVEMENT_THRESHOLD) {
                applyIdleMovement(p, random);
            }
        }

        // 5. Always avoid crowding at the end
        avoidCrowding(p, players);
    }

    /** Handle player positioning during stoppages (corners, free kicks, throw-ins) */
    private void handleStoppagePositioning(PlayerPositionDTO p, List<PlayerPositionDTO> players, Random random, boolean attacksRight, MatchRuntime rt) {
        switch (rt.activeStoppage) {
            case CORNER -> handleCornerPositioning(p, random, attacksRight, rt);
            case FREE_KICK -> handleFreeKickPositioning(p, random, attacksRight, rt);
            case THROW_IN -> handleThrowInPositioning(p, random, attacksRight, rt);
            case GOAL_CELEBRATION, VAR_REVIEW -> {} // Players stand still
            case PENALTY -> handlePenaltyPositioning(p, random, attacksRight, rt);
            default -> {}
        }
    }

    /** Corner: attackers into box, defenders mark */
    private void handleCornerPositioning(PlayerPositionDTO p, Random random, boolean attacksRight, MatchRuntime rt) {
        boolean isAttackingTeam = (attacksRight && rt.restartTeam.equals("HOME")) || (!attacksRight && rt.restartTeam.equals("AWAY"));
        double boxX = attacksRight ? 90 : 10;
        if (isAttackingTeam && isAttacker(p)) {
            // Attackers converge into the box
            double targetX = boxX + (random.nextDouble() - 0.5) * 10;
            double targetY = 50 + (random.nextDouble() - 0.5) * 30;
            p.setX(clamp(lerp(p.getX(), targetX, smoothFactor)));
            p.setY(clamp(lerp(p.getY(), targetY, smoothFactor)));
        } else if (!isAttackingTeam && (isCenterBack(p) || isFullBack(p))) {
            // Defenders mark inside box
            double targetX = boxX + (random.nextDouble() - 0.5) * 8;
            double targetY = 50 + (random.nextDouble() - 0.5) * 25;
            p.setX(clamp(lerp(p.getX(), targetX, smoothFactor)));
            p.setY(clamp(lerp(p.getY(), targetY, smoothFactor)));
        }
    }

    /** Free kick: position wall and taker */
    private void handleFreeKickPositioning(PlayerPositionDTO p, Random random, boolean attacksRight, MatchRuntime rt) {
        // Minimal movement during free kick setup
        applyIdleMovement(p, random);
    }

    /** Throw-in: thrower at sideline, others spread */
    private void handleThrowInPositioning(PlayerPositionDTO p, Random random, boolean attacksRight, MatchRuntime rt) {
        applyIdleMovement(p, random);
    }

    /** Penalty: everyone outside box except taker and GK */
    private void handlePenaltyPositioning(PlayerPositionDTO p, Random random, boolean attacksRight, MatchRuntime rt) {
        if (!isGoalkeeper(p)) {
            // Move everyone to edge of box
            double edgeX = attacksRight ? 78 : 22;
            p.setX(clamp(lerp(p.getX(), edgeX, smoothFactor * 0.5)));
        }
    }
    private double calculateOffsideLine(List<PlayerPositionDTO> players, boolean attacksRight) {
        String defendingTeam = attacksRight ? "AWAY" : "HOME";
        double offsideLine;
        if (attacksRight) {
            offsideLine = players.stream()
                    .filter(pp -> pp.getTeam().equals(defendingTeam) && !isGoalkeeper(pp))
                    .map(PlayerPositionDTO::getX)
                    .max(Double::compare)
                    .orElse(100.0);
        } else {
            offsideLine = players.stream()
                    .filter(pp -> pp.getTeam().equals(defendingTeam) && !isGoalkeeper(pp))
                    .map(PlayerPositionDTO::getX)
                    .min(Double::compare)
                    .orElse(0.0);
        }
        return offsideLine;
    }
    private void pullTowardsCarrier(PlayerPositionDTO p, MatchRuntime rt) {
        if (rt.currentCarrier == null) return;
        double dx = rt.currentCarrier.getX() - p.getX();
        double dy = rt.currentCarrier.getY() - p.getY();
        double distance = Math.hypot(dx, dy);
        if (distance > 40) return; // too far for effect
        double dynamicWeight = ATTACKER_PULL_WEIGHT * (1.0 + (40 - distance) / 40.0);
        dynamicWeight = Math.min(0.12, dynamicWeight);
        p.setX(p.getX() + dx * dynamicWeight);
        p.setY(p.getY() + dy * dynamicWeight);
    }

    /** Handle offside tolerance with deep retreat */
    private void handleOffsideTolerance(PlayerPositionDTO p, List<PlayerPositionDTO> players, boolean attacksRight, MatchRuntime rt) {
        // Offside only applies to attacking team
        if (!p.getTeam().equals(rt.currentCarrier.getTeam())) return;

        String defendingTeam = attacksRight ? "AWAY" : "HOME";
        Double offsideLine;
        if (attacksRight) {
            offsideLine = players.stream()
                    .filter(pp -> pp.getTeam().equals(defendingTeam) && !isGoalkeeper(pp))
                    .map(PlayerPositionDTO::getX).max(Double::compare).orElse(100.0);
        } else {
            offsideLine = players.stream()
                    .filter(pp -> pp.getTeam().equals(defendingTeam) && !isGoalkeeper(pp))
                    .map(PlayerPositionDTO::getX).min(Double::compare).orElse(0.0);
        }

        double targetX = p.getX();
        double tolerance = 1.0;
        boolean isInOffside = attacksRight ? (p.getX() > offsideLine + tolerance) : (p.getX() < offsideLine - tolerance);

        if (isInOffside) {
            if (p.getOffsideTicksRemaining() >= 2) {
                if (p.getRetreatTicksRemaining() < MAX_RETREAT_TICKS) {
                    p.setRetreatTicksRemaining(p.getRetreatTicksRemaining() + 1);
                }
                double progress = p.getRetreatTicksRemaining() / (double) MAX_RETREAT_TICKS;
                double effectiveForce = RETREAT_FORCE + DEEP_RETREAT_FORCE * progress;
                targetX = attacksRight ? (offsideLine - effectiveForce) : (offsideLine + effectiveForce);
            } else {
                targetX = attacksRight ? (offsideLine - 15) : (offsideLine + 15);
            }
            p.setOffsideTicksRemaining(p.getOffsideTicksRemaining() + 1);
        } else {
            p.setOffsideTicksRemaining(0);
            p.setRetreatTicksRemaining(0);
        }
        p.setX(clamp(lerp(p.getX(), targetX, smoothFactor)));
    }

    /** Check if attacker is at offside risk */
    private boolean checkOffsideRisk(PlayerPositionDTO attacker, List<PlayerPositionDTO> players, boolean attacksRight, MatchRuntime rt) {
        if (!attacker.getTeam().equals(rt.currentCarrier.getTeam())) return false;
        String defendingTeam = attacksRight ? "AWAY" : "HOME";
        Double offsideLine;
        if (attacksRight) {
            offsideLine = players.stream()
                    .filter(p -> p.getTeam().equals(defendingTeam) && !isGoalkeeper(p))
                    .map(PlayerPositionDTO::getX).max(Double::compare).orElse(100.0);
            return attacker.getX() > offsideLine;
        } else {
            offsideLine = players.stream()
                    .filter(p -> p.getTeam().equals(defendingTeam) && !isGoalkeeper(p))
                    .map(PlayerPositionDTO::getX).min(Double::compare).orElse(0.0);
            return attacker.getX() < offsideLine;
        }
    }

    /** Apply offside tolerance and return whether it was activated */
    private boolean applyOffsideTolerance(PlayerPositionDTO p, List<PlayerPositionDTO> players, boolean attacksRight, MatchRuntime rt) {
        if (rt.currentCarrier == null || !isAttacker(p)) {
            return false;
        }
        double oldTargetX = p.getX();
        handleOffsideTolerance(p, players, attacksRight, rt);
        return Math.abs(p.getX() - oldTargetX) > 0.01;
    }

    /** Apply role-based movement */
    private void applyRoleMovement(PlayerPositionDTO p, List<PlayerPositionDTO> players, Random random, boolean attacksRight, MatchRuntime rt) {
        int id = p.getId();
        if (id == 1 || id == 12) moveGoalkeeper(p, random, attacksRight);
        else if (id == 2 || id == 13) moveFullback(p, players, random, attacksRight, true, rt);
        else if (id == 3 || id == 16) moveFullback(p, players, random, attacksRight, false, rt);
        else if (id == 4 || id == 5 || id == 14 || id == 15) moveCenterBack(p, players, random, attacksRight, rt);
        else if (id == 6 || id == 8 || id == 17 || id == 18) moveCentralMidfielder(p, players, random, attacksRight, rt);
        else if (id == 7 || id == 11 || id == 19 || id == 20) moveWinger(p, players, random, attacksRight, rt);
        else if (id == 9 || id == 10 || id == 21 || id == 22) moveStriker(p, players, random, attacksRight, rt);
    }
    // -------- Position-specific movement methods --------
    private void moveGoalkeeper(PlayerPositionDTO gk, Random random, boolean attacksRight) {
        double homeGoalX = gk.getTeam().equals("HOME") ? 6 : 94;
        double targetX = homeGoalX + (random.nextDouble() - 0.5) * 3.0;
        double targetY = 48 + (random.nextDouble() - 0.5) * 7;
        gk.setX(clamp(lerp(gk.getX(), targetX, smoothFactor)));
        gk.setY(clamp(lerp(gk.getY(), targetY, smoothFactor)));
    }
    private void moveFullback(PlayerPositionDTO fb, List<PlayerPositionDTO> players, Random random, boolean attacksRight, boolean isRightBack, MatchRuntime rt) {
        boolean teamIsHome = "HOME".equals(fb.getTeam());
        boolean inPossession = rt.currentCarrier != null && fb.getTeam().equals(rt.currentCarrier.getTeam());
        boolean dangerNearOwnGoal = teamIsHome ? rt.ball.getX() < 30 : rt.ball.getX() > 70;

        double ownGoalX = teamIsHome ? 6 : 94;
        double defensiveLineX = teamIsHome ? 19 : 81;
        double supportLineX = teamIsHome ? 35 : 65;
        double baseX = inPossession ? supportLineX : defensiveLineX;

        double ballXWeight = inPossession ? 0.08 : 0.16;
        if (dangerNearOwnGoal) {
            ballXWeight = 0.26;
            baseX = teamIsHome ? ownGoalX + 13 : ownGoalX - 13;
        }

        double targetX = baseX + (rt.ball.getX() - baseX) * ballXWeight;
        if (!inPossession) {
            targetX = teamIsHome ? Math.min(targetX, 38) : Math.max(targetX, 62);
        }

        double flankY = isRightBack ? 24 : 76;
        double ballYWeight = inPossession ? 0.10 : 0.20;
        if (dangerNearOwnGoal) {
            ballYWeight = 0.28;
        }
        double targetY = flankY + (rt.ball.getY() - flankY) * ballYWeight;

        if (!inPossession) {
            PlayerPositionDTO nearestThreat = players.stream()
                    .filter(p -> !p.getTeam().equals(fb.getTeam()))
                    .min(Comparator.comparingDouble(p -> distance(fb, p)))
                    .orElse(null);
            if (nearestThreat != null && distance(fb, nearestThreat) < 22) {
                targetX += (nearestThreat.getX() - fb.getX()) * 0.12;
                targetY += (nearestThreat.getY() - fb.getY()) * 0.18;
            }
        }

        targetX += (random.nextDouble() - 0.5) * 1.4;
        targetY += (random.nextDouble() - 0.5) * 3.0;
        if (isRightBack) {
            targetY = Math.max(8, Math.min(25, targetY));
        } else {
            targetY = Math.max(75, Math.min(92, targetY));
        }

        fb.setX(clamp(lerp(fb.getX(), targetX, smoothFactor)));
        fb.setY(clamp(lerp(fb.getY(), targetY, smoothFactor)));
    }
    private void moveCenterBack(PlayerPositionDTO cb, List<PlayerPositionDTO> players, Random random, boolean attacksRight, MatchRuntime rt) {
        boolean teamIsHome = "HOME".equals(cb.getTeam());
        boolean inPossession = rt.currentCarrier != null && cb.getTeam().equals(rt.currentCarrier.getTeam());
        boolean dangerNearOwnGoal = teamIsHome ? rt.ball.getX() < 35 : rt.ball.getX() > 65;

        boolean rightCenterBack = cb.getId() == 4 || cb.getId() == 14;
        double laneY = rightCenterBack ? 44 : 56; // Keep CBs narrow/central.

        double defensiveX = teamIsHome ? 22 : 78;
        double supportX = teamIsHome ? 34 : 66;
        double baseX = inPossession ? supportX : defensiveX;
        if (dangerNearOwnGoal) {
            baseX = teamIsHome ? 15 : 85;
        }

        double xFollow = inPossession ? 0.08 : 0.15;
        double yFollow = inPossession ? 0.10 : 0.18;
        if (dangerNearOwnGoal) {
            xFollow = 0.24;
            yFollow = 0.30;
        }

        double targetX = baseX + (rt.ball.getX() - baseX) * xFollow;
        double targetY = laneY + (rt.ball.getY() - laneY) * yFollow;

        if (!inPossession) {
            PlayerPositionDTO assignedStriker = getAssignedStriker(cb, players);
            if (assignedStriker != null) {
                double dist = distance(cb, assignedStriker);
                if (dist < 20) {
                    targetX += (assignedStriker.getX() - cb.getX()) * 0.12;
                    targetY += (assignedStriker.getY() - cb.getY()) * 0.16;
                }
            }
        }

        targetX += (random.nextDouble() - 0.5) * 1.0;
        targetY += (random.nextDouble() - 0.5) * 2.2;

        cb.setX(clamp(lerp(cb.getX(), targetX, smoothFactor)));
        cb.setY(clamp(lerp(cb.getY(), targetY, smoothFactor)));
    }
    private void moveCentralMidfielder(PlayerPositionDTO cm, List<PlayerPositionDTO> players, Random random, boolean attacksRight, MatchRuntime rt) {
        boolean inPossession = rt.currentCarrier != null && cm.getTeam().equals(rt.currentCarrier.getTeam());
        double baseX = attacksRight ? (inPossession ? 50 : 42) : (inPossession ? 50 : 58);
        double laneY = (cm.getId() == 6 || cm.getId() == 17) ? 42 : 58;

        double ballPullX = (rt.ball.getX() - baseX) * (inPossession ? 0.22 : 0.30);
        double ballPullY = (rt.ball.getY() - laneY) * (inPossession ? 0.24 : 0.34);

        PlayerPositionDTO nearestOpp = players.stream()
                .filter(p -> !p.getTeam().equals(cm.getTeam()))
                .min(Comparator.comparingDouble(p -> distance(cm, p)))
                .orElse(null);
        if (!inPossession && nearestOpp != null && distance(cm, nearestOpp) < 20) {
            ballPullX += (nearestOpp.getX() - cm.getX()) * 0.14;
            ballPullY += (nearestOpp.getY() - cm.getY()) * 0.12;
        }

        double targetX = baseX + ballPullX + (random.nextDouble() - 0.5) * 2.1;
        double targetY = laneY + ballPullY + (random.nextDouble() - 0.5) * 3.2;
        cm.setX(clamp(lerp(cm.getX(), targetX, smoothFactor)));
        cm.setY(clamp(lerp(cm.getY(), targetY, smoothFactor)));
    }
    private void moveWinger(PlayerPositionDTO winger, List<PlayerPositionDTO> players, Random random, boolean attacksRight, MatchRuntime rt) {
        boolean inPossession = rt.currentCarrier != null && winger.getTeam().equals(rt.currentCarrier.getTeam());
        double baseY = (winger.getId() == 7 || winger.getId() == 19) ? 78 : 22;
        double baseX = attacksRight ? (inPossession ? 62 : 45) : (inPossession ? 38 : 55);
        double advance = inPossession ? (attacksRight ? 8.0 : -8.0) : (attacksRight ? 3.0 : -3.0);
        double targetX = baseX + (rt.ball.getX() - baseX) * (inPossession ? 0.20 : 0.15) + advance;

        if (rt.currentCarrier != null && winger.getTeam().equals(rt.currentCarrier.getTeam()) && checkOffsideRisk(winger, players, attacksRight, rt)) {
            targetX += attacksRight ? -8 : 8;
        }

        PlayerPositionDTO nearestOpponent = players.stream()
                .filter(p -> !p.getTeam().equals(winger.getTeam()))
                .min(Comparator.comparingDouble(p -> distance(winger, p)))
                .orElse(null);
        if (!inPossession && nearestOpponent != null && distance(winger, nearestOpponent) < 14) {
            targetX += (nearestOpponent.getX() - winger.getX()) * 0.18;
        }

        double targetY = baseY + (rt.ball.getY() - baseY) * (inPossession ? 0.16 : 0.10) + (random.nextDouble() - 0.5) * 2.4;
        if (winger.getId() == 11 || winger.getId() == 20) {
            targetY = Math.max(6, Math.min(28, targetY));
        } else {
            targetY = Math.max(72, Math.min(94, targetY));
        }
        winger.setX(clamp(lerp(winger.getX(), targetX, smoothFactor)));
        winger.setY(clamp(lerp(winger.getY(), targetY, smoothFactor)));
    }
    // Updated: striker movement with randomized step (+/-2)
    private void moveStriker(PlayerPositionDTO striker, List<PlayerPositionDTO> players, Random random, boolean attacksRight, MatchRuntime rt) {
        boolean inPossession = rt.currentCarrier != null && striker.getTeam().equals(rt.currentCarrier.getTeam());
        double bandY = (striker.getId() == 9 || striker.getId() == 21) ? 46 : 54;
        double baseX = attacksRight ? (inPossession ? 67 : 54) : (inPossession ? 33 : 46);
        double targetX = baseX + (rt.ball.getX() - baseX) * (inPossession ? 0.18 : 0.12);

        if (inPossession) {
            targetX += attacksRight ? 5.5 : -5.5;
        }
        if (rt.currentCarrier != null && rt.currentCarrier.getTeam().equals(striker.getTeam())) {
            double carrierX = rt.currentCarrier.getX();
            targetX = attacksRight ? Math.min(targetX, carrierX + 16) : Math.max(targetX, carrierX - 16);
        }
        if (rt.currentCarrier != null && striker.getId() != rt.currentCarrier.getId() && checkOffsideRisk(striker, players, attacksRight, rt)) {
            targetX += attacksRight ? -9 : 9;
        }

        double targetY = bandY + (rt.ball.getY() - bandY) * 0.13 + (random.nextDouble() - 0.5) * 3.0;
        striker.setX(clamp(lerp(striker.getX(), targetX, smoothFactor)));
        striker.setY(clamp(lerp(striker.getY(), targetY, smoothFactor)));
    }
    // -------- Helper methods --------
    private PlayerPositionDTO getAssignedStriker(PlayerPositionDTO cb, List<PlayerPositionDTO> players) {

        int id = cb.getId();

        int targetId = switch (id) {
            case 4 -> 21;
            case 5 -> 22;
            case 14 -> 9;
            case 15 -> 10;
            default -> -1;
        };

        return players.stream()
                .filter(p -> p.getId() == targetId)
                .findFirst()
                .orElse(null);
    }
    /** Defensive line X with random offside trap (+/-10 with 30% chance) */
    private double getDefensiveLineX(PlayerPositionDTO cb, boolean attacksRight, MatchRuntime rt) {
        double ballX = rt.ball.getX();
        double baseLine = attacksRight ? 18 : 82;
        Random rand = new Random();

        // Ball far away -> push up to 35m, chance of offside trap
        if (attacksRight && ballX > 55) {
            double line = 35;
            if (rand.nextDouble() < 0.3) line += (rand.nextDouble() - 0.5) * 20;
            return line;
        }
        if (!attacksRight && ballX < 45) {
            double line = 65;
            if (rand.nextDouble() < 0.3) line -= (rand.nextDouble() - 0.5) * 20;
            return line;
        }
        // Ball close -> retreat
        if (attacksRight && ballX < 30) return 14;
        if (!attacksRight && ballX > 70) return 86;
        return baseLine;
    }
    private void pullTowardsBall(PlayerPositionDTO p, MatchRuntime rt) {
        if (rt.currentCarrier != null) return;
        double dx = rt.ball.getX() - p.getX();
        double dy = rt.ball.getY() - p.getY();
        double distance = Math.sqrt(dx * dx + dy * dy);
        if (distance > 42) return;

        double weight;
        if (isGoalkeeper(p)) weight = 0.04;
        else if (isCenterBack(p)) weight = distance < 24 ? 0.08 : 0.05;
        else if (isFullBack(p)) weight = 0.10;
        else if (isStriker(p)) weight = 0.12;
        else weight = 0.14;

        p.setX(p.getX() + dx * weight);
        p.setY(p.getY() + dy * weight);
    }
    private void avoidCrowding(PlayerPositionDTO p, List<PlayerPositionDTO> allPlayers) {
        double minDistance = 10.0;
        for (PlayerPositionDTO other : allPlayers) {
            if (p == other) continue;
            double dx = p.getX() - other.getX();
            double dy = p.getY() - other.getY();
            double distance = Math.sqrt(dx * dx + dy * dy);
            if (distance < minDistance && distance > 0.01) {
                double overlap = minDistance - distance;
                double pushFactor = overlap * 0.08; // Gentle push to avoid explosion
                p.setX(p.getX() + (dx / distance) * pushFactor);
                p.setY(p.getY() + (dy / distance) * pushFactor);
            }
        }
    }

    /** Apply idle jitter when player barely moved */
    private void applyIdleMovement(PlayerPositionDTO p, Random random) {
        double rangeY = 1.3;
        if (isWinger(p) || isFullBack(p)) rangeY = 0.6;
        else if (isStriker(p)) rangeY = 1.0;

        double rangeX = random.nextDouble() * 1.5 + 1.5;  // +/-1.5 to 3

        double targetX = p.getX() + (random.nextDouble() - 0.5) * rangeX;
        double targetY = p.getY() + (random.nextDouble() - 0.5) * rangeY;

        p.setX(clamp(lerp(p.getX(), targetX, smoothFactor)));
        p.setY(clamp(lerp(p.getY(), targetY, smoothFactor)));
    }
    // -------- Role detection helpers --------
    private boolean isGoalkeeper(PlayerPositionDTO p) { return p.getId() == 1 || p.getId() == 12; }
    private boolean isCenterBack(PlayerPositionDTO p)
    { return p.getId() == 4 || p.getId() == 5 || p.getId() == 14 || p.getId() == 15; }
    private boolean isFullBack(PlayerPositionDTO p) { return p.getId() == 2 || p.getId() == 3 || p.getId() == 13 || p.getId() == 16; }
    private boolean isStriker(PlayerPositionDTO p) { int id = p.getId(); return (id >= 9 && id <= 10) || (id >= 21 && id <= 22); }  // Only striker IDs (9,10,21,22).
    private boolean isWinger(PlayerPositionDTO p) { int id = p.getId(); return (id == 7 || id == 11 || id == 19 || id == 20); }
    private boolean isAttacker(PlayerPositionDTO p) { int id = p.getId(); return (id >= 7 && id <= 11) || (id >= 19 && id <= 22); }

    private enum SkillType { PACE, TECHNIQUE, PLAYMAKER, PASSING, STRIKER, DEFENDER, GOALKEEPER }

    private Player getRuntimePlayer(MatchRuntime rt, int positionId) {
        if (positionId >= 1 && positionId <= 11 && positionId - 1 < rt.homeSquad.size()) {
            return rt.homeSquad.get(positionId - 1);
        }
        if (positionId >= 12 && positionId <= 22 && positionId - 12 < rt.awaySquad.size()) {
            return rt.awaySquad.get(positionId - 12);
        }
        return null;
    }

    private double skill(Player p, SkillType type) {
        if (p == null || p.getSkills() == null) {
            return 0.5;
        }
        int raw = switch (type) {
            case PACE -> p.getSkills().getPace();
            case TECHNIQUE -> p.getSkills().getTechnique();
            case PLAYMAKER -> p.getSkills().getPlaymaker();
            case PASSING -> p.getSkills().getPassing();
            case STRIKER -> p.getSkills().getStriker();
            case DEFENDER -> p.getSkills().getDefender();
            case GOALKEEPER -> p.getSkills().getGoalkeeper();
        };
        return normalizeSkill(raw);
    }

    private double overallOutfield(Player p) {
        if (p == null || p.getSkills() == null) {
            return 0.5;
        }
        boolean goalkeeper = p.getPosition() != null && p.getPosition().name().equals("GK");
        if (goalkeeper) {
            return clamp01((skill(p, SkillType.GOALKEEPER) * 0.58)
                    + (skill(p, SkillType.PASSING) * 0.18)
                    + (skill(p, SkillType.PLAYMAKER) * 0.14)
                    + (skill(p, SkillType.DEFENDER) * 0.10));
        }
        return clamp01((skill(p, SkillType.PACE) * 0.18)
                + (skill(p, SkillType.TECHNIQUE) * 0.20)
                + (skill(p, SkillType.PLAYMAKER) * 0.22)
                + (skill(p, SkillType.PASSING) * 0.20)
                + (skill(p, SkillType.STRIKER) * 0.10)
                + (skill(p, SkillType.DEFENDER) * 0.10));
    }

    private double normalizeSkill(int raw) {
        if (raw <= 20) {
            return clamp01(raw / 20.0);
        }
        return clamp01(raw / 100.0);
    }

    private double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private PlayerPositionDTO nearestOpponent(PlayerPositionDTO player, List<PlayerPositionDTO> players) {
        return players.stream()
                .filter(p -> !p.getTeam().equals(player.getTeam()))
                .min(Comparator.comparingDouble(p -> distance(player, p)))
                .orElse(null);
    }

    private double distance(PlayerPositionDTO a, PlayerPositionDTO b) { return Math.hypot(a.getX() - b.getX(), a.getY() - b.getY()); }
    private double distance(BallPositionDTO ball, PlayerPositionDTO player) { return Math.hypot(ball.getX() - player.getX(), ball.getY() - player.getY()); }
    private double distance(double x, double x1) {return x1-x;}
    public double clamp(double val) { return Math.max(0, Math.min(100, val)); }
    double lerp(double start, double end, double alpha) { return start + (end - start) * alpha; }
    // =============================================
    // CARRIER DECISION LOGIC
    // =============================================
    public PlayerPositionDTO chooseNextAction(PlayerPositionDTO carrier, List<PlayerPositionDTO> players, Random random, MatchRuntime rt) {
        if (carrier == null) {
            return null;
        }

        boolean attacksRight = carrier.getTeam().equals("HOME");
        double goalX = attacksRight ? 100 : 0;
        double distToGoal = Math.abs(carrier.getX() - goalX);
        Player carrierEntity = getRuntimePlayer(rt, carrier.getId());
        double playmaker = skill(carrierEntity, SkillType.PLAYMAKER);
        double total = overallOutfield(carrierEntity);
        double passing = skill(carrierEntity, SkillType.PASSING);
        double technique = skill(carrierEntity, SkillType.TECHNIQUE);
        double pace = skill(carrierEntity, SkillType.PACE);
        boolean forwardRole = isWinger(carrier) || isStriker(carrier) || carrier.getId() == 6 || carrier.getId() == 8 || carrier.getId() == 17 || carrier.getId() == 18;

        double shotWeight = 0.0;
        if (forwardRole && distToGoal <= 30) {
            double zoneFactor = 1.0 - Math.min(1.0, distToGoal / 30.0);
            shotWeight = 0.12 + zoneFactor * 0.40 + skill(carrierEntity, SkillType.STRIKER) * 0.16;
            if (distToGoal > 24) {
                shotWeight *= 0.55;
            }
        }

        double dangerPassWeight = playmaker > 0.72 ? 0.38 + (playmaker - 0.72) * 0.45 : 0.10;
        double nearPassWeight = 0.52 + passing * 0.26 + technique * 0.14;
        double dribbleWeight = 0.12 + pace * 0.16 + technique * 0.10;
        double keepWeight = 0.08 + playmaker * 0.08 + total * 0.06;

        PlayerPositionDTO nearestOpponent = nearestOpponent(carrier, players);
        if (nearestOpponent != null && distance(carrier, nearestOpponent) < 7.0) {
            dribbleWeight += 0.12;
            keepWeight -= 0.03;
        }

        String action = pickWeightedAction(random, Map.of(
                "SHOT", Math.max(0.01, shotWeight),
                "DANGER_PASS", Math.max(0.01, dangerPassWeight),
                "NEAR_PASS", Math.max(0.01, nearPassWeight),
                "DRIBBLE", Math.max(0.01, dribbleWeight),
                "KEEP", Math.max(0.01, keepWeight)
        ));

        if ("SHOT".equals(action)) {
            initiateShot(carrier, players, random, attacksRight, rt);
            return carrier;
        }

        if ("DANGER_PASS".equals(action)) {
            PlayerPositionDTO target = findDangerousTeamMate(carrier, players, attacksRight, rt);
            if (target != null && target.getId() != carrier.getId()) {
                initiatePass(carrier, target, rt, passing, technique, playmaker);
                return carrier;
            }
        }

        if ("NEAR_PASS".equals(action)) {
            PlayerPositionDTO target = findNearestTeamMate(carrier, players, 4 + (int) Math.round(playmaker * 3), random);
            if (target != null && target.getId() != carrier.getId()) {
                initiatePass(carrier, target, rt, passing, technique, playmaker);
                return carrier;
            }
        }

        if ("DRIBBLE".equals(action) || "KEEP".equals(action)) {
            applyCarrierProgression(carrier, attacksRight, pace, technique, random);
        }

        return carrier;
    }

    private PlayerPositionDTO findNearestTeamMate(PlayerPositionDTO carrier, List<PlayerPositionDTO> players, int numberOfCandidates, Random rnd) {
        List<PlayerPositionDTO> nearby = findNearbyPlayers(carrier, players, Math.max(2, numberOfCandidates));
        if (nearby == null || nearby.isEmpty()) {
            return null;
        }
        int index = Math.min(nearby.size() - 1, rnd.nextInt(Math.max(1, Math.min(nearby.size(), 3))));
        return nearby.get(index);
    }

    private PlayerPositionDTO findDangerousTeamMate(PlayerPositionDTO carrier, List<PlayerPositionDTO> players, boolean attacksRight, MatchRuntime rt) {
        List<PlayerPositionDTO> teammates = players.stream()
                .filter(p -> p.getTeam().equals(carrier.getTeam()) && p.getId() != carrier.getId())
                .toList();
        if (teammates.isEmpty()) {
            return null;
        }

        double goalX = attacksRight ? 100 : 0;
        return teammates.stream()
                .max(Comparator.comparingDouble(t -> {
                    double progress = 1.0 - Math.min(1.0, Math.abs(t.getX() - goalX) / 100.0);
                    PlayerPositionDTO nearestOpp = nearestOpponent(t, players);
                    double separation = nearestOpp != null ? Math.min(1.0, distance(t, nearestOpp) / 15.0) : 0.8;
                    double centralBonus = 1.0 - Math.min(1.0, Math.abs(t.getY() - 50.0) / 50.0);
                    return progress * 0.52 + separation * 0.34 + centralBonus * 0.14;
                }))
                .orElse(null);
    }

    private void applyCarrierProgression(PlayerPositionDTO carrier, boolean attacksRight, double pace, double technique, Random rnd) {
        double moveX = (attacksRight ? 1 : -1) * (1.0 + pace * 1.9 + rnd.nextDouble() * 0.9);
        double moveY = (rnd.nextDouble() - 0.5) * (1.0 + technique * 1.5);
        carrier.setX(clamp(carrier.getX() + moveX));
        carrier.setY(clamp(carrier.getY() + moveY));
    }

    private String pickWeightedAction(Random rnd, Map<String, Double> weights) {
        double total = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (total <= 0.0) {
            return "KEEP";
        }
        double roll = rnd.nextDouble() * total;
        double acc = 0.0;
        for (Map.Entry<String, Double> entry : weights.entrySet()) {
            acc += entry.getValue();
            if (roll <= acc) {
                return entry.getKey();
            }
        }
        return "KEEP";
    }

    /** Handle ball movement during a shot. Sets lastShotOutcome when shot resolves. */
    public void handleShotMovement(Random random, MatchRuntime rt) {
        rt.shotTicks++;
        double progress = (double) rt.shotTicks / rt.maxShotTicks;
        if (progress >= 1) progress = 1;

        // Interpolate ball position towards target
        rt.ball.setX(clamp(rt.ball.getX() + (rt.targetBallX - rt.ball.getX()) * progress));
        rt.ball.setY(clamp(rt.ball.getY() + (rt.targetBallY - rt.ball.getY()) * progress));

        if (rt.shotTicks >= rt.maxShotTicks) {
            rt.isShooting = false;
            // Outcome decided externally by MatchEngine using DuelCalculator,
            // but we keep a fallback for standalone playback usage
            if (lastShotOutcome == ShotOutcome.PENDING) {
                // Fallback: random outcome (used only if MatchEngine didn't set it)
                double roll = random.nextDouble();
                if (roll < 0.20) lastShotOutcome = ShotOutcome.GOAL;
                else if (roll < 0.70) lastShotOutcome = ShotOutcome.SAVED;
                else lastShotOutcome = ShotOutcome.MISSED;
            }

            switch (lastShotOutcome) {
                case GOAL -> {
                    rt.ball.setX(rt.targetBallX);
                    rt.ball.setY(rt.targetBallY);
                    rt.isRebounding = false;
                    rt.currentCarrier = null;
                }
                case SAVED -> initiateRebound(random, rt);
                case MISSED -> {
                    double missX = rt.attacksRightDuringShot ? 100 : 0;
                    double missY = rt.targetBallY + (random.nextDouble() - 0.5) * 30;
                    rt.ball.setX(clamp(missX));
                    rt.ball.setY(clamp(missY));
                    // Ball goes out -> will be caught by boundary check in MatchEngine
                }
            }
            // Reset for next shot
            lastShotOutcome = ShotOutcome.PENDING;
        }
    }
    private PlayerPositionDTO trySpacePassTarget(PlayerPositionDTO carrier, List<PlayerPositionDTO> players, Random random, boolean attacksRight) {
        double spaceX = attacksRight ? 85 + random.nextDouble() * 12 : 15 - random.nextDouble() * 12;
        double spaceY = 20 + random.nextDouble() * 60;

        return players.stream()
                .filter(p -> p.getTeam().equals(carrier.getTeam()) && p.getId() != carrier.getId())
                .min(Comparator.comparingDouble(p -> Math.hypot(p.getX() - spaceX, p.getY() - spaceY)))
                .orElse(null);
    }

    private PlayerPositionDTO findPlayerById(List<PlayerPositionDTO> players, Integer id) {
        if (id == null) {
            return null;
        }

        return players.stream()
                .filter(p -> Objects.equals(p.getId(), id))
                .findFirst()
                .orElse(null);
    }

    private void initiatePass(PlayerPositionDTO passer, PlayerPositionDTO receiver, MatchRuntime rt,
                              double passingSkill, double techniqueSkill, double playmakerSkill) {
        if (passer == null || receiver == null || Objects.equals(passer.getId(), receiver.getId())) {
            return;
        }

        rt.isPassing = true;
        rt.passTicks = 0;
        rt.pendingPasserId = passer.getId();
        rt.pendingPassTeam = passer.getTeam();
        rt.pendingReceiverId = receiver.getId();
        double quality = passingSkill * 0.48 + techniqueSkill * 0.32 + playmakerSkill * 0.20;
        quality += (random.nextDouble() - 0.5) * 0.22;
        rt.passQuality = Math.max(0.12, Math.min(0.95, quality));
        double passDistance = Math.hypot(receiver.getX() - passer.getX(), receiver.getY() - passer.getY());
        rt.maxPassTicks = Math.max(5, Math.min(14, (int) Math.round(passDistance / (1.6 + rt.passQuality * 1.8))));

        double errorRadius = (1.0 - rt.passQuality) * 18.0;
        rt.targetBallX = clamp(receiver.getX() + (random.nextDouble() - 0.5) * errorRadius);
        rt.targetBallY = clamp(receiver.getY() + (random.nextDouble() - 0.5) * errorRadius);
        rt.lastTouchTeam = passer.getTeam();
    }

    public void handlePassMovement(List<PlayerPositionDTO> players, MatchRuntime rt) {
        rt.passTicks++;
        double passSpeed = 0.22 + rt.passQuality * 0.30;
        rt.ball.setX(clamp(rt.ball.getX() + (rt.targetBallX - rt.ball.getX()) * passSpeed));
        rt.ball.setY(clamp(rt.ball.getY() + (rt.targetBallY - rt.ball.getY()) * passSpeed));

        PlayerPositionDTO interceptor = players.stream()
                .filter(p -> rt.pendingPassTeam != null && !rt.pendingPassTeam.equals(p.getTeam()))
                .min(Comparator.comparingDouble(p -> distance(rt.ball, p)))
                .orElse(null);
        if (interceptor != null && distance(rt.ball, interceptor) <= INTERCEPTION_DISTANCE) {
            if (random.nextDouble() < 0.68) {
                rt.isPassing = false;
                rt.currentCarrier = interceptor;
                rt.lastTouchTeam = interceptor.getTeam();
                rt.pendingReceiverId = null;
                rt.pendingPasserId = null;
                rt.pendingPassTeam = null;
                rt.passTicks = 0;
                return;
            }
            // Deflection
            rt.isPassing = false;
            rt.currentCarrier = null;
            rt.ball.setX(clamp(rt.ball.getX() + (random.nextDouble() - 0.5) * 8));
            rt.ball.setY(clamp(rt.ball.getY() + (random.nextDouble() - 0.5) * 12));
            rt.pendingReceiverId = null;
            rt.pendingPasserId = null;
            rt.pendingPassTeam = null;
            rt.passTicks = 0;
            return;
        }

        if (rt.passTicks >= rt.maxPassTicks || Math.hypot(rt.ball.getX() - rt.targetBallX, rt.ball.getY() - rt.targetBallY) < 1.4) {
            rt.isPassing = false;
            PlayerPositionDTO receiver = findPlayerById(players, rt.pendingReceiverId);
            if (receiver != null) {
                Player receiverEntity = getRuntimePlayer(rt, receiver.getId());
                double control = skill(receiverEntity, SkillType.TECHNIQUE) * 0.55
                        + skill(receiverEntity, SkillType.PLAYMAKER) * 0.20
                        + overallOutfield(receiverEntity) * 0.25
                        + (random.nextDouble() - 0.5) * 0.18;
                if (control > 0.45) {
                    rt.currentCarrier = receiver;
                    rt.ball.setX(receiver.getX());
                    rt.ball.setY(receiver.getY());
                    rt.lastTouchTeam = receiver.getTeam();
                } else {
                    rt.currentCarrier = null;
                    rt.ball.setX(clamp(rt.ball.getX() + (random.nextDouble() - 0.5) * 5.0));
                    rt.ball.setY(clamp(rt.ball.getY() + (random.nextDouble() - 0.5) * 8.0));
                }
            }
            rt.pendingReceiverId = null;
            rt.pendingPasserId = null;
            rt.pendingPassTeam = null;
            rt.passQuality = 0.0;
            rt.passTicks = 0;
        }
    }
    public void handleReboundMovement(List<PlayerPositionDTO> players, Random random, MatchRuntime rt) {
        rt.reboundTicks++;
        double progress = (double) rt.reboundTicks / rt.maxReboundTicks;
        if (progress >= 1) progress = 1;

        // Interpolate ball towards rebound target
        rt.ball.setX(clamp(rt.ball.getX() + (rt.targetBallX - rt.ball.getX()) * progress));
        rt.ball.setY(clamp(rt.ball.getY() + (rt.targetBallY - rt.ball.getY()) * progress));

        if (rt.reboundTicks >= rt.maxReboundTicks) {
            rt.isRebounding = false;
            // Ball is loose - nearest player picks it up
            rt.currentCarrier = players.stream()
                    .min(Comparator.comparingDouble(p -> distance(rt.ball, p)))
                    .orElse(rt.currentCarrier);
            if (rt.currentCarrier != null) {
                rt.lastTouchTeam = rt.currentCarrier.getTeam();
            }
        }
    }

    private void initiateRebound(Random random, MatchRuntime rt) {
        rt.targetBallX = 50 + (random.nextDouble() - 0.5) * 20;
        rt.targetBallY = 50 + (random.nextDouble() - 0.5) * 20;
        rt.isRebounding = true;
        rt.reboundTicks = 0;
    }
    private void initiateShot(PlayerPositionDTO shooter, List<PlayerPositionDTO> players, Random random, boolean attacksRight, MatchRuntime rt) {
        rt.attacksRightDuringShot = attacksRight;
        rt.targetBallX = attacksRight ? 99 : 1;
        rt.targetBallY = 50 + (random.nextDouble() - 0.5) * 9;

        rt.isShooting = true;
        rt.shotTicks = 0;
        rt.lastTouchTeam = shooter.getTeam();
    }
    public void updatePositions(MatchRuntime rt) {
        if (rt.currentCarrier == null) {
            pullLooseBallToNearestPlayer(rt);
        }
        rt.players.forEach(p -> {
            boolean attacksRight = p.getTeam().equals("HOME");
            movePlayerByRole(
                    p,
                    rt.players,
                    ThreadLocalRandom.current(),
                    attacksRight,
                    rt
            );
        });
    }
    public void handlePossessionAndActions(MatchRuntime rt, Random random) {
        if (rt.currentCarrier == null || rt.isShooting || rt.isRebounding || rt.isPassing) {
            return;
        }

        triggerCloseDuelIfNeeded(rt, random);
        if (rt.currentCarrier == null || rt.isPassing || rt.isShooting) {
            return;
        }

        if (rt.reactionTicksRemaining > 0) {
            rt.reactionTicksRemaining--;
            return;
        }
        if (rt.tick < rt.nextDecisionTick) {
            return;
        }

        Player carrierEntity = getRuntimePlayer(rt, rt.currentCarrier.getId());
        double decisionIQ = skill(carrierEntity, SkillType.PLAYMAKER) * 0.62 + overallOutfield(carrierEntity) * 0.38;

        PlayerPositionDTO next = chooseNextAction(rt.currentCarrier, rt.players, random, rt);
        if (!rt.isPassing && next != null) {
            rt.currentCarrier = next;
        }

        int ticksPerMinute = rt.ticksPerMinute > 0 ? rt.ticksPerMinute : 27;
        int minDecisionGap = Math.max(7, ticksPerMinute / 4);
        int maxDecisionGap = Math.max(11, ticksPerMinute / 3 + 2);
        int iqBonus = (int) Math.round(decisionIQ * 2.0);
        int nextGap = Math.max(6, minDecisionGap - iqBonus) + random.nextInt(Math.max(2, maxDecisionGap - minDecisionGap + 1));
        rt.nextDecisionTick = rt.tick + nextGap;
        rt.reactionTicksRemaining = rt.isPassing ? 3 + random.nextInt(3) : 2 + random.nextInt(2);

        if (!rt.isPassing && random.nextDouble() < 0.08) {
            PlayerPositionDTO target = trySpacePassTarget(
                    rt.currentCarrier,
                    rt.players,
                    random,
                    rt.currentCarrier.getTeam().equals("HOME")
            );
            if (target != null && target.getId() != rt.currentCarrier.getId()) {
                initiatePass(rt.currentCarrier, target, rt,
                        skill(carrierEntity, SkillType.PASSING),
                        skill(carrierEntity, SkillType.TECHNIQUE),
                        skill(carrierEntity, SkillType.PLAYMAKER));
            }
        }
    }
    public void updateBallPosition(MatchRuntime rt) {
        if (rt.isShooting) {
            handleShotMovement(random, rt);
        } else if (rt.isRebounding) {
            handleReboundMovement(rt.players, random, rt);
        } else if (rt.isPassing) {
            handlePassMovement(rt.players, rt);
        } else {
            if (rt.currentCarrier == null) {
                pullLooseBallToNearestPlayer(rt);
                return;
            }
            rt.ball.setX(rt.currentCarrier.getX());
            rt.ball.setY(rt.currentCarrier.getY());
        }
    }

    private void triggerCloseDuelIfNeeded(MatchRuntime rt, Random rnd) {
        if (rt.currentCarrier == null) {
            return;
        }
        PlayerPositionDTO defenderPos = nearestOpponent(rt.currentCarrier, rt.players);
        if (defenderPos == null || distance(rt.currentCarrier, defenderPos) > DUEL_DISTANCE) {
            return;
        }

        Player attacker = getRuntimePlayer(rt, rt.currentCarrier.getId());
        Player defender = getRuntimePlayer(rt, defenderPos.getId());
        if (attacker == null || defender == null) {
            return;
        }

        MatchContext duelContext = new MatchContext(rt.matchRef, rt.crowd, rt.referee, rt.homeTactics, rt.awayTactics);
        int ticksPerMinute = rt.ticksPerMinute > 0 ? rt.ticksPerMinute : 27;
        duelContext.setCurrentMinute(Math.min(90, rt.tick / ticksPerMinute + 1));
        DuelCalculator.DuelResult result = DuelCalculator.resolveDuel(attacker, defender, duelContext, DuelCalculator.DuelType.DRIBBLING);
        if (result.getOutcome() == DuelCalculator.DuelOutcomeQuality.CLEAN) {
            return;
        }

        if (result.getOutcome() == DuelCalculator.DuelOutcomeQuality.FAIL || rnd.nextDouble() < 0.44) {
            rt.currentCarrier = defenderPos;
            rt.lastTouchTeam = defenderPos.getTeam();
            rt.ball.setX(defenderPos.getX());
            rt.ball.setY(defenderPos.getY());
            return;
        }

        // Partial duel can create a loose deflected ball.
        rt.currentCarrier = null;
        rt.ball.setX(clamp(rt.ball.getX() + (rnd.nextDouble() - 0.5) * 5.5));
        rt.ball.setY(clamp(rt.ball.getY() + (rnd.nextDouble() - 0.5) * 7.5));
    }

    private void pullLooseBallToNearestPlayer(MatchRuntime rt) {
        PlayerPositionDTO nearest = rt.players.stream()
                .min(Comparator.comparingDouble(p -> distance(rt.ball, p)))
                .orElse(null);
        if (nearest == null) {
            return;
        }
        if (distance(rt.ball, nearest) <= LOOSE_BALL_PICKUP_DISTANCE) {
            rt.currentCarrier = nearest;
            rt.lastTouchTeam = nearest.getTeam();
            rt.ball.setX(nearest.getX());
            rt.ball.setY(nearest.getY());
        }
    }

    private void applyContextReaction(PlayerPositionDTO p, List<PlayerPositionDTO> players, MatchRuntime rt) {
        if (rt.currentCarrier == null) {
            return;
        }
        if (p.getId() == rt.currentCarrier.getId()) {
            return;
        }

        boolean sameTeam = p.getTeam().equals(rt.currentCarrier.getTeam());
        double distCarrier = distance(p, rt.currentCarrier);

        if (sameTeam) {
            if (distCarrier > 8 && distCarrier < 42) {
                double forwardBias = p.getTeam().equals("HOME") ? 5.0 : -5.0;
                double tx;
                double ty;
                if (isFullBack(p) || isWinger(p)) {
                    double laneY = (p.getId() == 2 || p.getId() == 13 || p.getId() == 11 || p.getId() == 20) ? 20 : 80;
                    tx = rt.currentCarrier.getX() + (isWinger(p) ? forwardBias : forwardBias * 0.35);
                    ty = laneY + (rt.currentCarrier.getY() - laneY) * 0.08;
                } else if (isStriker(p)) {
                    tx = rt.currentCarrier.getX() + forwardBias * 0.85;
                    ty = rt.currentCarrier.getY() + (random.nextDouble() - 0.5) * 14;
                } else if (isCenterBack(p)) {
                    tx = p.getTeam().equals("HOME") ? 26 : 74;
                    ty = (p.getId() == 4 || p.getId() == 14) ? 44 : 56;
                } else {
                    tx = rt.currentCarrier.getX() + forwardBias * 0.25;
                    ty = rt.currentCarrier.getY() + (random.nextDouble() - 0.5) * 10;
                }
                p.setX(clamp(lerp(p.getX(), tx, smoothFactor * 0.45)));
                p.setY(clamp(lerp(p.getY(), ty, smoothFactor * 0.45)));
            }
            return;
        }

        // Defensive team presses carrier instead of staying static.
        if (distCarrier < PRESS_DISTANCE) {
            double press = isCenterBack(p) || isFullBack(p) ? 0.30 : isGoalkeeper(p) ? 0.08 : 0.22;
            double tx = rt.currentCarrier.getX();
            double ty;
            if (isFullBack(p) || isWinger(p)) {
                double laneY = (p.getId() == 2 || p.getId() == 13 || p.getId() == 11 || p.getId() == 20) ? 20 : 80;
                ty = laneY + (rt.currentCarrier.getY() - laneY) * 0.25;
            } else if (isCenterBack(p)) {
                double lineY = (p.getId() == 4 || p.getId() == 14) ? 44 : 56;
                ty = lineY + (rt.currentCarrier.getY() - lineY) * 0.30;
            } else {
                ty = rt.currentCarrier.getY();
            }
            p.setX(clamp(lerp(p.getX(), tx, smoothFactor * press)));
            p.setY(clamp(lerp(p.getY(), ty, smoothFactor * press)));
        } else if (isCenterBack(p) || isFullBack(p)) {
            // Keep backline shifting with ball even when far.
            double goalX = p.getTeam().equals("HOME") ? 14 : 86;
            double tx = goalX + (rt.ball.getX() - goalX) * 0.18;
            double lineY = isCenterBack(p) ? ((p.getId() == 4 || p.getId() == 14) ? 44 : 56) : ((p.getId() == 2 || p.getId() == 13) ? 24 : 76);
            double ty = lineY + (rt.ball.getY() - lineY) * 0.14;
            p.setX(clamp(lerp(p.getX(), tx, smoothFactor * 0.42)));
            p.setY(clamp(lerp(p.getY(), ty, smoothFactor * 0.42)));
        }
    }
}

