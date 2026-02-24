package org.example.footballmanager.old.oldSimulator;

import jakarta.persistence.EntityManager;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.dto.*;
import org.example.footballmanager.model.*;
import org.example.footballmanager.model.event.*;
import org.example.footballmanager.model.tactics.Formation;
import org.example.footballmanager.model.tactics.Tactics;
import org.example.footballmanager.repository.MatchEventRepository;
import org.example.footballmanager.repository.MatchPlayerStatsRepository;
import org.example.footballmanager.repository.MatchRepository;
import org.example.footballmanager.repository.PlayerRepository;
import org.example.footballmanager.old.oldService.DemoMatchRuntime;
import org.example.footballmanager.util.match.MatchContext;
import org.example.footballmanager.util.events.MatchEventFactory;
import org.example.footballmanager.util.events.MatchEventMapper;
import org.example.footballmanager.util.teams.TeamStrengthCalculator;
import org.example.footballmanager.util.websocket.MatchEventWSHandler;
import org.example.footballmanager.util.websocket.PositionWSHandler;
import org.example.footballmanager.util.match.MatchRatingCalculator;
import org.example.footballmanager.util.TacticsAdjustmentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Component
public class DemoSimulator {

    private final TacticsAdjustmentService tacticsAdjustmentService;
    private final MatchEventWSHandler eventWs;
    private final PositionWSHandler positionWs;
    private final MatchRepository matchRepository;
    private final Random random = new Random();
    private final PlayerRepository playerRepository;
    private final MatchPlayerStatsRepository matchPlayerStatsRepository;
    private final MatchEventRepository matchEventRepository;
    private static final int TICK_MS = 250;
    private static final int MATCH_DURATION_SECONDS = 90;
    private final Map<Long, DemoMatchRuntime> runtimes = new ConcurrentHashMap<>();
    private final Set<Long> runningMatches = ConcurrentHashMap.newKeySet();
    private final Map<Long, ScheduledExecutorService> schedulers = new ConcurrentHashMap<>();
    private final MatchEventMapper matchEventMapper = new MatchEventMapper();
    public DemoSimulator(TacticsAdjustmentService tacticsAdjustmentService,
                         MatchRepository matchRepository, PlayerRepository playerRepository,
                         MatchPlayerStatsRepository matchPlayerStatsRepository, MatchEventWSHandler eventWs
    , PositionWSHandler positionWs, MatchEventRepository  matchEventRepository) {
        this.tacticsAdjustmentService = tacticsAdjustmentService;
        this.eventWs = eventWs;
        this.matchRepository = matchRepository;
        this.playerRepository = playerRepository;
        this.matchPlayerStatsRepository = matchPlayerStatsRepository;
        this.positionWs = positionWs;
        this.matchEventRepository = matchEventRepository;
    }

    @Autowired
    private EntityManager em;

    private void batchSaveMatchEvents(Match match, List<MatchEvent> events) {
        int batchSize = 50; // PostgreSQL safe
        int i = 0;

        for (MatchEvent e : events) {
            e.setMatch(match);   // samo poveži
            em.merge(e);         // detached entities + merge

            if (++i % batchSize == 0) {
                em.flush();
                em.clear();
            }
        }

        em.flush();
        em.clear();
    }

    private void batchSaveGoalEvents(Match match, List<GoalEvent> goals, List<Player> homePlayers, List<Player> awayPlayers) {
        int batchSize = 50; // PostgreSQL safe
        int i = 0;

        for (GoalEvent g : goals) {
            g.setMatch(match);

            // --- ažuriraj scorer ---
            if (g.getScorer() != null) {
                Player scorer = g.getScorer();
                scorer.setTotalGoals(scorer.getTotalGoals() + 1);
                playerRepository.save(scorer);

                // update u runtime listi
                homePlayers.stream()
                        .filter(p -> p.getId().equals(scorer.getId()))
                        .forEach(p -> p.setTotalGoals(scorer.getTotalGoals()));

                awayPlayers.stream()
                        .filter(p -> p.getId().equals(scorer.getId()))
                        .forEach(p -> p.setTotalGoals(scorer.getTotalGoals()));
            }

            // --- ažuriraj assistant ---
            if (g.getAssistant() != null) {
                Player assistant = g.getAssistant();
                assistant.setTotalAssists(assistant.getTotalAssists() + 1);
                playerRepository.save(assistant);

                // update u runtime listi
                homePlayers.stream()
                        .filter(p -> p.getId().equals(assistant.getId()))
                        .forEach(p -> p.setTotalAssists(assistant.getTotalAssists()));

                awayPlayers.stream()
                        .filter(p -> p.getId().equals(assistant.getId()))
                        .forEach(p -> p.setTotalAssists(assistant.getTotalAssists()));
            }

            em.persist(g);

            if (++i % batchSize == 0) {
                em.flush();
                em.clear();
            }
        }

        em.flush();
        em.clear();
    }


