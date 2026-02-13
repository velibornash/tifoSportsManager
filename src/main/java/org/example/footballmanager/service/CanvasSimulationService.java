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

    // Dodato za simulaciju šuta
    private boolean isShooting = false;
    private boolean isRebounding = false;
    private double targetBallX;
    private double targetBallY;
    private int shotTicks = 0;
    private final int maxShotTicks = 4; // Broj tickova za kretanje lopte tokom šuta (npr. 1 sekunda)
    private int reboundTicks = 0;
    private final int maxReboundTicks = 3; // Broj tickova za odbijanje lopte

    private boolean attacksRightDuringShot; // Čuvaj smer napada tokom šuta

    // Brojač uzastopnih offside poseda po igraču
    private final Map<Integer, Integer> offsideStreak = new HashMap<>();

    public void startCanvasTestSimulation() {

        Random random = new Random();
        List<PlayerPositionDTO> players = new ArrayList<>();

        for (int i = 1; i <= 11; i++) {
            players.add(new PlayerPositionDTO(i, "HOME", 10 + random.nextDouble() * 35, 10 + random.nextDouble() * 80));
        }
        for (int i = 12; i <= 22; i++) {
            players.add(new PlayerPositionDTO(i, "AWAY", 65 + random.nextDouble() * 30, 10 + random.nextDouble() * 80));
        }

        ball = new BallPositionDTO(50, 50);
        currentCarrier = players.getFirst();

        final int[] tick = {0};
        final int totalTicks = MATCH_DURATION_SECONDS * (1000 / TICK_MS);

        scheduler.scheduleAtFixedRate(() -> {

            if (tick[0] >= totalTicks) {
                scheduler.shutdown();
                return;
            }

            // Kretanje svih igrača
            for (PlayerPositionDTO p : players) {
                boolean attacksRight = p.getTeam().equals("HOME");
                movePlayerByRole(p, players, random, attacksRight);
            }

            // Držanje lopte (varijabilno)
            if (!isShooting && !isRebounding) {
                possessionTicks++;
                if (possessionTicks > 6 + random.nextInt(9)) {  // 6–14 tickova
                    PlayerPositionDTO next = chooseNextAction(currentCarrier, players, random);
                    if (next != null) currentCarrier = next;
                    possessionTicks = 0;
                }

                // Pas u prostor (povremeno)
                spacePassCooldown++;
                if (spacePassCooldown > 8 && random.nextDouble() < 0.17) {
                    trySpacePass(currentCarrier, players, random);
                    spacePassCooldown = 0;
                }
            }

            // Obradi šut ako je u toku
            if (isShooting) {
                handleShotMovement(random);
            } else if (isRebounding) {
                handleReboundMovement(players, random);
            } else {
                ball.setX(currentCarrier.getX());
                ball.setY(currentCarrier.getY());
            }

            GameStateDTO state = new GameStateDTO(
                    tick[0] / (1000 / TICK_MS),
                    new ArrayList<>(players),
                    ball
            );

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

        pullTowardsBall(p, random);
        avoidCrowding(p, players, random);
        applyIdleMovement(p, random);
        handleOffsideTolerance(p, players, attacksRight);
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
            double speed = 0.65 + random.nextDouble() * 0.25;
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

        double dx = target != null ? target.getX() - cb.getX() : (attacksRight ? 5 : -5);
        double dy = preferredY - cb.getY();

        double dist = Math.hypot(dx, dy);
        if (dist > 0.4) {
            double speed = 0.65 + random.nextDouble() * 0.25;
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

        double dx = target != null ? target.getX() - cm.getX() : (attacksRight ? 8 : -8);
        double dy = (50 - cm.getY()) * 0.7;

        double dist = Math.hypot(dx, dy);
        if (dist > 0.4) {
            double speed = 0.85 + random.nextDouble() * 0.35;
            double newX = cm.getX() + (dx / dist) * speed;
            newX = Math.max(attacksRight ? 38 : 52, Math.min(attacksRight ? 78 : 92, newX));

            cm.setX(clamp(newX));
            cm.setY(clamp(cm.getY() + (dy / dist) * speed));
        }
    }

    // ================== KRILA ==================
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

        double dx = target != null ? target.getX() - winger.getX() : (attacksRight ? 18 : -18);
        double dy = target != null ? target.getY() - winger.getY() : (50 - winger.getY()) * 0.4;

        // Jače vučenje ka strani
        double sidePull = (winger.getY() > 50) ? 88 : 12;
        dy += (sidePull - winger.getY()) * 0.4;

        // Konstantno napred
        dx += attacksRight ? 1.6 : -1.6;

        double dist = Math.hypot(dx, dy);
        if (dist > 0.4) {
            double speed = 1.1 + random.nextDouble() * 0.4;
            double newX = winger.getX() + (dx / dist) * speed;
            newX = attacksRight ? Math.min(newX, maxForward) : Math.max(newX, maxForward);

            winger.setX(clamp(newX));
            winger.setY(clamp(winger.getY() + (dy / dist) * speed));
        }
    }

    // ================== NAPADAČI ==================
    private void moveStriker(PlayerPositionDTO striker, List<PlayerPositionDTO> players, Random random, boolean attacksRight) {
        double offsideLine = findOffsideLine(players, attacksRight);
        double maxForward = 80.00;

        if (attacksRight && striker.getX() > offsideLine + 15) {
            striker.setX(clamp(striker.getX() - 0.6));
            return;
        }
        if (!attacksRight && striker.getX() < offsideLine - 15) {
            striker.setX(clamp(striker.getX() + 0.6));
            return;
        }

        double baseTargetX = attacksRight ? 70 + random.nextDouble() * 30 : 30 - random.nextDouble() * 30;
        double baseTargetY = 32 + random.nextDouble() * 36;

        double dx = baseTargetX - striker.getX();
        double dy = baseTargetY - striker.getY();

        // Konstantno vučenje napred
        dx += attacksRight ? 2.0 : -2.0;

        double dist = Math.hypot(dx, dy);
        if (dist > 0.4) {
            double speed = 1.15 + random.nextDouble() * 0.45;
            double newX = striker.getX() + (dx / dist) * speed;
            newX = attacksRight ? Math.min(newX, maxForward) : Math.max(newX, maxForward);

            striker.setX(clamp(newX));
            striker.setY(clamp(striker.getY() + (dy / dist) * speed));
        }
    }

    // =============================================
    // ODLUKA NOSIOCA LOPTE
    // =============================================
    private PlayerPositionDTO chooseNextAction(PlayerPositionDTO carrier, List<PlayerPositionDTO> players, Random random) {
        boolean attacksRight = carrier.getTeam().equals("HOME");

        // Izračunaj distancu do gola
        double goalX = attacksRight ? 100 : 0;
        double distToGoal = Math.abs(carrier.getX() - goalX);
        System.out.println("Igrac:"+carrier.getId()+" udaljen od gola "+distToGoal);

        // Zona šuta - povećana na 38 jedinica + verovatnoća raste kako se približava
        if (distToGoal <= 38) {
            double shotProbability;

            if (distToGoal <= 18) {           // unutar ~18m → vrlo velika šansa
                shotProbability = 0.88;
            } else if (distToGoal <= 25) {    // 18–25m → dobra šansa
                shotProbability = 0.75;
            } else if (distToGoal <= 32) {    // 25–32m → prosečna šansa
                shotProbability = 0.45;
            } else {                          // 32–38m → mala šansa (dugi šut)
                shotProbability = 0.18;
            }

            if (random.nextDouble() < shotProbability) {
                System.out.println("ŠUT! Distanca: " + String.format("%.1f", distToGoal));
                initiateShot(carrier, players, random, attacksRight);
                return carrier;  // nosilac ostaje isti dok se izvrši šut
            }
        }

        // Ostalo isto: pas u prostor ili običan pas
        if (random.nextDouble() < 0.48) {
            PlayerPositionDTO target = trySpacePassTarget(carrier, players, random, attacksRight);
            if (target != null) return target;
        }

        return findNearbyTeammate(carrier, players);
    }

    // Nova metoda za inicijalizaciju šuta
    private void initiateShot(PlayerPositionDTO shooter, List<PlayerPositionDTO> players, Random random, boolean attacksRight) {
        attacksRightDuringShot = attacksRight;
        double goalX = attacksRight ? 100 : 0;
        double distToGoal = Math.abs(shooter.getX() - goalX);
        System.out.println("Šut sa distance: " + String.format("%.1f", distToGoal));

        // Cilj ka koordinatama golmana odbrane
        PlayerPositionDTO opponentGk = players.stream()
                .filter(p -> p.getTeam().equals(attacksRight ? "AWAY" : "HOME") && isGoalkeeper(p))
                .findFirst()
                .orElse(null);

        if (opponentGk != null) {
            targetBallX = opponentGk.getX();
            targetBallY = opponentGk.getY();
        } else {
            targetBallX = goalX;
            targetBallY = 50 + (random.nextDouble() - 0.5) * 20;  // Fallback na centar gola
        }

        isShooting = true;
        shotTicks = 0;
    }

    // Obrada kretanja lopte tokom šuta
    private void handleShotMovement(Random random) {
        shotTicks++;
        double progress = (double) shotTicks / maxShotTicks;
        if (progress >= 1) {
            progress = 1;
        }

        // Interpoliraj poziciju lopte ka cilju
        ball.setX(clamp(ball.getX() + (targetBallX - ball.getX()) * progress));
        ball.setY(clamp(ball.getY() + (targetBallY - ball.getY()) * progress));

        if (shotTicks >= maxShotTicks) {
            isShooting = false;
            // Odluči ishod šuta
            double shotOutcome = random.nextDouble();
            if (shotOutcome < 0.20) {
                // Gol: lopta prelazi gol-liniju
                System.out.println("GOL!");
                ball.setX(targetBallX);
                ball.setY(targetBallY);
                initiateRebound(random);

            } else if (shotOutcome < 0.70) {
                // Odbrana: pokreni odbijanje lopte
                System.out.println("Odbijena lopta!");
                initiateRebound(random);
            } else {
                // Promasaj: lopta ide izvan gola
                System.out.println("Promasaj!");
                double missX = attacksRightDuringShot ? 100 + 5 : -5;  // Izvan terena
                double missY = targetBallY + (random.nextDouble() - 0.5) * 30;
                ball.setX(clamp(missX));
                ball.setY(clamp(missY));
                initiateRebound(random);

            }
        }
    }

    // Nova metoda za inicijalizaciju odbijanja
    private void initiateRebound(Random random) {
        // Odbijanje ka sredini terena, random pozicija blizu igrača broj 6 ili slučajna
        targetBallX = 50 + (random.nextDouble() - 0.5) * 20;  // Oko sredine po X
        targetBallY = 50 + (random.nextDouble() - 0.5) * 20;  // Oko sredine po Y, sa varijacijom

        isRebounding = true;
        reboundTicks = 0;
    }

    // Obrada kretanja lopte tokom odbijanja
    private void handleReboundMovement(List<PlayerPositionDTO> players, Random random) {
        reboundTicks++;
        double progress = (double) reboundTicks / maxReboundTicks;
        if (progress >= 1) {
            progress = 1;
        }

        // Interpoliraj poziciju lopte ka cilju odbijanja
        ball.setX(clamp(ball.getX() + (targetBallX - ball.getX()) * progress));
        ball.setY(clamp(ball.getY() + (targetBallY - ball.getY()) * progress));

        if (reboundTicks >= maxReboundTicks) {
            isRebounding = false;
            // Lopta je slobodna, najbliži igrač (bilo koji tim) je uzima
            currentCarrier = players.stream()
                    .min(Comparator.comparingDouble(p -> distance(ball, p)))
                    .orElse(currentCarrier);
        }
    }

/*    private PlayerPositionDTO getOpponentGoalkeeper(boolean attacksRight) {
        // Pronađi golmana suparnika
        String opponentTeam = attacksRight ? "AWAY" : "HOME";
        return players.stream()
                .filter(p -> p.getTeam().equals(opponentTeam) && isGoalkeeper(p))
                .findFirst()
                .orElse(currentCarrier);  // Fallback
    }*/

    private boolean isGoalkeeper(PlayerPositionDTO p) {
        return p.getId() == 1 || p.getId() == 12;  // Pretpostavka da su golmani ID 1 i 12
    }

    private PlayerPositionDTO trySpacePassTarget(PlayerPositionDTO carrier, List<PlayerPositionDTO> players, Random random, boolean attacksRight) {
        double spaceX = attacksRight ? 85 + random.nextDouble() * 12 : 15 - random.nextDouble() * 12;
        double spaceY = 20 + random.nextDouble() * 60;

        return players.stream()
                .filter(p -> p.getTeam().equals(carrier.getTeam()) && p.getId() != carrier.getId())
                .min(Comparator.comparingDouble(p -> Math.hypot(p.getX() - spaceX, p.getY() - spaceY)))
                .orElse(null);
    }

    // =============================================
    // POMOĆNE METODE
    // =============================================

    private void pullTowardsBall(PlayerPositionDTO p, Random random) {
        if (p.getId() == currentCarrier.getId()) return;

        double toBallX = (ball.getX() - p.getX()) * 0.13;
        double toBallY = (ball.getY() - p.getY()) * 0.15;

        p.setX(clamp(p.getX() + toBallX));
        p.setY(clamp(p.getY() + toBallY));
    }

    private void avoidCrowding(PlayerPositionDTO p, List<PlayerPositionDTO> players, Random random) {
        for (PlayerPositionDTO other : players) {
            if (other.getId() == p.getId() || !other.getTeam().equals(p.getTeam())) continue;
            double dist = distance(p, other);
            if (dist < 9.8) {
                double dx = p.getX() - other.getX();
                double dy = p.getY() - other.getY();
                double len = Math.hypot(dx, dy) + 0.001;
                p.setX(clamp(p.getX() + (dx / len) * (11.5 - dist)));
                p.setY(clamp(p.getY() + (dy / len) * (11.5 - dist)));
            }
        }
    }

    private void applyIdleMovement(PlayerPositionDTO p, Random random) {
        p.setY(clamp(p.getY() + (random.nextDouble() - 0.5) * 1.3));
        //p.setX(clamp(p.getX() + (50 - p.getX()) * 0.09));
    }

    private void handleOffsideTolerance(PlayerPositionDTO p, List<PlayerPositionDTO> players, boolean attacksRight) {
        if (!isAttacker(p)) return;  // Samo za napadače (strikers i wingers)

        double offsideLine = findOffsideLine(players, attacksRight);
        boolean isOffside = attacksRight ? p.getX() > offsideLine : p.getX() < offsideLine;  // Bolja detekcija: bliži golmanu od drugog poslednjeg defanzivca

        int streak = offsideStreak.getOrDefault(p.getId(), 0);
        Random  random = new Random();
        if (isOffside) {
            streak++;
            if (streak > 1) {  // Brže vraćanje: odmah posle 1-2 ticka
                double retreatSpeed = 1.5 + random.nextDouble() * 0.5;  // Brže vraćanje
                p.setX(clamp(p.getX() + (attacksRight ? -retreatSpeed : retreatSpeed)));
            }
        } else {
            streak = 0;
        }

        offsideStreak.put(p.getId(), streak);
    }

    private boolean isAttacker(PlayerPositionDTO p) {
        int id = p.getId();
        return (id >= 7 && id <= 11) || (id >= 19 && id <= 22);  // Wingers i strikers
    }

    private double findOffsideLine(List<PlayerPositionDTO> players, boolean attacksRight) {
        // Pronađi drugog poslednjeg defanzivca (isključujući golmana)
        String defendingTeam = attacksRight ? "AWAY" : "HOME";
        List<Double> defenderXs = players.stream()
                .filter(p -> p.getTeam().equals(defendingTeam) && !isGoalkeeper(p))  // Isključi golmana
                .map(PlayerPositionDTO::getX)
                .sorted(attacksRight ? Comparator.reverseOrder() : Comparator.naturalOrder())  // Za HOME: max X drugog, za AWAY: min X drugog
                .limit(2)  // Uzmi dva poslednja
                .toList();

        if (defenderXs.size() < 2) {
            return attacksRight ? 100 : 0;  // Ako nema dovoljno, ofsajd na gol-liniji
        }

        return defenderXs.get(1);  // Drugi poslednji defanzivac određuje liniju
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

    private void trySpacePass(PlayerPositionDTO carrier, List<PlayerPositionDTO> players, Random random) {
        boolean attacksRight = carrier.getTeam().equals("HOME");
        double spaceX = attacksRight ? 85 + random.nextDouble() * 12 : 15 - random.nextDouble() * 12;
        double spaceY = 20 + random.nextDouble() * 60;

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