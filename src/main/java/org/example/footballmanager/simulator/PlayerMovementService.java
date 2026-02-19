package org.example.footballmanager.simulator;

import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.dto.BallPositionDTO;
import org.example.footballmanager.dto.PlayerPositionDTO;
import org.example.footballmanager.service.DemoMatchRuntime;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Random;

@Component
@Slf4j
public class PlayerMovementService {

    double smoothFactor = 0.25; // 0.0 = ne pomera se, 1.0 = teleport

    public void movePlayerByRole(PlayerPositionDTO p, List<PlayerPositionDTO> players, Random random, boolean attacksRight, DemoMatchRuntime rt) {
        int id = p.getId();
        if (id == 1 || id == 12) moveGoalkeeper(p, random, attacksRight);
        else if (id == 2 || id == 13) moveFullback(p, players, random, attacksRight, true, rt);
        else if (id == 3 || id == 16) moveFullback(p, players, random, attacksRight, false, rt);
        else if (id == 4 || id == 5 || id == 14 || id == 15) moveCenterBack(p, players, random, attacksRight);
        else if (id == 6 || id == 8 || id == 17 || id == 18) moveCentralMidfielder(p, players, random, attacksRight, rt);
        else if (id == 7 || id == 11 || id == 19 || id == 20) moveWinger(p, players, random, attacksRight, rt);
        else if (id == 9 || id == 10 || id == 21 || id == 22) moveStriker(p, players, random, attacksRight, rt);

        pullTowardsBall(p, random, rt, attacksRight);
        avoidCrowding(p, players, random);
        applyIdleMovement(p, random);
        handleOffsideTolerance(p, players, attacksRight, rt);
    }

    // -------- Metode po pozicijama --------
    private void moveGoalkeeper(PlayerPositionDTO gk, Random random, boolean attacksRight) {
        double goalX = attacksRight ? 6 : 94;
        double targetX = goalX + (random.nextDouble() - 0.5) * 5;
        double targetY = 48 + (random.nextDouble() - 0.5) * 12;
        gk.setX(clamp(lerp(gk.getX(), targetX, smoothFactor)));
        gk.setY(clamp(lerp(gk.getY(), targetY, smoothFactor)));
    }

    private void moveFullback(PlayerPositionDTO fb, List<PlayerPositionDTO> players, Random random, boolean attacksRight, boolean isRightBack, DemoMatchRuntime rt) {
        double minX = 0;
        double maxX = 40;
        double targetX = attacksRight ? Math.min(maxX, fb.getX() + 1.5) : Math.max(minX, fb.getX() - 1.5);

        PlayerPositionDTO sideWinger = players.stream()
                .filter(p -> isRightBack ? p.getId() == 11 : p.getId() == 7)
                .findFirst()
                .orElse(null);
        if (sideWinger != null) {
            double dx = fb.getX() - sideWinger.getX();
            if (Math.abs(dx) < 10) {
                targetX += dx > 0 ? 5 : -5;
            }
        }

        if (!fb.getTeam().equals(rt.currentCarrier.getTeam())) {
            PlayerPositionDTO threat = players.stream()
                    .filter(p -> !p.getTeam().equals(fb.getTeam()))
                    .min(Comparator.comparingDouble(p -> distance(fb, p)))
                    .orElse(null);
            if (threat != null && distance(fb, threat) < 15) {
                targetX += (threat.getX() - fb.getX()) * 0.2;
            }
        }

        double targetY = isRightBack ? 30 + (random.nextDouble() - 0.5) * 6 : 66 + (random.nextDouble() - 0.5) * 6;
        fb.setX(clamp(lerp(fb.getX(), targetX, smoothFactor)));
        fb.setY(clamp(lerp(fb.getY(), targetY, smoothFactor)));
    }

    private void moveCenterBack(PlayerPositionDTO cb, List<PlayerPositionDTO> players, Random random, boolean attacksRight) {
        double baseX = attacksRight ? 15 : 85;
        double targetX = baseX + (random.nextDouble() - 0.5) * 6;
        double targetY = 48 + (random.nextDouble() - 0.5) * 12;
        cb.setX(clamp(lerp(cb.getX(), targetX, smoothFactor)));
        cb.setY(clamp(lerp(cb.getY(), targetY, smoothFactor)));
    }

