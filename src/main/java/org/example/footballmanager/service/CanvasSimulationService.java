package org.example.footballmanager.service;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.dto.*;
import org.example.footballmanager.util.MatchEventWebSocketHandler;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

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
    private int spacePassCooldown = 0;

    public void startCanvasTestSimulation() {
        Random random = new Random();
        List<PlayerPositionDTO> players = new ArrayList<>();

        // HOME team
        for (int i = 1; i <= 11; i++)
            players.add(new PlayerPositionDTO(i, "HOME", 10 + random.nextDouble() * 35, 10 + random.nextDouble() * 80));
        // AWAY team
        for (int i = 12; i <= 22; i++)
            players.add(new PlayerPositionDTO(i, "AWAY", 65 + random.nextDouble() * 30, 10 + random.nextDouble() * 80));

        ball = new BallPositionDTO(50, 50);
        currentCarrier = players.get(0);

        final int[] tick = {0};
        final int totalTicks = MATCH_DURATION_SECONDS * (1000 / TICK_MS);

        scheduler.scheduleAtFixedRate(() -> {
            if (tick[0] >= totalTicks) {
                scheduler.shutdown();
                return;
            }

            for (PlayerPositionDTO p : players) {
                boolean attacksRight = p.getTeam().equals("HOME");
                movePlayerByRole(p, players, random, attacksRight);
            }

            possessionTicks++;
            if (possessionTicks > 8) {
                tryPassToPlayer(currentCarrier, players, random);
                possessionTicks = 0;
            }

            spacePassCooldown++;
            if (spacePassCooldown > 10 && random.nextDouble() < 0.2) {
                trySpacePass(currentCarrier, players, random);
                spacePassCooldown = 0;
            }

            tryFinishAttack(currentCarrier, players, random);

            // Ball lags za nosiocem
            ball.setX(ball.getX() + (currentCarrier.getX() - ball.getX()) * 0.22);
            ball.setY(ball.getY() + (currentCarrier.getY() - ball.getY()) * 0.22);

            GameStateDTO state = new GameStateDTO(tick[0] / (1000 / TICK_MS), new ArrayList<>(players), ball);
            webSocketHandler.broadcastEvent(state);
            tick[0]++;

        }, 0, TICK_MS, TimeUnit.MILLISECONDS);
    }

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

        if ((p.getId() >= 6 && p.getId() <= 11) || (p.getId() >= 17 && p.getId() <= 22)) {
            pullTowardsBall(p, ball, random);
        }

        avoidCrowding(p, players, random);
        applyIdleMovement(p, random);
    }

    // ==================== GOLMAN ====================
    private void moveGoalkeeper(PlayerPositionDTO gk, Random random, boolean attacksRight) {
        double goalX = attacksRight ? 6 : 94;
        gk.setX(clamp(goalX + (random.nextDouble() - 0.5) * 5));
        gk.setY(clamp(48 + (random.nextDouble() - 0.5) * 12));
    }

    // ==================== BEKOVI ====================
    private void moveFullback(PlayerPositionDTO fb, List<PlayerPositionDTO> players, Random random,
                              boolean attacksRight, boolean isRightBack) {
        double minX = attacksRight ? 12 : 62;
        double maxX = attacksRight ? 58 : 100;
        double preferredY = isRightBack ? 88 : 12;

        PlayerPositionDTO target = findTargetOpponent(fb, players, random, attacksRight);
        double dx = target != null ? target.getX() - fb.getX() : (attacksRight ? 5 : -5);
        double dy = preferredY - fb.getY();
        double dist = Math.hypot(dx, dy);
        if (dist > 0.4) {
            double speed = 0.7 + random.nextDouble() * 0.3;
            fb.setX(clamp(fb.getX() + (dx / dist) * speed));
            fb.setY(clamp(fb.getY() + (dy / dist) * speed));
        }
    }

    // ==================== ŠTOPERI ====================
    private void moveCenterBack(PlayerPositionDTO cb, List<PlayerPositionDTO> players, Random random, boolean attacksRight) {
        double minX = attacksRight ? 18 : 62;
        double maxX = attacksRight ? 38 : 82;
        double preferredY = (cb.getId() == 4 || cb.getId() == 14) ? 42 : 58;

        PlayerPositionDTO target = findTargetOpponent(cb, players, random, attacksRight);
        double dx = target != null ? target.getX() - cb.getX() : (attacksRight ? 6 : -6);
        double dy = preferredY - cb.getY();
        double dist = Math.hypot(dx, dy);
        if (dist > 0.4) {
            double speed = 0.6 + random.nextDouble() * 0.3;
            cb.setX(clamp(cb.getX() + (dx / dist) * speed));
            cb.setY(clamp(cb.getY() + (dy / dist) * speed));
        }
    }

    // ==================== CENTRALNI VEZNI ====================
    private void moveCentralMidfielder(PlayerPositionDTO cm, List<PlayerPositionDTO> players, Random random, boolean attacksRight) {
        PlayerPositionDTO target = findTargetOpponent(cm, players, random, attacksRight);
        double dx = target != null ? target.getX() - cm.getX() : (attacksRight ? 10 : -10);
        double dy = (50 - cm.getY()) * 0.7;
        double dist = Math.hypot(dx, dy);
        if (dist > 0.4) {
            double speed = 0.8 + random.nextDouble() * 0.4;
            cm.setX(clamp(cm.getX() + (dx / dist) * speed));
            cm.setY(clamp(cm.getY() + (dy / dist) * speed));
        }
    }

    // ==================== KRILA ====================
    private void moveWinger(PlayerPositionDTO winger, List<PlayerPositionDTO> players, Random random, boolean attacksRight) {
        double offsideLine = findOffsideLine(players, attacksRight);

        // targetX povučen ka liniji odbrane + random širenje
        double targetX = attacksRight ? Math.min(winger.getX() + 1.5 + random.nextDouble(), offsideLine - 1)
                : Math.max(winger.getX() - 1.5 - random.nextDouble(), offsideLine + 1);
        double targetY = 25 + random.nextDouble() * 50;

        double dx = targetX - winger.getX();
        double dy = targetY - winger.getY();
        double dist = Math.hypot(dx, dy);
        if (dist > 0.3) {
            double speed = 1.0 + random.nextDouble() * 0.3;
            winger.setX(clamp(winger.getX() + (dx / dist) * speed));
            winger.setY(clamp(winger.getY() + (dy / dist) * speed));
        }
    }

    // ==================== NAPADAČI ====================
    private void moveStriker(PlayerPositionDTO striker, List<PlayerPositionDTO> players, Random random, boolean attacksRight) {
        double offsideLine = findOffsideLine(players, attacksRight);

        // target ka liniji odbrane
        double targetX = attacksRight ? Math.min(striker.getX() + 2 + random.nextDouble(), offsideLine - 1)
                : Math.max(striker.getX() - 2 - random.nextDouble(), offsideLine + 1);
        double targetY = 40 + random.nextDouble() * 20;

        // povremeni šut / dribling ka golu
        if (random.nextDouble() < 0.18) {
            double goalX = attacksRight ? 98 : 2;
            double goalY = 48 + (random.nextDouble()-0.5) * 15;
            targetX = goalX;
            targetY = goalY;
        }

        double dx = targetX - striker.getX();
        double dy = targetY - striker.getY();
        double dist = Math.hypot(dx, dy);
        if (dist > 0.4) {
            double speed = 1.0 + random.nextDouble() * 0.4;
            striker.setX(clamp(striker.getX() + (dx / dist) * speed));
            striker.setY(clamp(striker.getY() + (dy / dist) * speed));
        }
    }

    // ==================== PAS KA NAJBLIŽEM IGRAČU ====================
    private void tryPassToPlayer(PlayerPositionDTO carrier, List<PlayerPositionDTO> players, Random random) {
        if (carrier == null) return;

        // 80% šanse da pas bude ka najbližem (smanjeno da ne ide stalno)
        if (random.nextDouble() > 0.8) return;

        // Pronađi 4 najbliža saigrača
        List<PlayerPositionDTO> nearby = players.stream()
                .filter(p -> p.getTeam().equals(carrier.getTeam()) && p.getId() != carrier.getId())
                .sorted(Comparator.comparingDouble(p -> distance(carrier, p)))
                .limit(4)
                .toList();

        if (nearby.isEmpty()) return;

        // Biramo jednog od 4 najbliža
        PlayerPositionDTO receiver = nearby.get(random.nextInt(nearby.size()));

        // Pas ka igraču: lopta ide ka njemu
        currentCarrier = receiver;
        ball.setX(receiver.getX());
        ball.setY(receiver.getY());
    }


    // ==================== PAS U PROSTOR ====================
    private void trySpacePass(PlayerPositionDTO carrier, List<PlayerPositionDTO> players, Random random) {
        // NE radi pas ako nema igrač kod lopte
        if (carrier == null) return;

        // Pas se desi samo povremeno, smanjen broj puta
        if (random.nextDouble() > 0.07) return; // ~7 šansa

        // Pronađi 4 najbliža saigrača
        List<PlayerPositionDTO> nearby = players.stream()
                .filter(p -> p.getTeam().equals(carrier.getTeam()) && p.getId() != carrier.getId())
                .sorted(Comparator.comparingDouble(p -> distance(carrier, p)))
                .limit(4)
                .toList();

        if (nearby.isEmpty()) return;

        // Izaberi jednog od najbližih
        PlayerPositionDTO receiver = nearby.get(random.nextInt(nearby.size()));

        // Postavi lopta kod primatelja
        currentCarrier = receiver;
        ball.setX(receiver.getX());
        ball.setY(receiver.getY());
    }


    private void tryFinishAttack(PlayerPositionDTO carrier, List<PlayerPositionDTO> players, Random random) {
        boolean attacksRight = carrier.getTeam().equals("HOME");
        double dangerZone = attacksRight ? 75 : 25;

        if ((attacksRight && carrier.getX() > dangerZone) || (!attacksRight && carrier.getX() < dangerZone)) {
            long opponentsAhead = players.stream()
                    .filter(p -> p.getTeam().equals(attacksRight ? "AWAY" : "HOME"))
                    .filter(p -> attacksRight ? p.getX() > carrier.getX() : p.getX() < carrier.getX())
                    .count();

            if (opponentsAhead <= 2 && random.nextDouble() < 0.35) {
                // ŠUT ka golu
                ball.setX(attacksRight ? 98 : 2);
                ball.setY(48 + (random.nextDouble()-0.5)*18);
                currentCarrier = players.stream()
                        .filter(p -> p.getTeam().equals(attacksRight ? "AWAY" : "HOME"))
                        .min(Comparator.comparingDouble(p -> distance(ball, p)))
                        .orElse(carrier);
            }
        }
    }

    // ==================== POMOĆNE FUNKCIJE ====================
    private void pullTowardsBall(PlayerPositionDTO p, BallPositionDTO b, Random random) {
        double toBallX = (b.getX() - p.getX()) * 0.22;
        double toBallY = (b.getY() - p.getY()) * 0.22;
        p.setX(clamp(p.getX() + toBallX));
        p.setY(clamp(p.getY() + toBallY));
    }

    private void avoidCrowding(PlayerPositionDTO p, List<PlayerPositionDTO> players, Random random) {
        for (PlayerPositionDTO other : players) {
            if (other.getId() == p.getId() || !other.getTeam().equals(p.getTeam())) continue;
            double dist = distance(p, other);
            if (dist < 10.0) {
                double dx = p.getX() - other.getX();
                double dy = p.getY() - other.getY();
                double len = Math.hypot(dx, dy) + 0.001;
                p.setX(clamp(p.getX() + (dx / len) * (11.5 - dist)));
                p.setY(clamp(p.getY() + (dy / len) * (11.5 - dist)));
            }
        }
    }

    private void applyIdleMovement(PlayerPositionDTO p, Random random) {
        // stalno pomeranje gore-dole i ka centru terena
        p.setY(clamp(p.getY() + (random.nextDouble() - 0.5) * 1.2));
        p.setX(clamp(p.getX() + (50 - p.getX()) * 0.05));
    }

    private PlayerPositionDTO findNearbyTeammate(PlayerPositionDTO carrier, List<PlayerPositionDTO> players) {
        List<PlayerPositionDTO> candidates = players.stream()
                .filter(p -> p.getTeam().equals(carrier.getTeam()) && p.getId() != carrier.getId())
                .sorted(Comparator.comparingDouble(p -> distance(carrier, p)))
                .limit(4)
                .toList();

        if (candidates.isEmpty()) return null;
        return candidates.get(new Random().nextInt(candidates.size()));
    }

    private double findOffsideLine(List<PlayerPositionDTO> players, boolean attacksRight) {
        return players.stream()
                .filter(p -> p.getTeam().equals(attacksRight ? "AWAY" : "HOME"))
                .mapToDouble(PlayerPositionDTO::getX)
                .reduce(attacksRight ? 0 : 100, attacksRight ? Math::max : Math::min);
    }

    private PlayerPositionDTO findTargetOpponent(PlayerPositionDTO player, List<PlayerPositionDTO> allPlayers,
                                                 Random random, boolean attacksRight) {
        String opponentTeam = attacksRight ? "AWAY" : "HOME";
        List<PlayerPositionDTO> candidates = allPlayers.stream()
                .filter(p -> p.getTeam().equals(opponentTeam))
                .sorted(Comparator.comparingDouble(p -> distance(player, p)))
                .limit(3)
                .toList();

        if (candidates.isEmpty()) return null;
        return candidates.get(new Random().nextInt(candidates.size()));
    }

    private double distance(PlayerPositionDTO a, PlayerPositionDTO b) {
        return Math.hypot(a.getX() - b.getX(), a.getY() - b.getY());
    }

    private double distance(BallPositionDTO ball, PlayerPositionDTO player) {
        return Math.hypot(ball.getX() - player.getX(), ball.getY() - player.getY());
    }

    private double clamp(double val) {
        return Math.max(0, Math.min(100, val));
    }
}
