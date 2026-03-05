package org.example.footballmanager.service;

import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.dto.BallPositionDTO;
import org.example.footballmanager.dto.PlayerPositionDTO;
import org.example.footballmanager.model.MatchRuntime;
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

    double smoothFactor = 0.18; // 0.0 = no movement, 1.0 = teleport
    private static final double MIN_MOVEMENT_THRESHOLD = 0.5;
    private static final int MAX_RETREAT_TICKS = 8;
    private static final double DEEP_RETREAT_FORCE = 25.0;
    private static final double RETREAT_FORCE = 12.0;
    private static final double ATTACKER_PULL_WEIGHT = 0.05;
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
        double goalX = attacksRight ? 6 : 94;
        double targetX = goalX + (random.nextDouble() - 0.5) * 5;
        double targetY = 48 + (random.nextDouble() - 0.5) * 12;
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
        double baseX = attacksRight ? 45 : 55;
        double toBallX = (rt.ball.getX() - cm.getX()) * 0.12;
        double toBallY = (rt.ball.getY() - cm.getY()) * 0.12;
        double targetX = baseX + (random.nextDouble() - 0.5) * 10 + toBallX;
        double targetY = 50 + (random.nextDouble() - 0.5) * 12 + toBallY;
        cm.setX(clamp(lerp(cm.getX(), targetX, smoothFactor)));
        cm.setY(clamp(lerp(cm.getY(), targetY, smoothFactor)));
    }
    private void moveWinger(PlayerPositionDTO winger, List<PlayerPositionDTO> players, Random random, boolean attacksRight, MatchRuntime rt) {
        double baseY = (winger.getId() == 7 || winger.getId() == 19) ? 76 : 20;
        double minX = attacksRight ? 30 : 20;  // Lower floor for away-side winger progression.
        double maxX = 100;
        double step = attacksRight ? 5 + (random.nextDouble() - 0.5) * 4 : -5 + (random.nextDouble() - 0.5) * 4;  // Random +/-2
        double targetX = attacksRight ? Math.min(maxX, winger.getX() + step) : Math.max(minX, winger.getX() + step);

        if (rt.currentCarrier != null && winger.getTeam().equals(rt.currentCarrier.getTeam())) {
            if (checkOffsideRisk(winger, players, attacksRight, rt)) {
                if (winger.getOffsideTicksRemaining() < 3) {
                    winger.setOffsideTicksRemaining(winger.getOffsideTicksRemaining() + 1);
                } else {
                    targetX -= step;
                    winger.setOffsideTicksRemaining(0);
                }
            } else {
                winger.setOffsideTicksRemaining(0);
            }
        } else {
            PlayerPositionDTO nearestOpponent = players.stream()
                    .filter(p -> !p.getTeam().equals(winger.getTeam()))
                    .min(Comparator.comparingDouble(p -> distance(winger, p)))
                    .orElse(null);
            if (nearestOpponent != null && distance(winger, nearestOpponent) < 10) {
                targetX += (nearestOpponent.getX() - winger.getX()) * 0.15;
            }
        }

        // Soft rollback in the final attacking zone.
        if (attacksRight && winger.getX() > 92) targetX = 80;
        if (!attacksRight && winger.getX() < 8) targetX = 20;

        double targetY = baseY + (random.nextDouble() - 0.5) * 5;
        winger.setX(clamp(lerp(winger.getX(), targetX, smoothFactor)));
        winger.setY(clamp(lerp(winger.getY(), targetY, smoothFactor)));
    }
    // Updated: striker movement with randomized step (+/-2)
    private void moveStriker(PlayerPositionDTO striker, List<PlayerPositionDTO> players, Random random, boolean attacksRight, MatchRuntime rt) {
        double goalX = attacksRight ? 100 : 0;

        double distToBall = distance(rt.ball, striker);
        double baseStep = attacksRight ? 6.0 : -6.0;          // Reduced from 9 to 6 for smoother movement.
        double stepMultiplier = Math.min(1.2, Math.max(0.3, (80 - distToBall) / 60.0)); // Closer to ball -> larger step.

        double randomPart = (random.nextDouble() - 0.5) * 3.0; // +/-1.5 instead of +/-2
        double step = baseStep * stepMultiplier + randomPart;

        double targetX = striker.getX() + step;

        if (rt.currentCarrier != null && striker.getId() != rt.currentCarrier.getId()) {
            if (checkOffsideRisk(striker, players, attacksRight, rt)) {
                if (striker.getOffsideTicksRemaining() < 3) {
                    striker.setOffsideTicksRemaining(striker.getOffsideTicksRemaining() + 1);
                } else {
                    targetX -= step;
                    striker.setOffsideTicksRemaining(0);
                }
            } else {
                striker.setOffsideTicksRemaining(0);
            }
        }

        // Soft rollback in the final attacking zone.
        if (attacksRight && striker.getX() > 92) targetX = 75;
        if (!attacksRight && striker.getX() < 8) targetX = 25;

        // Additional limit: do not move too far ahead of the carrier.
        if (rt.currentCarrier != null && rt.currentCarrier.getTeam().equals(striker.getTeam())) {
            double carrierX = rt.currentCarrier.getX();
            double maxAllowed = attacksRight ? carrierX + 18 : carrierX - 18;
            targetX = attacksRight ? Math.min(targetX, maxAllowed) : Math.max(targetX, maxAllowed);
        }

        double targetY = 48 + (random.nextDouble() - 0.5) * 12;
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
        if (distance > 15) return; // Only if ball is relatively close

        double weight;
        if (isStriker(p)) weight = 0.06;
        else if (isCenterBack(p)) weight = 0.03;
        else weight = 0.08;

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

        // Shooting zone - probability increases as player gets closer to goal
        if (distToGoal <= 45) {
            double shotProbability;
            if (distToGoal <= 18) shotProbability = 0.88;
            else if (distToGoal <= 22) shotProbability = 0.75;
            else if (distToGoal <= 27) shotProbability = 0.52;
            else if (distToGoal <= 35) shotProbability = 0.30;
            else shotProbability = 0.18; // long-range shot

            if (random.nextDouble() < shotProbability) {
                initiateShot(carrier, players, random, attacksRight, rt);
                return carrier; // carrier stays the same during shot
            }
        }
        // Space pass or normal pass
        if (random.nextDouble() < 0.48) {
            PlayerPositionDTO target = trySpacePassTarget(carrier, players, random, attacksRight);
            if (target != null && target.getId() != carrier.getId()) {
                initiatePass(carrier, target, rt);
                return carrier;
            }
        }

        List<PlayerPositionDTO> nearby = findNearbyPlayers(carrier, players, 4);
        if (nearby == null || nearby.isEmpty()) {
            return carrier;
        }

        PlayerPositionDTO receiver = nearby.get(random.nextInt(nearby.size()));
        if (receiver.getId() != carrier.getId()) {
            initiatePass(carrier, receiver, rt);
        }
        return carrier;
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

    private void initiatePass(PlayerPositionDTO passer, PlayerPositionDTO receiver, MatchRuntime rt) {
        if (passer == null || receiver == null || Objects.equals(passer.getId(), receiver.getId())) {
            return;
        }

        rt.isPassing = true;
        rt.passTicks = 0;
        rt.pendingReceiverId = receiver.getId();
        rt.targetBallX = receiver.getX();
        rt.targetBallY = receiver.getY();
        rt.lastTouchTeam = passer.getTeam();
    }

    public void handlePassMovement(List<PlayerPositionDTO> players, MatchRuntime rt) {
        rt.passTicks++;
        double progress = Math.min(1.0, (double) rt.passTicks / rt.maxPassTicks);

        rt.ball.setX(clamp(rt.ball.getX() + (rt.targetBallX - rt.ball.getX()) * progress));
        rt.ball.setY(clamp(rt.ball.getY() + (rt.targetBallY - rt.ball.getY()) * progress));

        if (rt.passTicks >= rt.maxPassTicks) {
            rt.isPassing = false;
            PlayerPositionDTO receiver = findPlayerById(players, rt.pendingReceiverId);
            if (receiver != null) {
                rt.currentCarrier = receiver;
                rt.ball.setX(receiver.getX());
                rt.ball.setY(receiver.getY());
                rt.lastTouchTeam = receiver.getTeam();
            }
            rt.pendingReceiverId = null;
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
        rt.targetBallY = 50 + (random.nextDouble() - 0.5) * 14;

        rt.isShooting = true;
        rt.shotTicks = 0;
        rt.lastTouchTeam = shooter.getTeam();
    }
    public void updatePositions(MatchRuntime rt) {
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

        rt.possessionTicks++;
        if (rt.possessionTicks > 6 + random.nextInt(9)) {
            PlayerPositionDTO next = chooseNextAction(rt.currentCarrier, rt.players, random, rt);
            if (!rt.isPassing && next != null) {
                rt.currentCarrier = next;
            }
            rt.possessionTicks = 0;
        }

        if (!rt.isPassing) {
            rt.spacePassCooldown++;
            if (rt.spacePassCooldown > 8 && random.nextDouble() < 0.17) {
                PlayerPositionDTO target = trySpacePassTarget(
                        rt.currentCarrier,
                        rt.players,
                        random,
                        rt.currentCarrier.getTeam().equals("HOME")
                );
                if (target != null && target.getId() != rt.currentCarrier.getId()) {
                    initiatePass(rt.currentCarrier, target, rt);
                }
                rt.spacePassCooldown = 0;
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
                return;
            }
            rt.ball.setX(rt.currentCarrier.getX());
            rt.ball.setY(rt.currentCarrier.getY());
        }
    }
}

