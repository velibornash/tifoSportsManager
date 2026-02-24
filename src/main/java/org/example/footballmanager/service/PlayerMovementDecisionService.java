package org.example.footballmanager.service;

import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.dto.BallPositionDTO;
import org.example.footballmanager.dto.PlayerPositionDTO;
import org.example.footballmanager.util.MatchRuntime;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Service
@Slf4j
public class PlayerMovementDecisionService {

    double smoothFactor = 0.25; // 0.0 = ne pomera se, 1.0 = teleport
    private static final double MIN_MOVEMENT_THRESHOLD = 0.5; // Prag za "minimalno pomeranje" (sabiraj apsolutne razlike X+Y)
    private static final int MAX_RETREAT_TICKS = 8; // Broj tick-ova za duboko povlačenje (2-3s)
    private static final double DEEP_RETREAT_FORCE = 25.0; // Duboko povlačenje (do ~30m nazad)
    private static final double RETREAT_FORCE = 12.0; // Povećano sa 4 na 12 za brže izvlačenje
    private static final double ATTACKER_PULL_WEIGHT = 0.05; // Blagi pull ka carrier-u za napadače (čak i kad lopta nije free)
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
    public PlayerPositionDTO findDangerousOpponent( List<PlayerPositionDTO> homePlayers, List<PlayerPositionDTO> awayPlayers, boolean attacksRight) {
        // minDistanceToTeammate = npr. 5.0 m
        double x;
        List<PlayerPositionDTO> resultPlayersList = new ArrayList<>();
        List<PlayerPositionDTO> otherPlayersList = new ArrayList<>();
        if(attacksRight)
        {
            x=0.00;
            resultPlayersList=awayPlayers;
            otherPlayersList=homePlayers;
        }
        else {
            x = 100.0;
            resultPlayersList=homePlayers;
            otherPlayersList=awayPlayers;
        }
        List<PlayerPositionDTO> finalOtherPlayersList = otherPlayersList;
        List<PlayerPositionDTO> candidates = resultPlayersList.stream()
                .filter(p -> distance(p, Objects.requireNonNull(findNearbyPlayers(p, finalOtherPlayersList, 1)).getFirst())>5)
                .sorted(Comparator.comparingDouble(p -> Math.abs(distance(p.getX(),x))))
                .toList();
        return candidates.getFirst();
    }
    /**
     * Proverava da li je putanja lopte ka saigraču "čista"
     * 1. nema protivnika u radijusu 3m oko primaoca
     * 2. nema protivnika na liniji između pasa i primaoca (sa malim toleransom)
     */
    public boolean isPassClear(PlayerPositionDTO passer, PlayerPositionDTO receiver, double clearRadius, List<PlayerPositionDTO> opponentPlayers) {
        final double PATH_TOLERANCE = 2.0;      // koliko metara sa strane linije računamo kao "na putu"

        // 1. Provera zone oko primaoca
        boolean receiverMarked = opponentPlayers.stream()
                .anyMatch(opp -> distance(receiver, opp) <= clearRadius);

        if (receiverMarked) {
            return false;
        }

        // 2. Provera linije pasa
        for (PlayerPositionDTO opp : opponentPlayers) {
            if (isPointCloseToLineSegment(passer, receiver, opp, PATH_TOLERANCE)) {
                return false;
            }
        }

        return true;
    }
    // Pomoćna funkcija - da li je tačka blizu linijskog segmenta
    private boolean isPointCloseToLineSegment(PlayerPositionDTO a, PlayerPositionDTO b, PlayerPositionDTO p, double maxDistance) {

        double lengthSquared = distanceSquared(a, b);
        if (lengthSquared == 0) return distance(a, p) <= maxDistance;

        // projekcija
        double t = Math.max(0, Math.min(1,
                ((p.getX() - a.getX()) * (b.getX() - a.getX()) + (p.getY() - a.getY()) * (b.getY() - a.getY())) / lengthSquared));

        double projX = a.getX() + t * (b.getX() - a.getX());
        double projY = a.getY() + t * (b.getY() - a.getY());

        return distance(p, new PlayerPositionDTO(0,null, projX, projY,0,0)) <= maxDistance;
    }
    private double distanceSquared(PlayerPositionDTO a, PlayerPositionDTO b) {
        double dx = a.getX() - b.getY();
        double dy = a.getY() - b.getY();
        return dx*dx + dy*dy;
    }
    public void movePlayerByRole(PlayerPositionDTO p, List<PlayerPositionDTO> players, Random random, boolean attacksRight, MatchRuntime rt) {
        int id = p.getId();
        // Log samo za specifične igrače: HOME 7 (krilo), 9 (napadač); AWAY 20 (krilo), 22 (napadač)
        boolean shouldLog = false; //= id == 8 || id == 16 || id == 2 || id == 13;

        if (shouldLog) {
            // Početni log: Broj igrača, početna pozicija, offside line, current carrier ID, offsideTicks, retreatTicks
            double offsideLine = calculateOffsideLine(players, attacksRight);  // Nova helper metoda za offside line
            int carrierId = (rt.currentCarrier != null) ? rt.currentCarrier.getId() : -1;
            log.info("Igrač {} ({}): Početak - X={}, Y={}, offsideLine={}, currentCarrierId={}, offTicks={}, retreatTicks={}",
                    p.getId(), p.getTeam(), p.getX(), p.getY(), offsideLine, carrierId, p.getOffsideTicksRemaining(), p.getRetreatTicksRemaining());

        }

        // Sačuvaj stare pozicije za proveru pomeranja
        double oldX = p.getX();
        double oldY = p.getY();

        boolean pulledToBall = false;
        // 1. Ako lopta free (nema carrier-a) → pullTowardsBall
        if (rt.currentCarrier == null) {
            pullTowardsBall(p, rt);
            pulledToBall = true;
            if (shouldLog) {
                log.info("Igrač {}: Ušao u pullTowardsBall (lopta free), nova X={}, Y={}", p.getId(), p.getX(), p.getY());
            }
        } else if (isAttacker(p)) {
            // Dodat: Blagi pull ka carrier-u za napadače (čak i kad lopta nije free)
            pullTowardsCarrier(p, rt);
            if (shouldLog) {
                log.info("Igrač {}: Blagi pull ka carrier-u, nova X={}, Y={}", p.getId(), p.getX(), p.getY());
            }
        }

        // 2. handleOffsideTolerance – ako se aktivira (menja targetX), preskoči moveByRole i idle
        boolean offsidesActivated = applyOffsideTolerance(p, players, attacksRight, rt);
        if (offsidesActivated && shouldLog) {
            log.info("Igrač {}: Offside aktiviran, nova X={}, Y={}", p.getId(), p.getX(), p.getY());
        }

        if (!offsidesActivated) {
            // 3. Ako nije ofsajd → moveByRole
            applyRoleMovement(p, players, random, attacksRight, rt);
            if (shouldLog) {
                log.info("Igrač {}: Primena role movement-a, nova X={}, Y={}", p.getId(), p.getX(), p.getY());
            }

            // 4. Proveri da li se pomerio posle role movement-a
            double movementDelta = Math.abs(p.getX() - oldX) + Math.abs(p.getY() - oldY);
            if (movementDelta < MIN_MOVEMENT_THRESHOLD) {
                applyIdleMovement(p, random);
                if (shouldLog) {
                    log.info("Igrač {}: Ušao u applyIdleMovement (mali delta={}), nova X={}, Y={}", p.getId(), movementDelta, p.getX(), p.getY());
                }
            }
        }

        // 5. Uvek na kraju → avoidCrowding
        avoidCrowding(p, players);
        if (shouldLog) {
            log.info("Igrač {}: Primena avoidCrowding, konačna X={}, Y={}", p.getId(), p.getX(), p.getY());
        }

        if (shouldLog) {
            // Krajnji log: Konačna pozicija, ukupna delta
            double totalDelta = Math.abs(p.getX() - oldX) + Math.abs(p.getY() - oldY);
            log.info("Igrač {}: Kraj - Ukupna delta={}, pulledToBall={}, offActivated={}, offTicks={}, retreatTicks={}",
                    p.getId(), totalDelta, pulledToBall, offsidesActivated, p.getOffsideTicksRemaining(), p.getRetreatTicksRemaining());
        }
    }
    private double calculateOffsideLine(List<PlayerPositionDTO> players, boolean attacksRight) {
        String defendingTeam = attacksRight ? "AWAY" : "HOME";
        double offsideLine = 0.0;
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
        log.info("Offside linija:{}", offsideLine);
        return offsideLine;
    }
    private void pullTowardsCarrier(PlayerPositionDTO p, MatchRuntime rt) {
        if (rt.currentCarrier == null) return;

        double dx = rt.currentCarrier.getX() - p.getX();
        double dy = rt.currentCarrier.getY() - p.getY();
        double distance = Math.hypot(dx, dy);

        if (distance > 40) return; // preslab efekat na daljinu >40m

        double dynamicWeight = ATTACKER_PULL_WEIGHT * (1.0 + (40 - distance) / 40.0); // 0.05 → do ~0.1 na 0m
        dynamicWeight = Math.min(0.12, dynamicWeight);

        p.setX(p.getX() + dx * dynamicWeight);
        p.setY(p.getY() + dy * dynamicWeight);
    }
    // IZMENJENO: handleOffsideTolerance sa deep retreat-om
    private void handleOffsideTolerance(PlayerPositionDTO p, List<PlayerPositionDTO> players, boolean attacksRight, MatchRuntime rt) {
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
        double tolerance = 1.0;

        boolean isInOffside = attacksRight ? (p.getX() > offsideLine + tolerance) : (p.getX() < offsideLine - tolerance);

        if (isInOffside) {
            if (p.getOffsideTicksRemaining() >= 2) { // ranije počinje deep retreat
                if (p.getRetreatTicksRemaining() < MAX_RETREAT_TICKS) {
                    p.setRetreatTicksRemaining(p.getRetreatTicksRemaining() + 1);
                }
                double progress = p.getRetreatTicksRemaining() / (double) MAX_RETREAT_TICKS;
                double effectiveForce = RETREAT_FORCE + DEEP_RETREAT_FORCE * progress;
                targetX = attacksRight ? (offsideLine - effectiveForce) : (offsideLine + effectiveForce);
            } else {
                // Normalni retreat jači na početku
                targetX = attacksRight ? (offsideLine - 15) : (offsideLine + 15); // bilo 12
            }
            p.setOffsideTicksRemaining(p.getOffsideTicksRemaining() + 1);
        } else {
            p.setOffsideTicksRemaining(0);
            p.setRetreatTicksRemaining(0);
        }

        p.setX(clamp(lerp(p.getX(), targetX, smoothFactor)));
    }
    // IZMENJENO: checkOffsideRisk (ostaje isto, ali sad se koristi samo u role metodama za inicijalni brojač)
    private boolean checkOffsideRisk(PlayerPositionDTO attacker, List<PlayerPositionDTO> players, boolean attacksRight, MatchRuntime rt) {
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
    // NOVO: Aplikovati ofsajd i vratiti da li se aktivirao (da li je promenio targetX)
    private boolean applyOffsideTolerance(PlayerPositionDTO p, List<PlayerPositionDTO> players, boolean attacksRight, MatchRuntime rt) {
        double oldTargetX = p.getX();  // Koristi trenutni X kao proxy za target (pre pomeranja)
        handleOffsideTolerance(p, players, attacksRight, rt);
        return Math.abs(p.getX() - oldTargetX) > 0.01;  // Ako se pomerio, znači ofsajd aktiviran
    }
    // NOVO: Aplikovati role movement bez uslova (izdvojeno iz glavnog if-a)
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
    // -------- Metode po pozicijama (ostaju iste, osim izmena u winger i striker) --------
    private void moveGoalkeeper(PlayerPositionDTO gk, Random random, boolean attacksRight) {
        double goalX = attacksRight ? 6 : 94;
        double targetX = goalX + (random.nextDouble() - 0.5) * 5;
        double targetY = 48 + (random.nextDouble() - 0.5) * 12;
        gk.setX(clamp(lerp(gk.getX(), targetX, smoothFactor)));
        gk.setY(clamp(lerp(gk.getY(), targetY, smoothFactor)));
    }
    private void moveFullback(PlayerPositionDTO fb, List<PlayerPositionDTO> players, Random random, boolean attacksRight, boolean isRightBack, MatchRuntime rt) {
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
    private void moveCenterBack(PlayerPositionDTO cb, List<PlayerPositionDTO> players, Random random, boolean attacksRight, MatchRuntime rt) {

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
        double minX = attacksRight ? 30 : 20;  // Smanjeno sa 30 na 20 za AWAY
        double maxX = 100;
        double step = attacksRight ? 5 + (random.nextDouble() - 0.5) * 4 : -5 + (random.nextDouble() - 0.5) * 4;  // Random ±2
        double targetX = attacksRight ? Math.min(maxX, winger.getX() + step) : Math.max(minX, winger.getX() + step);

        if (winger.getTeam().equals(rt.currentCarrier.getTeam())) {
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

        // Soft rollback u protivničkih 8m
        if (attacksRight && winger.getX() > 92) targetX = 80;
        if (!attacksRight && winger.getX() < 8) targetX = 20;

        double targetY = baseY + (random.nextDouble() - 0.5) * 5;
        winger.setX(clamp(lerp(winger.getX(), targetX, smoothFactor)));
        winger.setY(clamp(lerp(winger.getY(), targetY, smoothFactor)));
    }
    // IZMENJENO: moveStriker sa random u step (±2)
    private void moveStriker(PlayerPositionDTO striker, List<PlayerPositionDTO> players, Random random, boolean attacksRight, MatchRuntime rt) {
        double goalX = attacksRight ? 100 : 0;

        double distToBall = distance(rt.ball, striker);
        double baseStep = attacksRight ? 6.0 : -6.0;          // smanjeno sa 9 na 6
        double stepMultiplier = Math.min(1.2, Math.max(0.3, (80 - distToBall) / 60.0)); // bliže lopti → veći korak

        double randomPart = (random.nextDouble() - 0.5) * 3.0; // ±1.5 umesto ±2
        double step = baseStep * stepMultiplier + randomPart;

        double targetX = striker.getX() + step;

        if (striker.getId() != rt.currentCarrier.getId()) {
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

        // Soft rollback u protivničkih 8m
        if (attacksRight && striker.getX() > 92) targetX = 75;
        if (!attacksRight && striker.getX() < 8) targetX = 25;

// Dodatno ograničenje: ne sme ići previše ispred carrier-a ili lopte
        if (rt.currentCarrier != null && rt.currentCarrier.getTeam().equals(striker.getTeam())) {
            double carrierX = rt.currentCarrier.getX();
            double maxAllowed = attacksRight ? carrierX + 18 : carrierX - 18;
            targetX = attacksRight ? Math.min(targetX, maxAllowed) : Math.max(targetX, maxAllowed);
        }

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
    // IZMENJENO: getDefensiveLineX sa random off trap-om (±10 sa ver 0.3)
    private double getDefensiveLineX(PlayerPositionDTO cb, boolean attacksRight, MatchRuntime rt) {
        double ballX = rt.ball.getX();
        double baseLine = attacksRight ? 18 : 82;
        Random rand = new Random();

        // Ako lopta daleko → izlaze napred do 35m, sa šansom za trap (±10)
        if (attacksRight && ballX > 55) {
            double line = 35;
            if (rand.nextDouble() < 0.3) line += (rand.nextDouble() - 0.5) * 20;  // ±10
            return line;
        }

        if (!attacksRight && ballX < 45) {
            double line = 65;
            if (rand.nextDouble() < 0.3) line -= (rand.nextDouble() - 0.5) * 20;  // ±10 (manji X za AWAY)
            return line;
        }

        // Ako lopta blizu → povlače se
        if (attacksRight && ballX < 30) {
            return 14;
        }

        if (!attacksRight && ballX > 70) {
            return 86;
        }

        return baseLine;
    }
    private void pullTowardsBall(PlayerPositionDTO p, MatchRuntime rt) {

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
    // IZMENJENO: applyIdleMovement sa random X (±1.5–3)
    private void applyIdleMovement(PlayerPositionDTO p, Random random) {
        double rangeY = 1.3;
        if (isWinger(p) || isFullBack(p)) rangeY = 0.6;
        else if (isStriker(p)) rangeY = 1.0;

        double rangeX = random.nextDouble() * 1.5 + 1.5;  // ±1.5–3

        double targetX = p.getX() + (random.nextDouble() - 0.5) * rangeX;
        double targetY = p.getY() + (random.nextDouble() - 0.5) * rangeY;

        p.setX(clamp(lerp(p.getX(), targetX, smoothFactor)));
        p.setY(clamp(lerp(p.getY(), targetY, smoothFactor)));
    }
    // -------- Ostale pomoćne metode --------
    private boolean isGoalkeeper(PlayerPositionDTO p) { return p.getId() == 1 || p.getId() == 12; }
    private boolean isCenterBack(PlayerPositionDTO p)
    { return p.getId() == 4 || p.getId() == 5 || p.getId() == 14 || p.getId() == 15; }
    private boolean isFullBack(PlayerPositionDTO p) { return p.getId() == 2 || p.getId() == 3 || p.getId() == 13 || p.getId() == 16; }
    private boolean isStriker(PlayerPositionDTO p) { int id = p.getId(); return (id >= 9 && id <= 10) || (id >= 21 && id <= 22); }  // Ispravka: samo strikers 9,10,21,22
    private boolean isWinger(PlayerPositionDTO p) { int id = p.getId(); return (id == 7 || id == 11 || id == 19 || id == 20); }
    private boolean isAttacker(PlayerPositionDTO p) { int id = p.getId(); return (id >= 7 && id <= 11) || (id >= 19 && id <= 22); }
    private double distance(PlayerPositionDTO a, PlayerPositionDTO b) { return Math.hypot(a.getX() - b.getX(), a.getY() - b.getY()); }
    private double distance(BallPositionDTO ball, PlayerPositionDTO player) { return Math.hypot(ball.getX() - player.getX(), ball.getY() - player.getY()); }
    private double distance(double x, double x1) {return x1-x;}
    public double clamp(double val) { return Math.max(0, Math.min(100, val)); }
    double lerp(double start, double end, double alpha) { return start + (end - start) * alpha; }
    // =============================================
    // ODLUKA NOSIOCA LOPTE
    // =============================================
    public PlayerPositionDTO chooseNextAction(PlayerPositionDTO carrier, List<PlayerPositionDTO> players, Random random, MatchRuntime rt) {
        boolean attacksRight = carrier.getTeam().equals("HOME");
        // Izračunaj distancu do gola
        double goalX = attacksRight ? 100 : 0;
        double distToGoal = Math.abs(carrier.getX() - goalX);

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
                initiateShot(carrier, players, random, attacksRight, rt);
                return carrier;  // nosilac ostaje isti dok se izvrši šut
            }
        }
        // Ostalo isto: pas u prostor ili običan pas
        if (random.nextDouble() < 0.48) {
            PlayerPositionDTO target = trySpacePassTarget(carrier, players, random, attacksRight);
            if (target != null) return target;
        }

        return Objects.requireNonNull(findNearbyPlayers(carrier, players, 4)).get(random.nextInt(4));
    }
    // Obrada kretanja lopte tokom šuta
    public void handleShotMovement(Random random, MatchRuntime rt) {
        rt.shotTicks++;
        double progress = (double) rt.shotTicks / rt.maxShotTicks;
        if (progress >= 1) {
            progress = 1;
        }

        // Interpoliraj poziciju lopte ka cilju
        rt.ball.setX(clamp(rt.ball.getX() + (rt.targetBallX - rt.ball.getX()) * progress));
        rt.ball.setY(clamp(rt.ball.getY() + (rt.targetBallY - rt.ball.getY()) * progress));

        if (rt.shotTicks >= rt.maxShotTicks) {
            rt.isShooting = false;
            // Odluči ishod šuta
            double shotOutcome = random.nextDouble();
            if (shotOutcome < 0.20) {
                // Gol: lopta prelazi gol-liniju
                // System.out.println("GOL!");
                rt.ball.setX(rt.targetBallX);
                rt.ball.setY(rt.targetBallY);
                initiateRebound(random, rt);

            } else if (shotOutcome < 0.70) {
                // Odbrana: pokreni odbijanje lopte
                //System.out.println("Odbijena lopta!");
                initiateRebound(random, rt);
            } else {
                // Promasaj: lopta ide izvan gola
                //System.out.println("Promasaj!");
                double missX = rt.attacksRightDuringShot ? 100 + 5 : -5;  // Izvan terena
                double missY = rt.targetBallY + (random.nextDouble() - 0.5) * 30;
                rt.ball.setX(clamp(missX));
                rt.ball.setY(clamp(missY));
                initiateRebound(random, rt);

            }
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
    public void handleReboundMovement(List<PlayerPositionDTO> players, Random random, MatchRuntime rt) {
        rt.reboundTicks++;
        double progress = (double) rt.reboundTicks / rt.maxReboundTicks;
        if (progress >= 1) {
            progress = 1;
        }

        // Interpoliraj poziciju lopte ka cilju odbijanja
        rt.ball.setX(clamp(rt.ball.getX() + (rt.targetBallX - rt.ball.getX()) * progress));
        rt.ball.setY(clamp(rt.ball.getY() + (rt.targetBallY - rt.ball.getY()) * progress));

        if (rt.reboundTicks >= rt.maxReboundTicks) {
            rt.isRebounding = false;
            // Lopta je slobodna, najbliži igrač (bilo koji tim) je uzima
            rt.currentCarrier = players.stream()
                    .min(Comparator.comparingDouble(p -> distance(rt.ball, p)))
                    .orElse(rt.currentCarrier);
        }
    }
    private void initiateRebound(Random random, MatchRuntime rt) {
        // Odbijanje ka sredini terena, random pozicija blizu igrača broj 6 ili slučajna
        rt.targetBallX = 50 + (random.nextDouble() - 0.5) * 20;  // Oko sredine po X
        rt.targetBallY = 50 + (random.nextDouble() - 0.5) * 20;  // Oko sredine po Y, sa varijacijom

        rt.isRebounding = true;
        rt.reboundTicks = 0;
    }
    public void trySpacePass(PlayerPositionDTO carrier, List<PlayerPositionDTO> players, Random random, MatchRuntime rt) {
        boolean attacksRight = carrier.getTeam().equals("HOME");
        double spaceX = attacksRight ? 85 + random.nextDouble() * 12 : 15 - random.nextDouble() * 12;
        double spaceY = 20 + random.nextDouble() * 60;

        PlayerPositionDTO receiver = players.stream()
                .filter(p -> p.getTeam().equals(carrier.getTeam()) && p.getId() != carrier.getId())
                .min(Comparator.comparingDouble(p -> Math.hypot(p.getX() - spaceX, p.getY() - spaceY)))
                .orElse(null);

        if (receiver != null) {
            rt.currentCarrier = receiver;
            rt.ball.setX(clamp(spaceX));
            rt. ball.setY(clamp(spaceY));
        }
    }
    private void initiateShot(PlayerPositionDTO shooter, List<PlayerPositionDTO> players, Random random, boolean attacksRight, MatchRuntime rt) {
        rt.attacksRightDuringShot = attacksRight;
        double goalX = attacksRight ? 100 : 0;
        double distToGoal = Math.abs(shooter.getX() - goalX);
        //System.out.println("Šut sa distance: " + String.format("%.1f", distToGoal));

        // Cilj ka koordinatama golmana odbrane
        PlayerPositionDTO opponentGk = players.stream()
                .filter(p -> p.getTeam().equals(attacksRight ? "AWAY" : "HOME") && isGoalkeeper(p))
                .findFirst()
                .orElse(null);

        if (opponentGk != null) {
            rt.targetBallX = opponentGk.getX();
            rt.targetBallY = opponentGk.getY();
        } else {
            rt.targetBallX = goalX;
            rt.targetBallY = 50 + (random.nextDouble() - 0.5) * 20;  // Fallback na centar gola
        }

        rt.isShooting = true;
        rt.shotTicks = 0;
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
        if (rt.isShooting || rt.isRebounding) {
            return;
        }

        rt.possessionTicks++;
        if (rt.possessionTicks > 6 + random.nextInt(9)) {
            PlayerPositionDTO next = chooseNextAction(rt.currentCarrier, rt.players, random, rt);
            if (next != null) rt.currentCarrier = next;
            rt.possessionTicks = 0;
        }

        rt.spacePassCooldown++;
        if (rt.spacePassCooldown > 8 && random.nextDouble() < 0.17) {
            trySpacePass(rt.currentCarrier, rt.players, random, rt);
            rt.spacePassCooldown = 0;
        }
    }
    public void updateBallPosition(MatchRuntime rt) {
        if (rt.isShooting) {
            handleShotMovement(random, rt);
        } else if (rt.isRebounding) {
            handleReboundMovement(rt.players, random, rt);
        } else {
            rt.ball.setX(rt.currentCarrier.getX());
            rt.ball.setY(rt.currentCarrier.getY());
        }
    }
}