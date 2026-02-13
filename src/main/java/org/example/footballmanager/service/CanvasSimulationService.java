package org.example.footballmanager.service;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.dto.*;
import org.example.footballmanager.util.MatchEventWebSocketHandler;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CanvasSimulationService {

    private final MatchEventWebSocketHandler webSocketHandler;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private static final int TICK_MS = 250;
    private static final int MATCH_DURATION_SECONDS = 90;

    private PlayerPositionDTO currentCarrier;
    private BallPositionDTO ball;
    private int possessionTicks = 0;

    public void startCanvasTestSimulation() {

        Random random = new Random();
        List<PlayerPositionDTO> players = new ArrayList<>();

        for (int i = 1; i <= 11; i++) players.add(new PlayerPositionDTO(i, "HOME", 10 + random.nextDouble() * 35, 10 + random.nextDouble() * 80));
        for (int i = 12; i <= 22; i++) players.add(new PlayerPositionDTO(i, "AWAY", 65 + random.nextDouble() * 30, 10 + random.nextDouble() * 80));

        ball = new BallPositionDTO(50, 50);
        currentCarrier = players.get(0);

        final int[] tick = {0};
        final int totalTicks = MATCH_DURATION_SECONDS * (1000 / TICK_MS);

        scheduler.scheduleAtFixedRate(() -> {

            if (tick[0] >= totalTicks) { scheduler.shutdown(); return; }

            for (PlayerPositionDTO p : players) {
                boolean attacksRight = p.getTeam().equals("HOME");
                movePlayerByRole(p, players, random, attacksRight);
            }

            possessionTicks++;
            if (possessionTicks > 8) {
                PlayerPositionDTO next = findNearbyTeammate(currentCarrier, players);
                if (next != null) currentCarrier = next;
                possessionTicks = 0;
            }

            tryFinishAttack(currentCarrier, players, random);

            ball.setX(currentCarrier.getX());
            ball.setY(currentCarrier.getY());

            GameStateDTO state = new GameStateDTO(tick[0] / (1000 / TICK_MS), new ArrayList<>(players), ball);
            webSocketHandler.broadcastEvent(state);
            tick[0]++;

        }, 0, TICK_MS, TimeUnit.MILLISECONDS);
    }

    // =============================================
    private void movePlayerByRole(PlayerPositionDTO p, List<PlayerPositionDTO> players,
                                  Random random, boolean attacksRight) {

        int id = p.getId();

        if (id == 1 || id == 12) moveGoalkeeper(p, random, attacksRight);
        else if (id == 2 || id == 13) moveFullback(p, players, random, attacksRight, true);
        else if (id == 3 || id == 16) moveFullback(p, players, random, attacksRight, false);
        else if (id == 4 || id == 5 || id == 14 || id == 15) moveCenterBack(p, players, random, attacksRight);
        else if (id == 6 || id == 8 || id == 17 || id == 18) moveCentralMidfielder(p, players, random, attacksRight);
        else if (id == 7 || id == 11 || id == 19 || id == 20) moveWinger(p, players, random, attacksRight);
        else if (id == 9 || id == 10 || id == 21 || id == 22) moveStriker(p, players, random, attacksRight);

        avoidCrowding(p, players, random);      // ← jača razdaljina
        applyIdleMovement(p, random);           // ← stalno kretanje
    }

    // ================== GOLMANI ==================
    private void moveGoalkeeper(PlayerPositionDTO gk, Random random, boolean attacksRight) {
        double goalX = attacksRight ? 6 : 94;
        gk.setX(clamp(goalX + (random.nextDouble() - 0.5) * 6));
        gk.setY(clamp(48 + (random.nextDouble() - 0.5) * 14));
    }

    // ================== BEKOVI ==================
    private void moveFullback(PlayerPositionDTO fb, List<PlayerPositionDTO> players, Random random,
                              boolean attacksRight, boolean isRightBack) {
        double minX = attacksRight ? 12 : 62;
        double maxX = attacksRight ? 58 : 100;
        double preferredY = isRightBack ? 88 : 12;

        PlayerPositionDTO target = findTargetOpponent(fb, players, random, attacksRight);

        double dx = target != null ? target.getX() - fb.getX() : (attacksRight ? 6 : -6);
        double dy = preferredY - fb.getY();

        if (target != null) {
            double targetDy = target.getY() - fb.getY();
            if (Math.abs(target.getY() - preferredY) < 30) dy = targetDy * 0.55 + dy * 0.45;
        }

        double dist = Math.hypot(dx, dy);
        if (dist > 0.6) {
            double speed = 0.95 + random.nextDouble() * 0.5;
            double newX = fb.getX() + (dx / dist) * speed;
            double newY = fb.getY() + (dy / dist) * speed;

            newY = Math.max(preferredY - 20, Math.min(preferredY + 20, newY));
            newX = Math.max(minX, Math.min(maxX, newX));

            fb.setX(clamp(newX));
            fb.setY(clamp(newY));
        }
    }

    // ================== ŠTOPERI (razdvojeni) ==================
    private void moveCenterBack(PlayerPositionDTO cb, List<PlayerPositionDTO> players, Random random, boolean attacksRight) {
        double minX = attacksRight ? 18 : 62;
        double maxX = attacksRight ? 38 : 82;

        // Levi štoper malo levo, desni malo desno
        double preferredY = (cb.getId() == 4 || cb.getId() == 14) ? 42 : 58;

        PlayerPositionDTO target = findTargetOpponent(cb, players, random, attacksRight);

        double dx = target != null ? target.getX() - cb.getX() : (attacksRight ? 6 : -6);
        double dy = preferredY - cb.getY();

        double dist = Math.hypot(dx, dy);
        if (dist > 0.6) {
            double speed = 0.75 + random.nextDouble() * 0.4;
            double newX = cb.getX() + (dx / dist) * speed;
            double newY = cb.getY() + (dy / dist) * speed;

            newX = Math.max(minX, Math.min(maxX, newX));

            cb.setX(clamp(newX));
            cb.setY(clamp(newY));
        }
    }

    // ================== CENTRALNI VEZNI ==================
    private void moveCentralMidfielder(PlayerPositionDTO cm, List<PlayerPositionDTO> players, Random random, boolean attacksRight) {
        double minX = attacksRight ? 38 : 52;
        double maxX = attacksRight ? 78 : 92;

        PlayerPositionDTO target = findTargetOpponent(cm, players, random, attacksRight);

        double dx = target != null ? target.getX() - cm.getX() : (attacksRight ? 10 : -10);
        double dy = (50 - cm.getY()) * 0.8;

        double dist = Math.hypot(dx, dy);
        if (dist > 0.6) {
            double speed = 1.05 + random.nextDouble() * 0.6;
            double newX = cm.getX() + (dx / dist) * speed;
            newX = Math.max(minX, Math.min(maxX, newX));

            cm.setX(clamp(newX));
            cm.setY(clamp(cm.getY() + (dy / dist) * speed));
        }
    }

    // ================== KRILA ==================
    private void moveWinger(PlayerPositionDTO winger, List<PlayerPositionDTO> players, Random random, boolean attacksRight) {
        PlayerPositionDTO target = findTargetOpponent(winger, players, random, attacksRight);

        double dx = target != null ? target.getX() - winger.getX() : (attacksRight ? 15 : -15);
        double dy = target != null ? target.getY() - winger.getY() : (50 - winger.getY()) * 0.5;

        double dist = Math.hypot(dx, dy);
        if (dist > 0.6) {
            double speed = 1.3 + random.nextDouble() * 0.7;
            winger.setX(clamp(winger.getX() + (dx / dist) * speed));
            winger.setY(clamp(winger.getY() + (dy / dist) * speed));
        }
    }

    // ================== NAPADAČI (polako napred) ==================
    private void moveStriker(PlayerPositionDTO striker, List<PlayerPositionDTO> players, Random random, boolean attacksRight) {
        double offsideLine = findOffsideLine(players, attacksRight);
        double maxForward = attacksRight ? offsideLine + 8 + random.nextDouble() * 12 : offsideLine - 8 - random.nextDouble() * 12;

        double baseTargetX = attacksRight ? 62 + random.nextDouble() * 38 : 38 - random.nextDouble() * 38;
        double baseTargetY = 32 + random.nextDouble() * 36;

        // nearby threat
        PlayerPositionDTO nearbyThreat = null;
        double threatDist = Double.MAX_VALUE;
        String opponentTeam = attacksRight ? "AWAY" : "HOME";

        for (PlayerPositionDTO p : players) {
            if (!p.getTeam().equals(opponentTeam)) continue;
            double d = distance(striker, p);
            boolean isDangerous = currentCarrier.equals(p) || (attacksRight ? p.getX() > 74 : p.getX() < 26);
            if (isDangerous && d < threatDist && d < 21) {
                threatDist = d;
                nearbyThreat = p;
            }
        }

        double targetX = nearbyThreat != null ? nearbyThreat.getX() + (random.nextDouble() - 0.5) * 8 : baseTargetX;
        double targetY = nearbyThreat != null ? nearbyThreat.getY() + (random.nextDouble() - 0.5) * 8 : baseTargetY;

        // POLAKO NAPRED kad nema pretnje
        if (nearbyThreat == null) {
            targetX += attacksRight ? 0.9 : -0.9;
        }

        double dx = targetX - striker.getX();
        double dy = targetY - striker.getY();
        double dist = Math.hypot(dx, dy);

        if (dist > 0.6) {
            double speed = 1.35 + random.nextDouble() * 0.75;
            double newX = striker.getX() + (dx / dist) * speed;
            newX = attacksRight ? Math.min(newX, maxForward) : Math.max(newX, maxForward);

            striker.setX(clamp(newX));
            striker.setY(clamp(striker.getY() + (dy / dist) * speed));
        }
    }

    // =============================================
    // POMOĆNE FUNKCIJE
    // =============================================

    private double findOffsideLine(List<PlayerPositionDTO> players, boolean attacksRight) {
        return players.stream()
                .filter(p -> p.getTeam().equals(attacksRight ? "AWAY" : "HOME"))
                .mapToDouble(PlayerPositionDTO::getX)
                .reduce(attacksRight ? 0 : 100, attacksRight ? Math::max : Math::min);
    }

    private void avoidCrowding(PlayerPositionDTO p, List<PlayerPositionDTO> players, Random random) {
        for (PlayerPositionDTO other : players) {
            if (other.getId() == p.getId() || !other.getTeam().equals(p.getTeam())) continue;

            double dist = distance(p, other);
            if (dist < 9.5) {                                 // ← povećano
                double dx = p.getX() - other.getX();
                double dy = p.getY() - other.getY();
                double len = Math.hypot(dx, dy) + 0.001;

                p.setX(clamp(p.getX() + (dx / len) * (10.0 - dist)));   // jače odguravanje
                p.setY(clamp(p.getY() + (dy / len) * (10.0 - dist)));
            }
        }
    }

    private void applyIdleMovement(PlayerPositionDTO p, Random random) {
        p.setY(clamp(p.getY() + (random.nextDouble() - 0.5) * 1.6));   // jače gore-dole
        p.setX(clamp(p.getX() + (50 - p.getX()) * 0.09));             // lagano ka centru
    }

    private PlayerPositionDTO findTargetOpponent(PlayerPositionDTO player, List<PlayerPositionDTO> allPlayers,
                                                 Random random, boolean attacksRight) {
        String opponentTeam = attacksRight ? "AWAY" : "HOME";
        List<PlayerPositionDTO> candidates = allPlayers.stream()
                .filter(p -> p.getTeam().equals(opponentTeam))
                .sorted(Comparator.comparingDouble(p -> distance(player, p)))
                .limit(3)
                .collect(Collectors.toList());

        if (candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0);

        PlayerPositionDTO mostDangerous = candidates.stream()
                .max(Comparator.comparingDouble(p -> attacksRight ? p.getX() : (100 - p.getX())))
                .orElse(candidates.get(0));

        return random.nextDouble() < 0.68 ? mostDangerous : candidates.get(random.nextInt(candidates.size()));
    }

    private PlayerPositionDTO findNearbyTeammate(PlayerPositionDTO carrier, List<PlayerPositionDTO> players) {
        List<PlayerPositionDTO> candidates = players.stream()
                .filter(p -> p.getId() != carrier.getId() && p.getTeam().equals(carrier.getTeam()))
                .sorted(Comparator.comparingDouble(p -> distance(carrier, p)))
                .limit(4)
                .collect(Collectors.toList());

        if (candidates.isEmpty()) return null;
        return candidates.get(new Random().nextInt(candidates.size()));
    }

    private void tryFinishAttack(PlayerPositionDTO carrier, List<PlayerPositionDTO> players, Random random) {
        boolean attacksRight = carrier.getTeam().equals("HOME");
        double goalLine = attacksRight ? 92 : 8;

        if (Math.abs(carrier.getX() - goalLine) < 14) {
            if (random.nextDouble() < 0.42) {
                PlayerPositionDTO back = findBackwardTeammate(carrier, players);
                if (back != null) currentCarrier = back;
            }
        }
    }

    private PlayerPositionDTO findBackwardTeammate(PlayerPositionDTO carrier, List<PlayerPositionDTO> players) {
        boolean attacksRight = carrier.getTeam().equals("HOME");
        double backX = attacksRight ? carrier.getX() - 28 : carrier.getX() + 28;

        return players.stream()
                .filter(p -> p.getTeam().equals(carrier.getTeam()) && p.getId() != carrier.getId())
                .filter(p -> Math.abs(p.getX() - backX) < 35)
                .min(Comparator.comparingDouble(p -> distance(carrier, p)))
                .orElse(null);
    }

    private double distance(PlayerPositionDTO a, PlayerPositionDTO b) {
        return Math.hypot(a.getX() - b.getX(), a.getY() - b.getY());
    }

    private double clamp(double val) {
        return Math.max(0, Math.min(100, val));
    }
}