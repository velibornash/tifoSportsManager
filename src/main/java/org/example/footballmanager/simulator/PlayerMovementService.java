package org.example.footballmanager.simulator;

import org.example.footballmanager.dto.PlayerPositionDTO;
import org.example.footballmanager.service.DemoMatchRuntime;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

public class PlayerMovementService {

    public void movePlayerByRole(PlayerPositionDTO p, List<PlayerPositionDTO> players, Random random, boolean attacksRight, DemoMatchRuntime rt) {
        int id = p.getId();
        if (id == 1 || id == 12) moveGoalkeeper(p, random, attacksRight);
        else if (id == 2 || id == 13) moveFullback(p, players, random, attacksRight, true);
        else if (id == 3 || id == 16) moveFullback(p, players, random, attacksRight, false);
        else if (id == 4 || id == 5 || id == 14 || id == 15) moveCenterBack(p, players, random, attacksRight);
        else if (id == 6 || id == 8 || id == 17 || id == 18) moveCentralMidfielder(p, players, random, attacksRight, rt);
        else if (id == 7 || id == 11 || id == 19 || id == 20) moveWinger(p, players, random, attacksRight);
        else if (id == 9 || id == 10 || id == 21 || id == 22) moveStriker(p, players, random, attacksRight);

        pullTowardsBall(p, random, rt);
        avoidCrowding(p, players, random);
        applyIdleMovement(p, random);
        handleOffsideTolerance(p, players, attacksRight, rt);
    }
    // -------- Metode po pozicijama --------
    private void moveGoalkeeper(PlayerPositionDTO gk, Random random, boolean attacksRight) {
        double goalX = attacksRight ? 6 : 94;
        gk.setX(clamp(goalX + (random.nextDouble() - 0.5) * 5));
        gk.setY(clamp(48 + (random.nextDouble() - 0.5) * 12));
    }
    private void moveFullback(PlayerPositionDTO fb, List<PlayerPositionDTO> players, Random random, boolean attacksRight, boolean isRightBack) {
        double baseX = attacksRight ? 20 : 80;
        double baseY = isRightBack ? 30 : 66;
        fb.setX(clamp(baseX + (random.nextDouble() - 0.5) * 8));
        fb.setY(clamp(baseY + (random.nextDouble() - 0.5) * 8));
    }
    private void moveCenterBack(PlayerPositionDTO cb, List<PlayerPositionDTO> players, Random random, boolean attacksRight) {
        double baseX = attacksRight ? 15 : 85;
        cb.setX(clamp(baseX + (random.nextDouble() - 0.5) * 6));
        cb.setY(clamp(48 + (random.nextDouble() - 0.5) * 12));
    }
    private void moveCentralMidfielder(PlayerPositionDTO cm, List<PlayerPositionDTO> players, Random random, boolean attacksRight, DemoMatchRuntime rt) {
        double baseX = attacksRight ? 45 : 55;
        double toBallX = (rt.ball.getX() - cm.getX()) * 0.12;
        double toBallY = (rt.ball.getY() - cm.getY()) * 0.12;
        cm.setX(clamp(baseX + (random.nextDouble() - 0.5) * 10 + toBallX));
        cm.setY(clamp(50 + (random.nextDouble() - 0.5) * 12 + toBallY));
    }
    private void moveWinger(PlayerPositionDTO winger, List<PlayerPositionDTO> players, Random random, boolean attacksRight) {
        double baseX = attacksRight ? 60 : 40;
        double baseY = winger.getId() % 2 == 0 ? 20 : 76;
        winger.setX(clamp(baseX + (random.nextDouble() - 0.5) * 10));
        winger.setY(clamp(baseY + (random.nextDouble() - 0.5) * 10));
    }
    private void moveStriker(PlayerPositionDTO striker, List<PlayerPositionDTO> players, Random random, boolean attacksRight) {
        double baseX = attacksRight ? 80 : 20;
        striker.setX(clamp(baseX + (random.nextDouble() - 0.5) * 8));
        striker.setY(clamp(48 + (random.nextDouble() - 0.5) * 12));
    }

    // -------- Pomoćne metode --------
    private void pullTowardsBall(PlayerPositionDTO p, Random random, DemoMatchRuntime rt) {
        if (p.getId() == rt.currentCarrier.getId()) return;
        double toBallX = (rt.ball.getX() - p.getX()) * 0.13;
        double toBallY = (rt.ball.getY() - p.getY()) * 0.15;
        p.setX(clamp(p.getX() + toBallX));
        p.setY(clamp(p.getY() + toBallY));
    }
    private void avoidCrowding(PlayerPositionDTO p, List<PlayerPositionDTO> players, Random random) {
        for (PlayerPositionDTO other : players) {
            if (other.getId() == p.getId()) continue;
            double dx = other.getX() - p.getX();
            double dy = other.getY() - p.getY();
            double dist = Math.sqrt(dx * dx + dy * dy);
            if (dist < 3) {
                p.setX(clamp(p.getX() - dx * 0.3));
                p.setY(clamp(p.getY() - dy * 0.3));
            }
        }
    }
    private void applyIdleMovement(PlayerPositionDTO p, Random random) {
        p.setY(clamp(p.getY() + (random.nextDouble() - 0.5) * 1.3));
    }
    private void handleOffsideTolerance(PlayerPositionDTO p, List<PlayerPositionDTO> players, boolean attacksRight, DemoMatchRuntime rt) {
        double offsideLine = players.stream()
                .filter(pl -> (attacksRight ? pl.getX() > 50 : pl.getX() < 50) && pl.getId() != p.getId())
                .map(PlayerPositionDTO::getX)
                .min(Comparator.naturalOrder())
                .orElse(50.0);
        if (attacksRight && p.getX() > offsideLine + 2) p.setX(clamp(offsideLine + 2));
        if (!attacksRight && p.getX() < offsideLine - 2) p.setX(clamp(offsideLine - 2));
    }
    public double clamp(double val) {
        return Math.max(0, Math.min(100, val));
    }
}