    private void moveCentralMidfielder(PlayerPositionDTO cm, List<PlayerPositionDTO> players, Random random, boolean attacksRight, DemoMatchRuntime rt) {
        double baseX = attacksRight ? 45 : 55;
        double toBallX = (rt.ball.getX() - cm.getX()) * 0.12;
        double toBallY = (rt.ball.getY() - cm.getY()) * 0.12;
        double targetX = baseX + (random.nextDouble() - 0.5) * 10 + toBallX;
        double targetY = 50 + (random.nextDouble() - 0.5) * 12 + toBallY;
        cm.setX(clamp(lerp(cm.getX(), targetX, smoothFactor)));
        cm.setY(clamp(lerp(cm.getY(), targetY, smoothFactor)));
    }

    private void moveWinger(PlayerPositionDTO winger, List<PlayerPositionDTO> players, Random random, boolean attacksRight, DemoMatchRuntime rt) {
        double baseY = (winger.getId() == 7 || winger.getId() == 19) ? 76 : 20;
        double minX = 30;
        double maxX = 100;
        double step = attacksRight ? 2 : -2;
        double targetX = attacksRight ? Math.min(maxX, winger.getX() + step) : Math.max(minX, winger.getX() + step);

        if (winger.getTeam().equals(rt.currentCarrier.getTeam())) {
            if (checkOffsideRisk(winger, players, attacksRight, rt)) {
                if (winger.getOffsideTicksRemaining() < 3) {
                    winger.setOffsideTicksRemaining(winger.getOffsideTicksRemaining() + 1);
                } else {
                    targetX -= step * 3;
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

        double targetY = baseY + (random.nextDouble() - 0.5) * 5;
        winger.setX(clamp(lerp(winger.getX(), targetX, smoothFactor)));
        winger.setY(clamp(lerp(winger.getY(), targetY, smoothFactor)));
    }

    private void moveStriker(PlayerPositionDTO striker, List<PlayerPositionDTO> players, Random random, boolean attacksRight, DemoMatchRuntime rt) {
        double goalX = attacksRight ? 100 : 0;
        double step = attacksRight ? 2 : -2;
        double targetX = striker.getX() + step;

        if (!(striker.getId() == rt.currentCarrier.getId())) {
            if (checkOffsideRisk(striker, players, attacksRight, rt)) {
                if (striker.getOffsideTicksRemaining() < 3) {
                    striker.setOffsideTicksRemaining(striker.getOffsideTicksRemaining() + 1);
                } else {
                    targetX -= step * 3;
                    striker.setOffsideTicksRemaining(0);
                }
            } else {
                striker.setOffsideTicksRemaining(0);
            }
        }

        double targetY = 48 + (random.nextDouble() - 0.5) * 12;
        striker.setX(clamp(lerp(striker.getX(), targetX, smoothFactor)));
        striker.setY(clamp(lerp(striker.getY(), targetY, smoothFactor)));
    }

    // -------- Pomoćne metode --------
    private void pullTowardsBall(PlayerPositionDTO p, Random random, DemoMatchRuntime rt, boolean attacksRight) {
        if (p.getId() == rt.currentCarrier.getId()) return;

        double weight = 0.13;
        double minX = (p.getId() == 7 || p.getId() == 19) ? 30 : 45;
        double maxX = (p.getId() == 7 || p.getId() == 19) ? 55 : 70;
        double targetX = attacksRight ? Math.min(maxX, p.getX() + 2) : Math.max(minX, p.getX() - 2);

        if (isWinger(p)) {
            double toBallX = (rt.ball.getX() - p.getX()) * 0.02;
            targetX = Math.max(minX, Math.min(maxX, p.getX() + toBallX));
        } else if (isStriker(p)) weight = 0.1;
        else if (isCenterBack(p)) weight = 0.07;

        double toBallX = (rt.ball.getX() - p.getX()) * weight;
        double toBallY = (rt.ball.getY() - p.getY()) * (weight + 0.02);

        p.setX(clamp(lerp(p.getX(), p.getX() + toBallX, smoothFactor)));
        p.setY(clamp(lerp(p.getY(), p.getY() + toBallY, smoothFactor)));
    }

    private void avoidCrowding(PlayerPositionDTO p, List<PlayerPositionDTO> players, Random random) {
        double factor = 0.3;
        if (isWinger(p) || isFullBack(p)) factor = 0.15;
        else if (isStriker(p)) factor = 0.2;

        for (PlayerPositionDTO other : players) {
            if (other.getId() == p.getId()) continue;
            double dx = other.getX() - p.getX();
            double dy = other.getY() - p.getY();
            double dist = Math.sqrt(dx * dx + dy * dy);
            if (dist < 3) {
                double targetX = p.getX() - dx * factor;
                double targetY = p.getY() - dy * factor;
                p.setX(clamp(lerp(p.getX(), targetX, smoothFactor)));
                p.setY(clamp(lerp(p.getY(), targetY, smoothFactor)));
            }
        }
    }

    private void applyIdleMovement(PlayerPositionDTO p, Random random) {
        double range = 1.3;
        if (isWinger(p) || isFullBack(p)) range = 0.6;
        else if (isStriker(p)) range = 1.0;

        double targetY = p.getY() + (random.nextDouble() - 0.5) * range;
        p.setY(clamp(lerp(p.getY(), targetY, smoothFactor)));
    }

    private void handleOffsideTolerance(PlayerPositionDTO p, List<PlayerPositionDTO> players, boolean attacksRight, DemoMatchRuntime rt) {
        double offsideLine = players.stream()
                .filter(pl -> (attacksRight ? pl.getX() > 50 : pl.getX() < 50) && pl.getId() != p.getId())
                .map(PlayerPositionDTO::getX)
                .min(Comparator.naturalOrder())
                .orElse(50.0);

        double targetX = p.getX();
        if (attacksRight && p.getX() > offsideLine + 2) targetX = offsideLine + 2;
        if (!attacksRight && p.getX() < offsideLine - 2) targetX = offsideLine - 2;

        p.setX(clamp(lerp(p.getX(), targetX, smoothFactor)));
    }

    // -------- Ostale pomoćne metode --------
    private boolean checkOffsideRisk(PlayerPositionDTO winger, List<PlayerPositionDTO> players, boolean attacksRight, DemoMatchRuntime rt) {
        String defendingTeam = attacksRight ? "AWAY" : "HOME";
        double offsideLine = players.stream()
                .filter(p -> p.getTeam().equals(defendingTeam) && !isGoalkeeper(p))
                .map(PlayerPositionDTO::getX)
                .min(attacksRight ? Comparator.naturalOrder() : Comparator.reverseOrder())
                .orElse(50.0);
        return attacksRight ? winger.getX() > offsideLine : winger.getX() < offsideLine;
    }

    private boolean isGoalkeeper(PlayerPositionDTO p) { return p.getId() == 1 || p.getId() == 12; }
    private boolean isCenterBack(PlayerPositionDTO p) { return p.getId() == 4 || p.getId() == 5 || p.getId() == 16 || p.getId() == 17; }
    private boolean isFullBack(PlayerPositionDTO p) { return p.getId() == 2 || p.getId() == 3 || p.getId() == 13 || p.getId() == 16; }
    private boolean isStriker(PlayerPositionDTO p) { int id = p.getId(); return (id >= 9 && id <= 11) || (id >= 21 && id <= 22); }
    private boolean isWinger(PlayerPositionDTO p) { int id = p.getId(); return (id == 7 || id == 11 || id == 19 || id == 20); }
    private boolean isAttacker(PlayerPositionDTO p) { int id = p.getId(); return (id >= 7 && id <= 11) || (id >= 19 && id <= 22); }

    private double distance(PlayerPositionDTO a, PlayerPositionDTO b) { return Math.hypot(a.getX() - b.getX(), a.getY() - b.getY()); }
    private double distance(BallPositionDTO ball, PlayerPositionDTO player) { return Math.hypot(ball.getX() - player.getX(), ball.getY() - player.getY()); }

    public double clamp(double val) { return Math.max(0, Math.min(100, val)); }
    double lerp(double start, double end, double alpha) { return start + (end - start) * alpha; }
}
