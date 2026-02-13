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
    private int spacePassCooldown = 0;

    public void startCanvasTestSimulation() {

        Random random = new Random();
        List<PlayerPositionDTO> players = new ArrayList<>();

        for (int i = 1; i <= 11; i++) players.add(new PlayerPositionDTO(i, "HOME", 10 + random.nextDouble() * 35, 10 + random.nextDouble() * 80));
        for (int i = 12; i <= 22; i++) players.add(new PlayerPositionDTO(i, "AWAY", 65 + random.nextDouble() * 30, 10 + random.nextDouble() * 80));

        ball = new BallPositionDTO(50, 50);
        currentCarrier = players.getFirst();

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

            spacePassCooldown++;
            if (spacePassCooldown > 7 && random.nextDouble() < 0.16) {
                trySpacePass(currentCarrier, players, random);
                spacePassCooldown = 0;
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

        avoidCrowding(p, players, random);
        applyIdleMovement(p, random);
    }

    // ================== GOLMANI ==================
    private void moveGoalkeeper(PlayerPositionDTO gk, Random random, boolean attacksRight) {
        double goalX = attacksRight ? 6 : 94;
        gk.setX(clamp(goalX + (random.nextDouble() - 0.5) * 5));
        gk.setY(clamp(48 + (random.nextDouble() - 0.5) * 12));
    }

    // ================== BEKOVI ==================
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
            double speed = 0.75 + random.nextDouble() * 0.35;
            double newX = fb.getX() + (dx / dist) * speed;
            double newY = fb.getY() + (dy / dist) * speed;

            newY = Math.max(preferredY - 18, Math.min(preferredY + 18, newY));
            newX = Math.max(minX, Math.min(maxX, newX));

            fb.setX(clamp(newX));
            fb.setY(clamp(newY));
        }
    }

    // ================== ŠTOPERI ==================
    private void moveCenterBack(PlayerPositionDTO cb, List<PlayerPositionDTO> players, Random random, boolean attacksRight) {
        double minX = attacksRight ? 18 : 62;
        double maxX = attacksRight ? 38 : 82;
        double preferredY = (cb.getId() == 4 || cb.getId() == 14) ? 42 : 58;

        PlayerPositionDTO target = findTargetOpponent(cb, players, random, attacksRight);

        double dx = target != null ? target.getX() - cb.getX() : (attacksRight ? 6 : -6);
        double dy = preferredY - cb.getY();

        double dist = Math.hypot(dx, dy);
        if (dist > 0.4) {
            double speed = 0.7 + random.nextDouble() * 0.3;
            double newX = cb.getX() + (dx / dist) * speed;
            double newY = cb.getY() + (dy / dist) * speed;

            newX = Math.max(minX, Math.min(maxX, newX));

            cb.setX(clamp(newX));
            cb.setY(clamp(newY));
        }
    }

    // ================== CENTRALNI VEZNI ==================
    private void moveCentralMidfielder(PlayerPositionDTO cm, List<PlayerPositionDTO> players, Random random, boolean attacksRight) {
        PlayerPositionDTO target = findTargetOpponent(cm, players, random, attacksRight);

        double dx = target != null ? target.getX() - cm.getX() : (attacksRight ? 10 : -10);
        double dy = (50 - cm.getY()) * 0.75;

        double dist = Math.hypot(dx, dy);
        if (dist > 0.4) {
            double speed = 0.95 + random.nextDouble() * 0.45;
            double newX = cm.getX() + (dx / dist) * speed;
            newX = Math.max(attacksRight ? 38 : 52, Math.min(attacksRight ? 78 : 92, newX));

            cm.setX(clamp(newX));
            cm.setY(clamp(cm.getY() + (dy / dist) * speed));
        }
    }

    // ================== KRILA (energično) ==================
    private void moveWinger(PlayerPositionDTO winger, List<PlayerPositionDTO> players, Random random, boolean attacksRight) {
        double offsideLine = findOffsideLine(players, attacksRight);
        double maxForward = attacksRight ? offsideLine + 12 + random.nextDouble() * 10 : offsideLine - 12 - random.nextDouble() * 10;

        if (attacksRight && winger.getX() > offsideLine + 15) {
            winger.setX(clamp(winger.getX() - 0.6));
            return;
        }
        if (!attacksRight && winger.getX() < offsideLine - 15) {
            winger.setX(clamp(winger.getX() + 0.6));
            return;
        }

        PlayerPositionDTO target = findTargetOpponent(winger, players, random, attacksRight);

        double dx = target != null ? target.getX() - winger.getX() : (attacksRight ? 20 : -20);
        double dy = target != null ? target.getY() - winger.getY() : (50 - winger.getY()) * 0.4;

        // KONSTANTNO VUČENJE NAPRED
        dx += attacksRight ? 1.7 : -1.7;

        double dist = Math.hypot(dx, dy);
        if (dist > 0.4) {
            double speed = 1.25 + random.nextDouble() * 0.55;
            double newX = winger.getX() + (dx / dist) * speed;
            newX = attacksRight ? Math.min(newX, maxForward) : Math.max(newX, maxForward);

            winger.setX(clamp(newX));
            winger.setY(clamp(winger.getY() + (dy / dist) * speed));
        }
    }

    // ================== NAPADAČI (glavni napadački pokretač) ==================
    private void moveStriker(PlayerPositionDTO striker,
                             List<PlayerPositionDTO> players,
                             Random random,
                             boolean attacksRight) {

        // GOAL target
        double goalX = attacksRight ? 100 : 0;
        double goalY = 50;

        // konstantno vučenje ka golu
        double forwardPush = attacksRight ? 1.8 : -1.8;

        double dx = (goalX - striker.getX()) + forwardPush;
        double dy = (goalY - striker.getY()) * 0.6;

        double dist = Math.hypot(dx, dy);

        if (dist > 0.01) {

            double speed = 1.2 + random.nextDouble() * 0.4;

            double newX = striker.getX() + (dx / dist) * speed;
            double newY = striker.getY() + (dy / dist) * speed;

            striker.setX(clamp(newX));
            striker.setY(clamp(newY));
        }
    }

    // =============================================
    // PAS U PROSTOR + ŠUT
    // =============================================
    private void trySpacePass(PlayerPositionDTO carrier, List<PlayerPositionDTO> players, Random random) {
        boolean attacksRight = carrier.getTeam().equals("HOME");
        double spaceX = attacksRight ? 82 + random.nextDouble() * 14 : 18 - random.nextDouble() * 14;
        double spaceY = 28 + random.nextDouble() * 44;

        PlayerPositionDTO receiver = players.stream()
                .filter(p -> p.getTeam().equals(carrier.getTeam()) && p.getId() != carrier.getId())
                .min(Comparator.comparingDouble(p -> Math.hypot(p.getX() - spaceX, p.getY() - spaceY)))
                .orElse(null);

        if (receiver != null) {
            currentCarrier = receiver;
            ball.setX(clamp(spaceX));
            ball.setY(clamp(spaceY));
        }
    }

    private void tryFinishAttack(PlayerPositionDTO carrier,
                                 List<PlayerPositionDTO> players,
                                 Random random) {

        if (carrier == null) return;

        boolean attacksRight = carrier.getTeam().equals("HOME");

        double goalX = attacksRight ? 100 : 0;
        double goalY = 50;

        double distanceToGoal = Math.abs(goalX - carrier.getX());

        // zona šuta
        double shootZone = 22;

        if (distanceToGoal < shootZone) {

            // što je bliže golu → veća šansa
            double shootChance = 0.25 + (shootZone - distanceToGoal) / shootZone * 0.5;

            if (random.nextDouble() < shootChance) {

                // ----- SMOOTH KRETANJE LOPTE KA GOLU -----
                double dx = goalX - carrier.getX();
                double dy = goalY - carrier.getY();
                double dist = Math.hypot(dx, dy);

                if (dist > 0.01) {
                    double shotSpeed = 4.0;

                    ball.setX(clamp(carrier.getX() + (dx / dist) * shotSpeed));
                    ball.setY(clamp(carrier.getY() + (dy / dist) * shotSpeed));
                }

                // kada lopta pređe 98% terena → posed dobija protivnik
                if ((attacksRight && ball.getX() > 98) ||
                        (!attacksRight && ball.getX() < 2)) {

                    PlayerPositionDTO newCarrier = players.stream()
                            .filter(p -> !p.getTeam().equals(carrier.getTeam()))
                            .min(Comparator.comparingDouble(p -> distance(ball, p)))
                            .orElse(null);

                    currentCarrier = newCarrier;
                }

                return;
            }
        }

        // ako je previše blizu aut linije → vrati pas
        double nearGoalLine = attacksRight ? 96 : 4;

        if ((attacksRight && carrier.getX() > nearGoalLine) ||
                (!attacksRight && carrier.getX() < nearGoalLine)) {

            if (random.nextDouble() < 0.45) {
                PlayerPositionDTO back = findBackwardTeammate(carrier, players);
                if (back != null) {
                    currentCarrier = back;
                }
            }
        }
    }

    // =============================================
    // OSTALO
    // =============================================

    private void pullTowardsBall(PlayerPositionDTO p, Random random) {
        double toBallX = (ball.getX() - p.getX()) * 0.22;
        double toBallY = (ball.getY() - p.getY()) * 0.26;
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
        // samo blago vertikalno pomeranje
        p.setY(clamp(p.getY() + (random.nextDouble() - 0.5) * 0.6));
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
        if (candidates.size() == 1) return candidates.getFirst();

        PlayerPositionDTO mostDangerous = candidates.stream()
                .max(Comparator.comparingDouble(p -> attacksRight ? p.getX() : (100 - p.getX())))
                .orElse(candidates.getFirst());

        return random.nextDouble() < 0.68 ? mostDangerous : candidates.get(random.nextInt(candidates.size()));
    }

    private PlayerPositionDTO findNearbyTeammate(PlayerPositionDTO carrier, List<PlayerPositionDTO> players) {
        List<PlayerPositionDTO> candidates = players.stream()
                .filter(p -> p.getId() != carrier.getId() && p.getTeam().equals(carrier.getTeam()))
                .sorted(Comparator.comparingDouble(p -> distance(carrier, p)))
                .limit(4)
                .toList();

        if (candidates.isEmpty()) return null;
        return candidates.get(new Random().nextInt(candidates.size()));
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
    private double distance(BallPositionDTO ball, PlayerPositionDTO player) {
        return Math.hypot(ball.getX() - player.getX(), ball.getY() - player.getY());
    }
    private double clamp(double val) {
        return Math.max(0, Math.min(100, val));
    }
}