    private void movePlayerByRole(PlayerPositionDTO p, List<PlayerPositionDTO> players, Random random, boolean attacksRight, DemoMatchRuntime rt) {
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
    private void moveGoalkeeper(PlayerPositionDTO gk, Random random, boolean attacksRight) {
        double goalX = attacksRight ? 6 : 94;
        gk.setX(clamp(goalX + (random.nextDouble() - 0.5) * 5));
        gk.setY(clamp(48 + (random.nextDouble() - 0.5) * 12));
    }
    private void moveFullback(PlayerPositionDTO fb, List<PlayerPositionDTO> players, Random random, boolean attacksRight, boolean isRightBack) {

        double x = fb.getX();
        double y = fb.getY();

        double minX = attacksRight ? 0 : 40;
        double maxX = attacksRight ? 60 : 100;

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

        y += (random.nextDouble() - 0.5);

        PlayerPositionDTO nearbyOpponent = players.stream()
                .filter(p -> !p.getTeam().equals(fb.getTeam()))
                .filter(p -> distance(fb, p) < 15)
                .min(Comparator.comparingDouble(p -> distance(fb, p)))
                .orElse(null);

        double dx = 0;
        double dy = 0;

        if (nearbyOpponent != null) {
            dx = nearbyOpponent.getX() - x;
            dy = nearbyOpponent.getY() - y;
        } else {
            dx = attacksRight ? 1.8 : -1.8;
        }

        double goalDistance = attacksRight ? (100 - x) : x;

        if (goalDistance <= 25) {
            dy += (50 - y) * 0.25; // ulazak unutra
        }

        double dist = Math.hypot(dx, dy);
        if (dist > 0.3) {

            double speed = 0.95 + random.nextDouble() * 0.35;

            double newX = x + (dx / dist) * speed;
            double newY = y + (dy / dist) * speed;

            newX = Math.max(minX, Math.min(maxX, newX));

            double cbLine = players.stream()
                    .filter(p -> p.getTeam().equals(fb.getTeam()))
                    .filter(this::isCenterBack)
                    .mapToDouble(PlayerPositionDTO::getX)
                    .average()
                    .orElse(fb.getX());

            if (attacksRight) {
                newX = Math.min(newX, cbLine + 3);
            } else {
                newX = Math.max(newX, cbLine - 3);
            }


            newY = Math.max(minY, Math.min(maxY, newY));

            fb.setX(clamp(newX));
            fb.setY(clamp(newY));
        }

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
    private void moveCenterBack(PlayerPositionDTO cb, List<PlayerPositionDTO> players, Random random, boolean attacksRight) {

        double x = cb.getX();
        double y = cb.getY();

        double minX = attacksRight ? 0 : 50;
        double maxX = attacksRight ? 50 : 100;

        double minY = 35;
        double maxY = 65;

        PlayerPositionDTO otherCB = players.stream()
                .filter(p -> p.getTeam().equals(cb.getTeam()))
                .filter(p -> p.getId() != cb.getId())
                .filter(this::isCenterBack)
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

        double defensiveLineBase = attacksRight ? 25 : 75;

        if (random.nextDouble() < 0.15) {
            defensiveLineBase += attacksRight ? 8 : -8; // istrče 8m
        }

        double dxLine = defensiveLineBase - x;

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
    private void moveCentralMidfielder(PlayerPositionDTO cm, List<PlayerPositionDTO> players, Random random, boolean attacksRight, DemoMatchRuntime rt) {
        int id = cm.getId();
        boolean isDMC = (id == 6 || id == 16);  // DMC "policajci"
        boolean isMC = (id == 8 || id == 18);   // MC/AMC

        double minX, maxX;

        if (isDMC) {
            minX = 16;
            maxX = 70;
        } else {
            minX = 25;
            maxX = 90;
        }

        List<PlayerPositionDTO> nearestOpponents = players.stream()
                .filter(p -> !p.getTeam().equals(cm.getTeam()))
                .sorted(Comparator.comparingDouble(p -> distance(cm, p)))
                .limit(3)
                .toList();

        Optional<PlayerPositionDTO> withBall = nearestOpponents.stream()
                .filter(p -> p.getId() == getPlayerWithBall(rt))
                .findFirst();

        double dx = 0, dy = 0;

        if (withBall.isPresent() && isDMC) {
            dx = (withBall.get().getX() - cm.getX()) * 0.5;
            dy = (withBall.get().getY() - cm.getY()) * 0.5;
        } else if (isMC) {
            dx = ((minX + maxX) / 2 - cm.getX()) * 0.25;
            dy = (50 - cm.getY()) * 0.25;
        } else if (isDMC) {
            dx = ((minX + maxX) / 2 - cm.getX()) * 0.2;
            dy = (50 - cm.getY()) * 0.15;
        }

        if (isMC && rt.currentCarrier.getTeam().equals(cm.getTeam())) {
            PlayerPositionDTO targetAttacker = players.stream()
                    .filter(p -> p.getTeam().equals(cm.getTeam()) && (isStriker(p) || isWinger(p)))
                    .min(Comparator.comparingDouble(p -> distance(cm, p)))
                    .orElse(null);

            if (targetAttacker != null) {
                double spaceX = attacksRight ? targetAttacker.getX() - 1 : targetAttacker.getX() + 1;
                double spaceY = targetAttacker.getY();

                boolean freeSpace = players.stream()
                        .filter(p -> !p.getTeam().equals(cm.getTeam()))
                        .noneMatch(p -> Math.hypot(p.getX() - spaceX, p.getY() - spaceY) < 3.0);

                if (freeSpace) {
                    dx += (spaceX - cm.getX()) * 0.15;
                    dy += (spaceY - cm.getY()) * 0.15;
                }
            }
        }

        double goalX = attacksRight ? 100 : 0;
        double distToGoal = Math.abs(cm.getX() - goalX);

        if (isMC && distToGoal <= 28) {
            if (random.nextDouble() < 0.25) {
                initiateShot(cm, players, random, attacksRight, rt);
            }
        }

        double newX = clamp(cm.getX() + dx);
        double newY = clamp(cm.getY() + dy);
        newX = Math.max(minX, Math.min(maxX, newX));

        cm.setX(newX);
        cm.setY(newY);
    }
    private void moveWinger(PlayerPositionDTO winger, List<PlayerPositionDTO> players, Random random, boolean attacksRight) {

        double offsideLine = findOffsideLine(players, attacksRight);

        double maxForward = attacksRight ? offsideLine + 3 : offsideLine - 3;

        double x = winger.getX();
        double y = winger.getY();

        boolean isRightSide = (winger.getId() == 7 || winger.getId() == 19);

        double minY, maxY;

        if (isRightSide) {
            minY = 85;
            maxY = 100;

            y += (100 - y) * 0.08;
        } else {
            minY = 0;
            maxY = 15;

            y += (0 - y) * 0.08;
        }

        y += (random.nextDouble() - 0.5) * 1.2;

        y = Math.max(minY, Math.min(maxY, y));

        double dxForward = attacksRight ? 2.4 : -2.4;

        double goalDistance = attacksRight ? (100 - x) : x;

        double dyTowardsGoal = 0;

        if (goalDistance <= 25) {
            dyTowardsGoal = (50 - y) * 0.25;
        }

        double newX = x + dxForward;
        double newY = y + dyTowardsGoal;

        if (attacksRight) {
            newX = Math.min(newX, maxForward);
        } else {
            newX = Math.max(newX, maxForward);
        }

        winger.setX(clamp(newX));
        winger.setY(clamp(newY));
    }
    private void moveStriker(PlayerPositionDTO striker, List<PlayerPositionDTO> players, Random random, boolean attacksRight) {

        double offsideLine = findOffsideLine(players, attacksRight);

        double maxForward = attacksRight ? offsideLine + 3 : offsideLine - 3;

        if (attacksRight && striker.getX() > offsideLine + 6) {
            striker.setX(clamp(striker.getX() - 0.8));
            return;
        }
        if (!attacksRight && striker.getX() < offsideLine - 6) {
            striker.setX(clamp(striker.getX() + 0.8));
            return;
        }

        double baseTargetX = attacksRight ? 85 + random.nextDouble() * 15 : 15 - random.nextDouble() * 15;

        double baseTargetY = 32 + random.nextDouble() * 36;

        double dx = baseTargetX - striker.getX();
        double dy = baseTargetY - striker.getY();

        dx += attacksRight ? 3.2 : -3.2;

        double dist = Math.hypot(dx, dy);
        if (dist > 0.4) {

            double speed = 1.35 + random.nextDouble() * 0.55;

            double newX = striker.getX() + (dx / dist) * speed;
            double newY = striker.getY() + (dy / dist) * speed;

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
    private int getPlayerWithBall(DemoMatchRuntime rt) {
        return rt.currentCarrier != null ? rt.currentCarrier.getId() : -1;
    }
    private boolean isStriker(PlayerPositionDTO p) {
        int id = p.getId();
        return (id >= 9 && id <= 11) || (id >= 21 && id <= 22);
    }
    private boolean isWinger(PlayerPositionDTO p) {
        int id = p.getId();
        return (id == 7 || id == 11 || id == 19 || id == 20);
    }
    private boolean isAttacker(PlayerPositionDTO p) {
        int id = p.getId();
        return (id >= 7 && id <= 11) || (id >= 19 && id <= 22);
    }
    private double findOffsideLine(List<PlayerPositionDTO> players, boolean attacksRight) {
        String defendingTeam = attacksRight ? "AWAY" : "HOME";
        List<Double> defenderXs = players.stream()
                .filter(p -> p.getTeam().equals(defendingTeam) && !isGoalkeeper(p))
                .map(PlayerPositionDTO::getX)
                .sorted(attacksRight ? Comparator.reverseOrder() : Comparator.naturalOrder())
                .limit(2)
                .toList();

        if (defenderXs.size() < 2) {
            return attacksRight ? 100 : 0;
        }

        return defenderXs.get(1);
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
    private void trySpacePass(PlayerPositionDTO carrier, List<PlayerPositionDTO> players, Random random,  DemoMatchRuntime rt) {
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
    private double distance(PlayerPositionDTO a, PlayerPositionDTO b) {
        return Math.hypot(a.getX() - b.getX(), a.getY() - b.getY());
    }
    private double distance(BallPositionDTO ball, PlayerPositionDTO player) {
        return Math.hypot(ball.getX() - player.getX(), ball.getY() - player.getY());
    }
    private double clamp(double val) {
        return Math.max(0, Math.min(100, val));
    }
    // =============================================
    // ODLUKA NOSIOCA LOPTE
    // =============================================
    private PlayerPositionDTO chooseNextAction(PlayerPositionDTO carrier, List<PlayerPositionDTO> players, Random random, DemoMatchRuntime rt) {
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

        return findNearbyTeammate(carrier, players);
    }
    // Obrada kretanja lopte tokom šuta
    private void handleShotMovement(Random random,DemoMatchRuntime rt) {
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
    // Obrada kretanja lopte tokom odbijanja
    private void handleReboundMovement(List<PlayerPositionDTO> players, Random random, DemoMatchRuntime rt) {
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
    // =============================================
    // POMOĆNE METODE
    // =============================================
    private void pullTowardsBall(PlayerPositionDTO p, Random random, DemoMatchRuntime rt) {
        if (p.getId() == rt.currentCarrier.getId()) return;

        double toBallX = (rt.ball.getX() - p.getX()) * 0.13;
        double toBallY = (rt.ball.getY() - p.getY()) * 0.15;

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
    private void handleOffsideTolerance(PlayerPositionDTO p, List<PlayerPositionDTO> players, boolean attacksRight, DemoMatchRuntime rt) {
        if (!isAttacker(p)) return;  // Samo za napadače (strikers i wingers)

        double offsideLine = findOffsideLine(players, attacksRight);
        boolean isOffside = attacksRight ? p.getX() > offsideLine : p.getX() < offsideLine;  // Bolja detekcija: bliži golmanu od drugog poslednjeg defanzivca

        int streak = rt.offsideStreak.getOrDefault(p.getId(), 0);
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

        rt.offsideStreak.put(p.getId(), streak);
    }
    // Nova metoda za inicijalizaciju šuta
    private void initiateShot(PlayerPositionDTO shooter, List<PlayerPositionDTO> players, Random random, boolean attacksRight, DemoMatchRuntime rt) {
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
    // Nova metoda za inicijalizaciju odbijanja
    private void initiateRebound(Random random, DemoMatchRuntime rt) {
        // Odbijanje ka sredini terena, random pozicija blizu igrača broj 6 ili slučajna
        rt.targetBallX = 50 + (random.nextDouble() - 0.5) * 20;  // Oko sredine po X
        rt.targetBallY = 50 + (random.nextDouble() - 0.5) * 20;  // Oko sredine po Y, sa varijacijom

        rt.isRebounding = true;
        rt.reboundTicks = 0;
    }
    @SneakyThrows
    public DemoMatchRuntime simulateMatch(Match match, Crowd crowd, Referee referee, Tactics homeTactics, Tactics awayTactics, List<Player> homePlayers, List<Player> awayPlayers, ScheduledExecutorService scheduler) {
        CountDownLatch latch = new CountDownLatch(92);
        MatchContext context = new MatchContext(match, crowd, referee, homeTactics, awayTactics);
        context.setPossessionTeam(match.getHomeTeam());
        Formation homeFormation = homeTactics.getFormation();
        Formation awayFormation = awayTactics.getFormation();
        DemoMatchRuntime rt = runtimes.get(match.getId());
        MatchStartEvent startEvent = new MatchStartEvent();
        startEvent.setMinute(1);
        startEvent.setMatch(match);
        startEvent.apply();
        MatchEventDTO startDto = matchEventMapper.toDto(startEvent);
        if (startDto != null) {
            scheduler.schedule(() -> {
                eventWs.broadcast(match.getId(), startDto);
                rt.runtimeEvents.add(startEvent);
            }, 500, TimeUnit.MILLISECONDS);
        }
        for (int minute = 1; minute <= 92; minute++) {
            final int currentMinute = minute;
            scheduler.schedule(() -> {
                try {
                    context.setCurrentMinute(currentMinute);
                    updateFatigue(context);
                    updatePossession(context, homePlayers, awayPlayers, homeFormation, awayFormation);
                    tacticsAdjustmentService.adjustTactics(context);
                    MatchEventFactory eventFactory = new MatchEventFactory();
                    if (currentMinute < 91) {
                        MatchEvent event = eventFactory.createRandomEvent(context, homePlayers, awayPlayers, homeFormation, awayFormation);
                        if (event != null)
                        {
                            event.setMinute(currentMinute);
                            event.apply();
                            rt.runtimeEvents.add(event);
                            log.info("[{}'] Event: {}", currentMinute, event.getDescription());
                            MatchEventDTO dto = matchEventMapper.toDto(event);
                            if (dto != null) {
                                eventWs.broadcast(match.getId(), dto);
                            }
                            if (event instanceof GoalEvent goal) {
                                goal.setMatch(match);
                                rt.runtimeGoals.add(goal);
                                if (goal.getTeam().equals(match.getHomeTeam())) {
                                    rt.homeGoals++;
                                } else {
                                    rt.awayGoals++;
                                }
                                goal.setScoreAfterGoal(rt.homeGoals + ":" + rt.awayGoals);
                                goal.getScorer().setTotalGoals(goal.getScorer().getTotalGoals() + 1);
                            }
                            if (event instanceof PenaltyEvent pen ) {
                                if (pen.isScored()) {

                                    GoalEvent goal = new GoalEvent();
                                    goal.setMatch(match);
                                    goal.setTeam(pen.getTeam());
                                    goal.setScorer(pen.getTaker());
                                    goal.setMinute(currentMinute);
                                    goal.setScored(true);

                                    if (goal.getTeam().equals(match.getHomeTeam())) {
                                        rt.homeGoals++;
                                    } else {
                                        rt.awayGoals++;
                                    }
                                    goal.setScoreAfterGoal(rt.homeGoals + ":" + rt.awayGoals);
                                    goal.getScorer().setTotalGoals(goal.getScorer().getTotalGoals() + 1);
                                    goal.apply();
                                    rt.runtimeGoals.add(goal);
                                    rt.runtimeEvents.add(goal);
                                    log.info("[{}'] Event: {}", currentMinute, goal.getDescription());
                                    MatchEventDTO goalDto = matchEventMapper.toDto(goal);
                                    eventWs.broadcast(match.getId(), goalDto);
                                }

                            }
                            if (event instanceof InjuryEvent) performSubstitution(match, context, isHomeTeam(event) ? homePlayers : awayPlayers, isHomeTeam(event));

                        }

                        if (currentMinute == 65) {
                            performSubstitution(match, context, homePlayers, true);
                            performSubstitution(match, context, awayPlayers, false);
                        }
                    }
                    else if (currentMinute == 91)
                    {   MatchEndedEvent endEvent = new MatchEndedEvent();
                        endEvent.setMinute(91);
                        endEvent.setMatch(match);
                        endEvent.apply();
                        MatchEventDTO endDto = matchEventMapper.toDto(endEvent);
                        if (endDto != null) {
                            scheduler.schedule(() -> eventWs.broadcast(match.getId(), endDto), 30000, TimeUnit.MILLISECONDS);
                            rt.runtimeEvents.add(endEvent   );

                        }
                        log.info("[91'] Event: {}", endEvent.getDescription());}
                    else
                    {
                        match.setPlayed(true);
                    }
                }
                catch (Exception e) {
                    System.out.println(e.getMessage());
                }
                finally {latch.countDown();}
            }, 3000 , TimeUnit.MILLISECONDS);}
        latch.await();
        return rt;
    }

    public Match finalizeMatchResult(Match match,
                                     List<Player> homePlayers,
                                     List<Player> awayPlayers,
                                     DemoMatchRuntime rt) {

        rt.homeTeam = match.getHomeTeam();
        rt.awayTeam = match.getAwayTeam();

        // --- prebrojavanje golova po timu ---
        rt.homeGoals = (int) rt.runtimeGoals.stream()
                .filter(g -> g.getScorer() != null && g.getScorer().getTeam().equals(rt.homeTeam))
                .count();

        rt.awayGoals = (int) rt.runtimeGoals.stream()
                .filter(g -> g.getScorer() != null && g.getScorer().getTeam().equals(rt.awayTeam))
                .count();

        // --- batch merge svih ostalih eventa (kartoni, povrede, itd.) ---
        batchSaveMatchEvents(match, rt.runtimeEvents);

        // --- batch save golova i update igrača ---
        batchSaveGoalEvents(match, rt.runtimeGoals, homePlayers, awayPlayers);

        // --- update meča ---
        match.setHomeGoals(rt.homeGoals);
        match.setAwayGoals(rt.awayGoals);
        matchRepository.save(match); // 🔹 sigurni save

        // --- simulacija kartona/povreda ---
        simulateInjuriesAndCards(homePlayers, match);
        simulateInjuriesAndCards(awayPlayers, match);

        // --- ocene igrača i stats ---
        homePlayers = assignRatings(homePlayers, rt.runtimeGoals); // koristimo runtime, ne bazu
        awayPlayers = assignRatings(awayPlayers, rt.runtimeGoals);

        savePlayerStats(match, homePlayers, rt.runtimeGoals, rt.runtimeEvents.stream()
                        .filter(e -> e instanceof YellowCardEvent).map(e -> (YellowCardEvent) e).toList(),
                rt.runtimeEvents.stream()
                        .filter(e -> e instanceof RedCardEvent).map(e -> (RedCardEvent) e).toList()
        );

        savePlayerStats(match, awayPlayers, rt.runtimeGoals, rt.runtimeEvents.stream()
                        .filter(e -> e instanceof YellowCardEvent).map(e -> (YellowCardEvent) e).toList(),
                rt.runtimeEvents.stream()
                        .filter(e -> e instanceof RedCardEvent).map(e -> (RedCardEvent) e).toList()
        );

        // --- za report odmah koristimo runtimeGoalove + runtimeEvente ---
        System.out.println(generateMatchReport(match, rt, homePlayers, awayPlayers));

        return match;
    }


    public Match loadAndValidateMatch(long matchId) {
        return matchRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("Match not found"));
    }
    public boolean startSimulationOnlyIfNotRunning(long matchId) {
        if (!runningMatches.add(matchId)) {
            log.info("Match {} već se simulira!", matchId);
            return false;
        }
        return true;
    }
    public ScheduledExecutorService createAndRegisterScheduler(long matchId) {
        ScheduledExecutorService old = schedulers.get(matchId);
        if (old != null) old.shutdownNow();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        schedulers.put(matchId, scheduler);
        return scheduler;
    }
    public DemoMatchRuntime initializeRuntimeAndPositions(long matchId) {
        DemoMatchRuntime runtime = new DemoMatchRuntime();
        runtimes.put(matchId, runtime);
        Random r = new Random();
        // Home players (1–11)
        for (int i = 1; i <= 11; i++) {
            runtime.players.add(new PlayerPositionDTO(i, "HOME", 10 + r.nextDouble() * 35, 10 + r.nextDouble() * 80,0,0));
        }
        // Away players (12–22)
        for (int i = 12; i <= 22; i++) {
            runtime.players.add(new PlayerPositionDTO(i, "AWAY", 65 + r.nextDouble() * 30, 10 + r.nextDouble() * 80,0,0));
        }
        runtime.ball = new BallPositionDTO(50, 50);
        runtime.currentCarrier = runtime.players.getFirst();
        return runtime;
    }
    public void startPositionBroadcastLoop(ScheduledExecutorService scheduler, long matchId, DemoMatchRuntime rt) {
        final int totalTicks = MATCH_DURATION_SECONDS * (2500 / TICK_MS);
        scheduler.scheduleAtFixedRate(() -> {
            if (rt == null) return;
            if (rt.tick >= totalTicks) {
                stopMatch(matchId);
                return;
            }
            updatePlayerPositions(rt, random);
            handlePossessionAndActions(rt, random);
            updateBallPosition(rt);
            broadcastCurrentState(matchId, rt);
            rt.tick++;
        }, 0, TICK_MS, TimeUnit.MILLISECONDS);
    }
    private void updatePlayerPositions(DemoMatchRuntime rt, Random random) {
        for (PlayerPositionDTO p : rt.players) {
            boolean attacksRight = p.getTeam().equals("HOME");
            movePlayerByRole(p, rt.players, random, attacksRight, rt);
        }
    }
    private void handlePossessionAndActions(DemoMatchRuntime rt, Random random) {
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
    private void updateBallPosition(DemoMatchRuntime rt) {
        if (rt.isShooting) {
            handleShotMovement(random, rt);
        } else if (rt.isRebounding) {
            handleReboundMovement(rt.players, random, rt);
        } else {
            rt.ball.setX(rt.currentCarrier.getX());
            rt.ball.setY(rt.currentCarrier.getY());
        }
    }
    private void broadcastCurrentState(long matchId, DemoMatchRuntime rt) {
        GameStateDTO state = new GameStateDTO(
                rt.tick / (1000 / TICK_MS),
                new ArrayList<>(rt.players),
                rt.ball
        );
        positionWs.broadcast(matchId, state);
    }
    public void prepareMatchEntities(Match match, DemoMatchRuntime rt) {
        rt.home = match.getHomeLineup().getStartingPlayers();
        rt.away = match.getAwayLineup().getStartingPlayers();

        if (rt.home.size() != 11 ||rt.away.size() != 11) {
            throw new RuntimeException("Svaki tim mora imati tačno 11 igrača u postavi.");
        }
    }
    public Tactics createHomeTactics(Match match) {
        Tactics tactics = new Tactics();
        Formation formation = new Formation();
        formation.setName(match.getHomeLineup().getFormation() != null ? match.getHomeLineup().getFormation() : "4-4-2");
        formation.setOffenseModifier(1.05);
        formation.setDefenseModifier(0.95);
        formation.setPossessionModifier(1.0);
        tactics.setFormation(formation);
        return tactics;
    }
    public Tactics createAwayTactics(Match match) {
        Tactics tactics = new Tactics();
        Formation formation = new Formation();
        formation.setName(match.getAwayLineup().getFormation() != null ? match.getAwayLineup().getFormation() : "4-2-3-1");
        formation.setOffenseModifier(1.1);
        formation.setDefenseModifier(0.98);
        formation.setPossessionModifier(1.05);
        tactics.setFormation(formation);
        return tactics;
    }
    private void stopMatch(Long matchId) {
        ScheduledExecutorService scheduler = schedulers.remove(matchId);
        if (scheduler != null) scheduler.shutdownNow();
        runtimes.remove(matchId);
        runningMatches.remove(matchId);
        log.info("Canvas simulacija završena za meč {}", matchId);
    }
    private List<Player> assignRatings(List<Player> players, List<GoalEvent> allGoals) {
        for (Player player : players) {
            player.setRating(MatchRatingCalculator.calculate(player, player.getTeam(), allGoals));
        }
        return players;
    }


    private void simulateInjuriesAndCards(List<Player> players, Match match) {
        for (Player player : players) {
            if (random.nextDouble() < 0.05) {
                InjuryEvent injury = new InjuryEvent();
                injury.setMinute(random.nextInt(90) + 1);
                injury.setPlayer(player);
                injury.setMatch(match);
                injury.apply();
            }
            if (random.nextDouble() < 0.1) {
                YellowCardEvent yc = new YellowCardEvent();
                yc.setMinute(random.nextInt(90) + 1);
                yc.setPlayer(player);
                yc.setMatch(match);
                yc.apply();
            }
        }
    }
    private void savePlayerStats(Match match, List<Player> players,
                                 List<GoalEvent> allGoals,
                                 List<YellowCardEvent> allYellows,
                                 List<RedCardEvent> allReds) {

        for (Player player : players) {

            long goals = allGoals.stream()
                    .filter(g -> g.getScorer() != null && g.getScorer().equals(player))
                    .count();

            long assists = allGoals.stream()
                    .filter(g -> g.getAssistant() != null && g.getAssistant().equals(player))
                    .count();

            long yellowCards = allYellows.stream()
                    .filter(y -> y.getPlayer() != null && y.getPlayer().equals(player))
                    .count();

            long redCards = allReds.stream()
                    .filter(r -> r.getPlayer() != null && r.getPlayer().equals(player))
                    .count();

            MatchPlayerStats stats = new MatchPlayerStats();
            stats.setMatch(match);
            stats.setPlayer(player);
            stats.setGoals((int) goals);
            stats.setAssists((int) assists);
            stats.setYellowCards((int) yellowCards);
            stats.setRedCards((int) redCards);
            stats.setMinutesPlayed(90);
            stats.setRating(player.getRating());
            matchPlayerStatsRepository.save(stats);
        }
    }


    private String generateMatchReport(Match match,
                                       DemoMatchRuntime rt,
                                       List<Player> homePlayers,
                                       List<Player> awayPlayers) {

        StringBuilder sb = new StringBuilder();

        sb.append(String.format("%s %d - %d %s%n%n",
                match.getHomeTeam().getName(),
                rt.homeGoals,
                rt.awayGoals,
                match.getAwayTeam().getName()));

        // --- Strelci ---
        sb.append("Strelci:\n");
        // koristimo HashSet da ne dupliramo iste golove (minute+scorer)
        Set<String> addedGoals = new HashSet<>();
        rt.runtimeGoals.forEach(g -> {
            String scorerName = g.getScorer() != null ? g.getScorer().getName() : "N/A";
            String assistName = g.getAssistant() != null ? g.getAssistant().getName() : null;

            // oblik: 45' ⚽ Igrač (asistencija: Igrač)
            String desc;
            if (assistName != null) {
                desc = String.format("%d' ⚽ %s (asistencija: %s)", g.getMinute(), scorerName, assistName);
            } else {
                desc = String.format("%d' ⚽ %s", g.getMinute(), scorerName);
            }

            if (!addedGoals.contains(desc)) {
                sb.append(desc).append("\n");
                addedGoals.add(desc);
            }
        });

        sb.append("\n");

        // --- Ocene igrača ---
        sb.append("Ocene igrača - ").append(match.getHomeTeam().getName()).append("\n");
        appendPlayerRatings(sb, homePlayers, rt.runtimeGoals);

        sb.append("\nOcene igrača - ").append(match.getAwayTeam().getName()).append("\n");
        appendPlayerRatings(sb, awayPlayers, rt.runtimeGoals);

        return sb.toString();
    }

    // helper za prikaz ocena igrača sa golovima i asistencijama
    private void appendPlayerRatings(StringBuilder sb, List<Player> players, List<GoalEvent> allGoals) {
        for (Player player : players) {
            long goals = allGoals.stream()
                    .filter(g -> g.getScorer() != null && g.getScorer().equals(player))
                    .count();

            long assists = allGoals.stream()
                    .filter(g -> g.getAssistant() != null && g.getAssistant().equals(player))
                    .count();

            sb.append(String.format("- %s: %d (golova: %d, asistencija: %d)%n",
                    player.getName(),
                    player.getRating(),
                    goals,
                    assists));
        }
    }

    private void performSubstitution(Match match, MatchContext context, List<Player> teamPlayers, boolean isHomeTeam) {
        if (teamPlayers.size() < 12) return;

        Player out = teamPlayers.get(random.nextInt(11));
        Player in = teamPlayers.get(11 + random.nextInt(teamPlayers.size() - 11));

        SubstitutionEvent sub = new SubstitutionEvent();
        sub.setMatch(match);
        sub.setMinute(context.getCurrentMinute());
        sub.setPlayerOut(out);
        sub.setPlayerIn(in);
        sub.apply();

        log.info("[{}'] Substitution: {} out, {} in", context.getCurrentMinute(), out.getName(), in.getName());

        MatchEventDTO subDto = matchEventMapper.toDto(sub);
        if (subDto != null) {
            try {
                eventWs.broadcast(match.getId(),subDto);
                Thread.sleep(3500);
            } catch (Exception e) {
                log.error("Greška pri broadcastu zamene", e);
            }
        }

        teamPlayers.remove(out);
        teamPlayers.add(in);
    }
    private void updateFatigue(MatchContext context) {
        context.setFatigueFactor(Math.max(0.7, context.getFatigueFactor() - 0.002));
        //log.info("Minute: {}, Fatigue Factor: {}", context.getCurrentMinute(), context.getFatigueFactor());
    }
    private void updatePossession(MatchContext context, List<Player> homePlayers, List<Player> awayPlayers, Formation homeFormation, Formation awayFormation) {
        double homeStrength = TeamStrengthCalculator.calculateTeamStrength(homePlayers, homeFormation, context.getHomeTactics(), true);
        double awayStrength = TeamStrengthCalculator.calculateTeamStrength(awayPlayers, awayFormation, context.getAwayTactics(), false);
        double total = homeStrength + awayStrength;
        if (random.nextDouble() < homeStrength / total) {
            context.setPossessionTeam(context.getMatch().getHomeTeam());
        } else {
            context.setPossessionTeam(context.getMatch().getAwayTeam());
        }
        //log.info("Minute: {}, Possession: {}", context.getCurrentMinute(), context.getPossessionTeam().getName());
    }
    private boolean isHomeTeam(MatchEvent event) {
        if (event instanceof GoalEvent goal) return goal.getTeam().equals(goal.getMatch().getHomeTeam());
        if (event instanceof SubstitutionEvent sub) return sub.getPlayerOut().getTeam().equals(sub.getMatch().getHomeTeam());
        return false;
    }
}