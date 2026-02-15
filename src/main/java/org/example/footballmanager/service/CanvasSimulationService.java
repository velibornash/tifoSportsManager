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
    private ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

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
// Ako postoji stari scheduler i još nije ugašen → ugasi ga
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();           // odmah prekida sve tekuće zadatke
            try {
                scheduler.awaitTermination(5, TimeUnit.SECONDS);  // čekaj do 5 sekundi da se lepo završi
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // OBAVEZNO: kreiraj NOVI scheduler
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
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
    private void moveFullback(PlayerPositionDTO fb,
                              List<PlayerPositionDTO> players,
                              Random random,
                              boolean attacksRight,
                              boolean isRightBack) {

        double x = fb.getX();
        double y = fb.getY();

        // =========================================
        // 1️⃣ OGRANIČENJE DUBINE (0% - 60%)
        // =========================================
        double minX = attacksRight ? 0 : 40;
        double maxX = attacksRight ? 60 : 100;

        // =========================================
        // 2️⃣ ŠIRINA TERENA (0–15 / 85–100)
        // =========================================
        double minY, maxY;

        if (isRightBack) {
            minY = 85;
            maxY = 100;
            y += (100 - y) * 0.07; // blago ka liniji
        } else {
            minY = 0;
            maxY = 15;
            y += (0 - y) * 0.07;
        }

        // random drift
        y += (random.nextDouble() - 0.5) * 1.0;

        // =========================================
        // 3️⃣ PRAĆENJE PROTIVNIKA U RADIJUSU 15m
        // =========================================
        PlayerPositionDTO nearbyOpponent = players.stream()
                .filter(p -> !p.getTeam().equals(fb.getTeam()))
                .filter(p -> distance(fb, p) < 15)
                .min(Comparator.comparingDouble(p -> distance(fb, p)))
                .orElse(null);

        double dx = 0;
        double dy = 0;

        if (nearbyOpponent != null) {
            // agresivno ka protivniku
            dx = nearbyOpponent.getX() - x;
            dy = nearbyOpponent.getY() - y;
        } else {
            // guranje napred
            dx = attacksRight ? 1.8 : -1.8;
        }

        // =========================================
        // 4️⃣ U ZADNJIH 25m MOGU UNUTRA
        // =========================================
        double goalDistance = attacksRight ? (100 - x) : x;

        if (goalDistance <= 25) {
            dy += (50 - y) * 0.25; // ulazak unutra
        }

        double dist = Math.hypot(dx, dy);
        if (dist > 0.3) {

            double speed = 0.95 + random.nextDouble() * 0.35;

            double newX = x + (dx / dist) * speed;
            double newY = y + (dy / dist) * speed;

            // clamp dubine
            newX = Math.max(minX, Math.min(maxX, newX));

            double cbLine = players.stream()
                    .filter(p -> p.getTeam().equals(fb.getTeam()))
                    .filter(p -> isCenterBack(p))
                    .mapToDouble(PlayerPositionDTO::getX)
                    .average()
                    .orElse(fb.getX());

            if (attacksRight) {
                newX = Math.min(newX, cbLine + 3);
            } else {
                newX = Math.max(newX, cbLine - 3);
            }


            // clamp širine
            newY = Math.max(minY, Math.min(maxY, newY));

            fb.setX(clamp(newX));
            fb.setY(clamp(newY));
        }

        // =========================================
        // 5️⃣ DISTANCA OD SVOG KRILA (DUPLA)
        // =========================================
        PlayerPositionDTO winger = players.stream()
                .filter(p -> p.getTeam().equals(fb.getTeam()))
                .filter(p -> {
                    if (isRightBack)
                        return p.getId() == 7 || p.getId() == 19;
                    else
                        return p.getId() == 11 || p.getId() == 20;
                })
                .findFirst()
                .orElse(null);

        if (winger != null) {
            double distToWinger = distance(fb, winger);

            if (distToWinger < 18) { // duplo više od avoidCrowding (~9)
                double dxSep = fb.getX() - winger.getX();
                double dySep = fb.getY() - winger.getY();
                double len = Math.hypot(dxSep, dySep) + 0.001;

                fb.setX(clamp(fb.getX() + (dxSep / len) * (18 - distToWinger)));
                fb.setY(clamp(fb.getY() + (dySep / len) * (18 - distToWinger)));
            }
        }
    }

    // ================== ŠTOPERI ==================
    private void moveCenterBack(PlayerPositionDTO cb,
                                List<PlayerPositionDTO> players,
                                Random random,
                                boolean attacksRight)
                                 {

        double x = cb.getX();
        double y = cb.getY();

        // =========================================
        // 1️⃣ OGRANIČENJE DUBINE
        // =========================================
        double minX = attacksRight ? 0 : 50;
        double maxX = attacksRight ? 50 : 100;

        // ne prelaze centar
        if (attacksRight) {
            maxX = 50;
        } else {
            minX = 50;
        }

        // =========================================
        // 2️⃣ CENTRALNA ŠIRINA (NE IDU ŠIROKO)
        // =========================================
        double minY = 35;
        double maxY = 65;

        // =========================================
        // 3️⃣ RAZMAK IZMEĐU ŠTOPERA (VEĆI)
        // =========================================
        PlayerPositionDTO otherCB = players.stream()
                .filter(p -> p.getTeam().equals(cb.getTeam()))
                .filter(p -> p.getId() != cb.getId())
                .filter(p -> isCenterBack(p))
                .findFirst()
                .orElse(null);

        if (otherCB != null) {
            double dist = distance(cb, otherCB);

            if (dist < 14) { // malo veći razmak nego ranije
                double dx = cb.getX() - otherCB.getX();
                double dy = cb.getY() - otherCB.getY();
                double len = Math.hypot(dx, dy) + 0.001;

                cb.setX(clamp(cb.getX() + (dx / len) * 1.2));
                cb.setY(clamp(cb.getY() + (dy / len) * 1.2));
            }
        }

        // =========================================
        // 4️⃣ OFFSIDE LINE LOGIKA SA BEKOVIMA
        // =========================================
        double defensiveLineBase = attacksRight ? 25 : 75;

        // naizmenično istrčavanje (svakih par tickova)
        if (random.nextDouble() < 0.15) {
            defensiveLineBase += attacksRight ? 8 : -8; // istrče 8m
        }

        double dxLine = defensiveLineBase - x;

        // =========================================
        // 5️⃣ PRAĆENJE NAJBLIŽEG NAPADAČA
        // =========================================
        PlayerPositionDTO nearestOpponent = players.stream()
                .filter(p -> !p.getTeam().equals(cb.getTeam()))
                .min(Comparator.comparingDouble(p -> distance(cb, p)))
                .orElse(null);

        double dy = 0;

        if (nearestOpponent != null && distance(cb, nearestOpponent) < 18) {
            dy = nearestOpponent.getY() - y;
        }

        double dist = Math.hypot(dxLine, dy);

        if (dist > 0.3) {

            double speed = 0.8 + random.nextDouble() * 0.3;

            double newX = x + (dxLine / dist) * speed;
            double newY = y + (dy / dist) * speed;

            newX = Math.max(minX, Math.min(maxX, newX));
            newY = Math.max(minY, Math.min(maxY, newY));

            cb.setX(clamp(newX));
            cb.setY(clamp(newY));
        }
    }

    // ================== CENTRALNI VEZNI ==================
    private void moveCentralMidfielder(PlayerPositionDTO cm, List<PlayerPositionDTO> players, Random random, boolean attacksRight) {
        int id = cm.getId();
        boolean isDMC = (id == 6 || id == 16);  // DMC "policajci"
        boolean isMC = (id == 8 || id == 18);   // MC/AMC

        double minX, maxX;

        // Postavljamo zone kretanja
        if (isDMC) {
            minX = 16;      // od naših 16m
            maxX = 70;      // do protivničkih 30m
        } else {
            minX = 25;      // od naših 25m
            maxX = 90;      // do protivničkih 10m
        }

        // ===============================
        // 1️⃣ Provera najbliža 3 protivnička igrača
        // ===============================
        List<PlayerPositionDTO> nearestOpponents = players.stream()
                .filter(p -> !p.getTeam().equals(cm.getTeam()))
                .sorted(Comparator.comparingDouble(p -> distance(cm, p)))
                .limit(3)
                .toList();

        // Proveri da li neko od njih ima loptu
        Optional<PlayerPositionDTO> withBall = nearestOpponents.stream()
                .filter(p -> p.getId() == getPlayerWithBall(players))
                .findFirst();

        double dx = 0, dy = 0;

        if (withBall.isPresent() && isDMC) {
            // Agresivni pritisak DMC ka igraču sa loptom
            dx = (withBall.get().getX() - cm.getX()) * 0.5;
            dy = (withBall.get().getY() - cm.getY()) * 0.5;
        } else if (isMC) {
            // MC/AMC težnja ka sredini + spremni za pas u prostor
            dx = ((minX + maxX) / 2 - cm.getX()) * 0.25;
            dy = (50 - cm.getY()) * 0.25;
        } else if (isDMC) {
            // DMC bez lopte → naginju ka sredini svoje zone
            dx = ((minX + maxX) / 2 - cm.getX()) * 0.2;
            dy = (50 - cm.getY()) * 0.15;
        }

        // ===============================
        // 2️⃣ Pas u prostor (MC/AMC)
        // ===============================
        if (isMC && currentCarrier.getTeam().equals(cm.getTeam())) {
            // Nađi najbližeg napadača ili spica
            PlayerPositionDTO targetAttacker = players.stream()
                    .filter(p -> p.getTeam().equals(cm.getTeam()) && (isStriker(p) || isWinger(p)))
                    .min(Comparator.comparingDouble(p -> distance(cm, p)))
                    .orElse(null);

            if (targetAttacker != null) {
                // Pas u prostor: X malo ispred napadača, Y po širini
                double spaceX = attacksRight ? targetAttacker.getX() - 1 : targetAttacker.getX() + 1;
                double spaceY = targetAttacker.getY();

                // Proveri da li je slobodno malo ispred
                boolean freeSpace = players.stream()
                        .filter(p -> !p.getTeam().equals(cm.getTeam()))
                        .noneMatch(p -> Math.hypot(p.getX() - spaceX, p.getY() - spaceY) < 3.0);

                if (freeSpace) {
                    dx += (spaceX - cm.getX()) * 0.15;
                    dy += (spaceY - cm.getY()) * 0.15;
                }
            }
        }

        // ===============================
        // 3️⃣ Šut u zoni šuta
        // ===============================
        double goalX = attacksRight ? 100 : 0;
        double distToGoal = Math.abs(cm.getX() - goalX);

        if (isMC && distToGoal <= 28) { // zona šuta
            if (random.nextDouble() < 0.25) { // 25% šansa da puca
                initiateShot(cm, players, random, attacksRight);
            }
        }

        // ===============================
        // 4️⃣ Clamp X/Y unutar zone
        // ===============================
        double newX = clamp(cm.getX() + dx);
        double newY = clamp(cm.getY() + dy);

        newX = Math.max(minX, Math.min(maxX, newX));

        cm.setX(newX);
        cm.setY(newY);
    }

    // ===============================
// Helper metode
// ===============================
    private int getPlayerWithBall(List<PlayerPositionDTO> players) {
        return currentCarrier != null ? currentCarrier.getId() : -1;
    }

    private boolean isStriker(PlayerPositionDTO p) {
        int id = p.getId();
        return (id >= 9 && id <= 11) || (id >= 21 && id <= 22);
    }

    private boolean isWinger(PlayerPositionDTO p) {
        int id = p.getId();
        return (id == 7 || id == 11 || id == 19 || id == 20);
    }

// ================== KRILA ==================
    private void moveWinger(PlayerPositionDTO winger, List<PlayerPositionDTO> players, Random random, boolean attacksRight) {

        double offsideLine = findOffsideLine(players, attacksRight);

        // Dozvoljeno malo preko ofsajd linije (isto kao špicevi)
        double maxForward = attacksRight
                ? offsideLine + 3
                : offsideLine - 3;

        double x = winger.getX();
        double y = winger.getY();

        // Odredi da li je levi ili desni winger
        boolean isRightSide = (winger.getId() == 7 || winger.getId() == 19);
        // 7 i 19 tretiramo kao desna krila (možeš promeniti ako želiš obrnutu logiku)

    // ===============================
    // 1️⃣ OGRANIČENJE ŠIRINE (15%) + BLAGO VUČENJE KA LINJI
    // ===============================
        double minY, maxY;

        if (isRightSide) {
            minY = 85;
            maxY = 100;

            // blago vučenje ka aut liniji (100)
            y += (100 - y) * 0.08;

        } else {
            minY = 0;
            maxY = 15;

            // blago vučenje ka aut liniji (0)
            y += (0 - y) * 0.08;
        }

// dozvoli prirodni random drift unutar zone
        y += (random.nextDouble() - 0.5) * 1.2;

// clamp samo na granice zone
        y = Math.max(minY, Math.min(maxY, y));


        // ===============================
        // 2️⃣ NAPRED UZ LINIJU
        // ===============================
        double dxForward = attacksRight ? 2.4 : -2.4;

        // ===============================
        // 3️⃣ KA GOLU TEK U ZADNJIH 25m
        // ===============================
        double goalDistance = attacksRight ? (100 - x) : x;

        double dyTowardsGoal = 0;

        if (goalDistance <= 25) {
            // sme da seče ka sredini
            dyTowardsGoal = (50 - y) * 0.25;
        }

        double newX = x + dxForward;
        double newY = y + dyTowardsGoal;

        // ===============================
        // 4️⃣ OFSAJD LIMIT
        // ===============================
        if (attacksRight) {
            newX = Math.min(newX, maxForward);
        } else {
            newX = Math.max(newX, maxForward);
        }

        winger.setX(clamp(newX));
        winger.setY(clamp(newY));
    }

// ================== NAPADAČI ==================
    private void moveStriker(PlayerPositionDTO striker, List<PlayerPositionDTO> players, Random random, boolean attacksRight) {

        double offsideLine = findOffsideLine(players, attacksRight);

        // Dozvoljeno 2-3 ticka iza ofsajd linije
        double maxForward = attacksRight
                ? offsideLine + 3
                : offsideLine - 3;

        // Ako su previše izašli, lagano ih vraćaj
        if (attacksRight && striker.getX() > offsideLine + 6) {
            striker.setX(clamp(striker.getX() - 0.8));
            return;
        }
        if (!attacksRight && striker.getX() < offsideLine - 6) {
            striker.setX(clamp(striker.getX() + 0.8));
            return;
        }

        double baseTargetX = attacksRight
                ? 85 + random.nextDouble() * 15
                : 15 - random.nextDouble() * 15;

        double baseTargetY = 32 + random.nextDouble() * 36;

        double dx = baseTargetX - striker.getX();
        double dy = baseTargetY - striker.getY();

        // Jače konstantno vučenje napred (agresivnije nego pre)
        dx += attacksRight ? 3.2 : -3.2;

        double dist = Math.hypot(dx, dy);
        if (dist > 0.4) {

            double speed = 1.35 + random.nextDouble() * 0.55;

            double newX = striker.getX() + (dx / dist) * speed;
            double newY = striker.getY() + (dy / dist) * speed;

            // Ograničenje na ofsajd + 3
            if (attacksRight) {
                newX = Math.min(newX, maxForward);
            } else {
                newX = Math.max(newX, maxForward);
            }

            striker.setX(clamp(newX));
            striker.setY(clamp(newY));
        }
    }


    private boolean isCenterBack(PlayerPositionDTO p) {
        return p.getId() == 4 || p.getId() == 5
                || p.getId() == 16 || p.getId() == 17;
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

        // Zona šuta - povećana na 32 jedinica + verovatnoća raste kako se približava
        if (distToGoal <= 32) {
            double shotProbability;

            if (distToGoal <= 18) {           // unutar ~18m → vrlo velika šansa
                shotProbability = 0.88;
            } else if (distToGoal <= 22) {    // <22 m → dobra šansa
                shotProbability = 0.75;
            } else if (distToGoal <= 27) {    // 22-27m → prosečna šansa
                shotProbability = 0.45;
            } else {                          // >27m → mala šansa (dugi šut)
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
            if (streak > 3) {
                double retreatSpeed = 1.5 + random.nextDouble() * 0.5;
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