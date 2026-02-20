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
        else if (id == 4 || id == 5 || id == 14 || id == 15) moveCenterBack(p, players, random, attacksRight, rt);
        else if (id == 6 || id == 8 || id == 17 || id == 18) moveCentralMidfielder(p, players, random, attacksRight, rt);
        else if (id == 7 || id == 11 || id == 19 || id == 20) moveWinger(p, players, random, attacksRight, rt);
        else if (id == 9 || id == 10 || id == 21 || id == 22) moveStriker(p, players, random, attacksRight, rt);

        pullTowardsBall(p, rt);
        handleOffsideTolerance(p, players, attacksRight, rt);
        applyIdleMovement(p, random);
        avoidCrowding(p, players);
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

    private void moveCenterBack(PlayerPositionDTO cb, List<PlayerPositionDTO> players, Random random, boolean attacksRight, DemoMatchRuntime rt)
    {

        PlayerPositionDTO assignedStriker = getAssignedStriker(cb, players);

        double defensiveLineX = getDefensiveLineX(cb, attacksRight, rt);

        double targetX = defensiveLineX;
        double targetY = 48;

        if (assignedStriker != null) {

            double dist = distance(cb, assignedStriker);

            // Ako je napadač blizu → markiraj ga
            if (dist < 12) {
                targetX = cb.getX() + (assignedStriker.getX() - cb.getX()) * 0.45;
                targetY = cb.getY() + (assignedStriker.getY() - cb.getY()) * 0.45;
            }

        }

        targetX += (random.nextDouble() - 0.5) * 2;
        targetY += (random.nextDouble() - 0.5) * 4;

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
        double step = attacksRight ? 7 : -7;
        double targetX = attacksRight ? Math.min(maxX, winger.getX() + step) : Math.max(minX, winger.getX() + step);

        if (winger.getTeam().equals(rt.currentCarrier.getTeam())) {
            if (checkOffsideRisk(winger, players, attacksRight, rt)) {
                if (winger.getOffsideTicksRemaining() < 3) {
                    winger.setOffsideTicksRemaining(winger.getOffsideTicksRemaining() + 1);
                } else {
                    targetX -= step ;
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

        // 🔥 Soft rollback u protivničkih 8m
        if (attacksRight && winger.getX() > 92) targetX = 80;
        if (!attacksRight && winger.getX() < 8) targetX = 20;

        double targetY = baseY + (random.nextDouble() - 0.5) * 5;
        winger.setX(clamp(lerp(winger.getX(), targetX, smoothFactor)));
        winger.setY(clamp(lerp(winger.getY(), targetY, smoothFactor)));
    }

    private void moveStriker(PlayerPositionDTO striker, List<PlayerPositionDTO> players, Random random, boolean attacksRight, DemoMatchRuntime rt) {
        double goalX = attacksRight ? 100 : 0;
        double step = attacksRight ? 9 : -9;
        double targetX = striker.getX() + step;

        if (striker.getId() != rt.currentCarrier.getId()) {
            if (checkOffsideRisk(striker, players, attacksRight, rt)) {
                if (striker.getOffsideTicksRemaining() < 3) {
                    striker.setOffsideTicksRemaining(striker.getOffsideTicksRemaining() + 1);
                } else {
                    targetX -= step ;
                    striker.setOffsideTicksRemaining(0);
                }
            } else {
                striker.setOffsideTicksRemaining(0);
            }
        }

        // 🔥 Soft rollback u protivničkih 8m
        if (attacksRight && striker.getX() > 92) targetX = 80;
        if (!attacksRight && striker.getX() < 8) targetX = 20;

        double targetY = 48 + (random.nextDouble() - 0.5) * 12;
        striker.setX(clamp(lerp(striker.getX(), targetX, smoothFactor)));
        striker.setY(clamp(lerp(striker.getY(), targetY, smoothFactor)));
    }
    // -------- Pomoćne metode --------

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

    private double getDefensiveLineX(PlayerPositionDTO cb, boolean attacksRight, DemoMatchRuntime rt) {

        double ballX = rt.ball.getX();

        double baseLine = attacksRight ? 18 : 82;

        // Ako je lopta daleko → izlaze napred do 35m
        if (attacksRight && ballX > 55) {
            return 35;
        }

        if (!attacksRight && ballX < 45) {
            return 65;
        }

        // Ako lopta ulazi u opasnu zonu → povlače se
        if (attacksRight && ballX < 30) {
            return 14;
        }

        if (!attacksRight && ballX > 70) {
            return 86;
        }

        return baseLine;
    }
    private void pullTowardsBall(PlayerPositionDTO p, DemoMatchRuntime rt) {

        // Ako postoji nosilac lopte → nema gravitacije
        if (rt.currentCarrier != null) {
            return;
        }

        double dx = rt.ball.getX() - p.getX();
        double dy = rt.ball.getY() - p.getY();

        double distance = Math.sqrt(dx * dx + dy * dy);

        // Samo ako je lopta relativno blizu
        if (distance > 15) {
            return;
        }

        double weight;

        if (isStriker(p)) {
            weight = 0.06;
        } else if (isCenterBack(p)) {
            weight = 0.03;
        } else {
            weight = 0.08;
        }

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

                // Blaži push da ne eksplodiraju
                double pushFactor = overlap * 0.08;

                p.setX(p.getX() + (dx / distance) * pushFactor);
                p.setY(p.getY() + (dy / distance) * pushFactor);
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

        // Ofsajd samo za napadački tim
        if (!p.getTeam().equals(rt.currentCarrier.getTeam())) {
            return;
        }

        String defendingTeam = attacksRight ? "AWAY" : "HOME";

        Double offsideLine;

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


        double targetX = p.getX();
        double tolerance = 1.5;
        double retreatForce = 4;

        if (attacksRight && p.getX() > offsideLine + tolerance) {
            targetX = offsideLine - retreatForce;
        }

        if (!attacksRight && p.getX() < offsideLine - tolerance) {
            targetX = offsideLine + retreatForce;
        }

        p.setX(clamp(lerp(p.getX(), targetX, smoothFactor)));
    }

    // -------- Ostale pomoćne metode --------
    private boolean checkOffsideRisk(PlayerPositionDTO attacker, List<PlayerPositionDTO> players, boolean attacksRight, DemoMatchRuntime rt) {

        if (!attacker.getTeam().equals(rt.currentCarrier.getTeam())) {
            return false;
        }

        String defendingTeam = attacksRight ? "AWAY" : "HOME";

        Double offsideLine;

        if (attacksRight) {
            offsideLine = players.stream()
                    .filter(p -> p.getTeam().equals(defendingTeam) && !isGoalkeeper(p))
                    .map(PlayerPositionDTO::getX)
                    .max(Double::compare)
                    .orElse(100.0);

            return attacker.getX() > offsideLine;

        } else {
            offsideLine = players.stream()
                    .filter(p -> p.getTeam().equals(defendingTeam) && !isGoalkeeper(p))
                    .map(PlayerPositionDTO::getX)
                    .min(Double::compare)
                    .orElse(0.0);

            return attacker.getX() < offsideLine;
        }
    }
    private boolean isGoalkeeper(PlayerPositionDTO p) { return p.getId() == 1 || p.getId() == 12; }
    private boolean isCenterBack(PlayerPositionDTO p)
    { return p.getId() == 4 || p.getId() == 5 || p.getId() == 14 || p.getId() == 15; }
    private boolean isFullBack(PlayerPositionDTO p) { return p.getId() == 2 || p.getId() == 3 || p.getId() == 13 || p.getId() == 16; }
    private boolean isStriker(PlayerPositionDTO p) { int id = p.getId(); return (id >= 9 && id <= 11) || (id >= 21 && id <= 22); }
    private boolean isWinger(PlayerPositionDTO p) { int id = p.getId(); return (id == 7 || id == 11 || id == 19 || id == 20); }
    private boolean isAttacker(PlayerPositionDTO p) { int id = p.getId(); return (id >= 7 && id <= 11) || (id >= 19 && id <= 22); }

    private double distance(PlayerPositionDTO a, PlayerPositionDTO b) { return Math.hypot(a.getX() - b.getX(), a.getY() - b.getY()); }
    private double distance(BallPositionDTO ball, PlayerPositionDTO player) { return Math.hypot(ball.getX() - player.getX(), ball.getY() - player.getY()); }

    public double clamp(double val) { return Math.max(0, Math.min(100, val)); }
    double lerp(double start, double end, double alpha) { return start + (end - start) * alpha; }
}
