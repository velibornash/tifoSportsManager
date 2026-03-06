package org.example.footballmanager.engines;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.dto.BallPositionDTO;
import org.example.footballmanager.dto.PlayerPositionDTO;
import org.example.footballmanager.model.*;
import org.example.footballmanager.repository.*;
import org.example.footballmanager.service.SeasonService;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Stream;

/**
 * Realistic Match Simulation Engine
 * 
 * Simulira fudbalski meč sa pametnom logikom:
 * - Igrači sa loptom donose inteligentne odluke (pass/shot/dribble)
 * - Poziciona odbrana sa pokrivanjem zona
 * - Dueli/kolizije kada su igrači blizu
 * - Smisleni event-i sa realističnim tokom
 * 
 * Simulacija traje 90 minuta (events-only, bez 2430 ticks)
 * Svaka minuta generiše 1-3 važna event-a
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RealisticMatchEngine {

    private final MatchRepository matchRepository;
    private final MatchEventRepository matchEventRepository;
    private final MatchTickStateRepository tickStateRepository;
    private final CompetitionEntryRepository competitionEntryRepository;
    private final SeasonCompetitionRepository seasonCompetitionRepository;
    private final PlayerRepository playerRepository;
    private final LineupRepository lineupRepository;
    private final SeasonService seasonService;
    
    private final AIDecisionMaker aiDecisionMaker;
    private final PositionalDefense positionalDefense;
    private final DuelResolver duelResolver;
    private final RealisticEventGenerator eventGenerator;
    private final BroadcastEngine broadcastEngine;
    private final Random random = new Random();
    private static final int ACTIONS_PER_MINUTE = 4;
    private static final double MIN_X = 4.0;
    private static final double MAX_X = 96.0;
    private static final double MIN_Y = 6.0;
    private static final double MAX_Y = 94.0;
    private static final double LOOSE_BALL_PICKUP_RADIUS = 3.2;
    private static final double LOOSE_BALL_STEP = 7.5;
    private static final double SUPPORT_STEP = 3.0;
    private static final double SHOT_TRIGGER_DISTANCE = 24.0;

    /**
     * Simulira kompletan realistični fudbalski meč od 90 minuta
     */
    public MatchRuntime simulateRealisticMatch(Match match) {
        log.info("Starting realistic match simulation for match {}", match.getId());
        
        MatchRuntime rt = new MatchRuntime();
        initializeRuntime(rt, match);
        
        // Main simulation loop: 90 minuta sa vise faza po minutu
        for (int minute = 1; minute <= 90; minute++) {
            for (int phase = 0; phase < ACTIONS_PER_MINUTE; phase++) {
                simulatePhase(rt, match, minute, phase);
            }

            if (minute == 15 || minute == 30 || minute == 45 || minute == 60 || minute == 75) {
                maybeGeneratePeriodicalEvent(rt, match, minute);
            }
        }
        
        // Završetak simulacije
        finalizeSimulation(rt, match);
        
        log.info("Realistic match simulation finished. Events: {}, Home: {} Away: {}", 
                rt.runtimeEvents.size(), rt.homeGoals, rt.awayGoals);
        
        return rt;
    }

    /**
     * Inicijalizuje MatchRuntime sa svim potrebnim podacima
     */
    private void initializeRuntime(MatchRuntime rt, Match match) {
        // Postavi timove i squad-ove
        rt.matchRef = match;
        rt.homeTeam = match.getHomeTeam();
        rt.awayTeam = match.getAwayTeam();
        rt.homePlayers = new ArrayList<>(match.getHomeLineup().getStartingPlayers());
        rt.awayPlayers = new ArrayList<>(match.getAwayLineup().getStartingPlayers());
        rt.homeSquad = new ArrayList<>(match.getHomeLineup().getStartingPlayers());
        rt.awaySquad = new ArrayList<>(match.getAwayLineup().getStartingPlayers());
        
        // Inicijalizuj pozicije igrača
        initializePlayerPositions(rt);
        
        // Inicijalizuj događaje i golove
        rt.runtimeEvents = new ArrayList<>();
        rt.runtimeGoals = new ArrayList<>();
        rt.tickStates = new ArrayList<>();
        rt.homeGoals = 0;
        rt.awayGoals = 0;
        rt.ticksPerMinute = ACTIONS_PER_MINUTE;
        
        // Početni događaj
        eventGenerator.createMatchStartEvent(rt, match);
        
        // Početna poleganja lopte
        rt.ball = new BallPositionDTO(50, 50);
        Player ballCarrier = rt.homePlayers.stream()
                .filter(p -> p.getPosition() != Position.GK)
                .findFirst()
                .orElse(rt.homePlayers.getFirst());
        rt.currentCarrier = new PlayerPositionDTO(
                Math.toIntExact(ballCarrier.getId()),
                "HOME",
                50, 50, 0, 0
        );
        
        rt.lastTouchTeam = "HOME";
        rt.pendingPasserId = null;
        rt.pendingPassTeam = null;
        
        log.debug("Runtime initialized. Home: {} vs Away: {}", 
                rt.homeTeam.getName(), rt.awayTeam.getName());
    }

    /**
     * Inicijalizuje pozicije igrača na terenu (4-4-2 vs 4-2-3-1)
     */
    private void initializePlayerPositions(MatchRuntime rt) {
        // HOME team: 4-4-2
        List<PlayerPositionDTO> homePositions = new ArrayList<>();
        double[] homeFormation = getHomeFormationPositions();
        
        List<Player> homeSorted = rt.homePlayers.stream()
                .sorted(Comparator.comparingInt(p -> positionPriority(p.getPosition(), "HOME")))
                .toList();
        
        for (int i = 0; i < homeSorted.size(); i++) {
            Player p = homeSorted.get(i);
            double x = homeFormation[i * 2];
            double y = homeFormation[i * 2 + 1];
            homePositions.add(new PlayerPositionDTO(
                    Math.toIntExact(p.getId()),
                    "HOME",
                    x + (random.nextDouble() - 0.5) * 2,
                    y + (random.nextDouble() - 0.5) * 2,
                    0, 0
            ));
        }
        
        // AWAY team: 4-2-3-1
        List<PlayerPositionDTO> awayPositions = new ArrayList<>();
        double[] awayFormation = getAwayFormationPositions();
        
        List<Player> awaySorted = rt.awayPlayers.stream()
                .sorted(Comparator.comparingInt(p -> positionPriority(p.getPosition(), "AWAY")))
                .toList();
        
        for (int i = 0; i < awaySorted.size(); i++) {
            Player p = awaySorted.get(i);
            double x = awayFormation[i * 2];
            double y = awayFormation[i * 2 + 1];
            awayPositions.add(new PlayerPositionDTO(
                    Math.toIntExact(p.getId()),
                    "AWAY",
                    x + (random.nextDouble() - 0.5) * 2,
                    y + (random.nextDouble() - 0.5) * 2,
                    0, 0
            ));
        }
        
        rt.players = new ArrayList<>();
        rt.players.addAll(homePositions);
        rt.players.addAll(awayPositions);
        
        log.debug("Player positions initialized: {} home, {} away", 
                homePositions.size(), awayPositions.size());
    }

    /**
     * 4-4-2 formacija za domaće (koordinate x, y parovi)
     */
    private double[] getHomeFormationPositions() {
        return new double[]{
            // Igrači od broja 1-11 redosledom: GK, RB, CB, CB, LB, RM, CM, CM, LM, ST, ST
            10, 50,   // GK - centar branskog dela
            20, 25,   // RB
            22, 40,   // CB
            22, 60,   // CB
            20, 75,   // LB
            40, 20,   // RM (right midfielder)
            38, 40,   // CM
            38, 60,   // CM
            40, 80,   // LM (left midfielder)
            60, 40,   // ST (striker)
            60, 60    // ST (striker)
        };
    }

    /**
     * 4-2-3-1 formacija za goste
     */
    private double[] getAwayFormationPositions() {
        return new double[]{
            // GK, RB, CB, CB, LB, CDM, CDM, CAM, RAM, LAM, ST
            90, 50,   // GK
            80, 25,   // RB
            78, 40,   // CB
            78, 60,   // CB
            80, 75,   // LB
            62, 45,   // CDM (Defensive midfielder)
            62, 55,   // CDM
            48, 35,   // CAM (Central attacking midfielder)
            52, 20,   // RAM (Right attacking midfielder)
            52, 80,   // LAM (Left attacking midfielder)
            40, 50    // ST
        };
    }

    /**
     * Prioritet pozicije za sortiranje (1=GK, 2=DEF, 3=MID, 4=ATT)
     */
    private int positionPriority(Position pos, String team) {
        return switch (pos) {
            case GK -> 1;
            case DEF -> 2;
            case MID -> 3;
            case ATT, WNG -> 4;
        };
    }

    /**
     * Simulira jednu minutu meča
     */
    private void simulatePhase(MatchRuntime rt, Match match, int minute, int phase) {
        Player ballCarrier = findBallCarrier(rt);
        if (ballCarrier == null) {
            resolveLooseBall(rt);
        } else {
            setCurrentCarrier(rt, ballCarrier);
            AIDecisionMaker.Decision decision = aiDecisionMaker.makeDecision(ballCarrier, rt, match, minute);
            String ballTeam = getTeam(ballCarrier, rt);

            switch (decision.getAction()) {
                case PASS -> handlePass(rt, match, minute, ballCarrier, decision, ballTeam);
                case SHOT -> handleShot(rt, match, minute, ballCarrier, decision, ballTeam);
                case DRIBBLE -> handleDribble(rt, match, minute, ballCarrier, decision, ballTeam);
            }

            if (rt.currentCarrier != null && random.nextDouble() < 0.06) {
                handleBallOutOfBounds(rt, match, minute);
            }
        }

        updateSupportingMovement(rt);
        syncBallState(rt);
        rt.tick = (minute - 1) * ACTIONS_PER_MINUTE + phase;
        rt.recordTick();
    }

    /**
     * Rukuje pasovanjem
     */
    private void handlePass(MatchRuntime rt, Match match, int minute, 
                           Player passer, AIDecisionMaker.Decision decision,
                           String ballTeam) {
        Player receiver = decision.getTargetPlayer();
        if (receiver == null) {
            handleDribble(rt, match, minute, passer, decision, ballTeam);
            return;
        }

        Player interceptor = findInterceptor(rt, passer, receiver, ballTeam);

        if (interceptor != null) {
            eventGenerator.createInterceptionEvent(rt, match, minute, passer, interceptor);
            releaseBall(rt, interceptor, getTeam(interceptor, rt), null, null, 3.0);
            rt.pendingPasserId = null;
            rt.pendingPassTeam = null;
        } else {
            eventGenerator.createPassEvent(rt, match, minute, passer, receiver);
            rt.pendingPasserId = Math.toIntExact(passer.getId());
            rt.pendingPassTeam = ballTeam;
            releaseBall(rt, receiver, ballTeam, Math.toIntExact(receiver.getId()), Math.toIntExact(passer.getId()), 1.5);
            advanceAttackingShape(rt, passer, receiver, ballTeam, 8.0);

            List<Player> nearbyDefenders = getNearbyDefenders(rt, receiver, ballTeam);
            if (!nearbyDefenders.isEmpty() && random.nextDouble() < 0.18) {
                Player defender = nearbyDefenders.get(0);
                movePlayerTowardsBall(rt, defender, 4.0);
            }
        }
    }

    /**
     * Rukuje šutom na gol
     */
    private void handleShot(MatchRuntime rt, Match match, int minute,
                           Player shooter, AIDecisionMaker.Decision decision,
                           String ballTeam) {
        Player goalkeeper = getGoalkeeper(rt, ballTeam.equals("HOME") ? "AWAY" : "HOME");
        if (goalkeeper == null) {
            handleDribble(rt, match, minute, shooter, decision, ballTeam);
            return;
        }

        if (isDangerousAttackingPosition(rt, shooter, ballTeam)) {
            eventGenerator.createChanceEvent(rt, match, minute, shooter, true);
        }

        DuelResolver.DuelResult duelResult = duelResolver.resolveShotDuel(shooter, goalkeeper);

        if (duelResult.isGoal()) {
            if (ballTeam.equals("HOME")) {
                rt.homeGoals++;
            } else {
                rt.awayGoals++;
            }
            Player assistant = resolveAssistant(rt, shooter, ballTeam);
            movePlayerTowardsGoal(rt, shooter, ballTeam, 10.0);
            eventGenerator.createGoalEvent(rt, match, minute, shooter, assistant);
            rt.pendingPasserId = null;
            rt.pendingPassTeam = null;
            Player kickoffPlayer = selectRestartPlayer(rt, ballTeam.equals("HOME") ? "AWAY" : "HOME");
            rt.ball = new BallPositionDTO(50, 50);
            rt.currentCarrier = null;
            rt.pendingReceiverId = kickoffPlayer != null ? Math.toIntExact(kickoffPlayer.getId()) : null;
            rt.lastTouchTeam = ballTeam.equals("HOME") ? "AWAY" : "HOME";
        } else if (duelResult.isSaved()) {
            movePlayerTowardsGoal(rt, shooter, ballTeam, 6.0);
            eventGenerator.createShotSavedEvent(rt, match, minute, shooter, goalkeeper);
            releaseBall(rt, goalkeeper, getTeam(goalkeeper, rt), Math.toIntExact(goalkeeper.getId()), null, 1.2);
            rt.pendingPasserId = null;
            rt.pendingPassTeam = null;
        } else {
            movePlayerTowardsGoal(rt, shooter, ballTeam, 8.0);
            eventGenerator.createShotMissedEvent(rt, match, minute, shooter);
            Player restartPlayer = selectRestartPlayer(rt, ballTeam.equals("HOME") ? "AWAY" : "HOME");
            if (restartPlayer != null) {
                releaseBall(rt, restartPlayer, getTeam(restartPlayer, rt), Math.toIntExact(restartPlayer.getId()), null, 5.0);
            } else {
                rt.currentCarrier = null;
            }
            rt.pendingPasserId = null;
            rt.pendingPassTeam = null;
        }
    }

    /**
     * Rukuje driblingom
     */
    private void handleDribble(MatchRuntime rt, Match match, int minute,
                              Player dribbler, AIDecisionMaker.Decision decision,
                              String ballTeam) {
        List<Player> nearbyDefenders = getNearbyDefenders(rt, dribbler, ballTeam);

        if (!nearbyDefenders.isEmpty() && random.nextDouble() < 0.24) {
            Player defender = nearbyDefenders.get(0);
            handleDuel(rt, match, minute, dribbler, defender);
        } else {
            if (random.nextDouble() < 0.28) {
                eventGenerator.createDribbleEvent(rt, match, minute, dribbler);
            }
            movePlayerTowardsGoal(rt, dribbler, ballTeam, 9.0);
            if (isDangerousAttackingPosition(rt, dribbler, ballTeam) && random.nextDouble() < 0.2) {
                eventGenerator.createChanceEvent(rt, match, minute, dribbler, false);
            }
            rt.lastTouchTeam = ballTeam;
            setCurrentCarrier(rt, dribbler);

            // Uspeo je da izbaci čuvara i ušao je u završnicu: pokušaj šuta odmah u istoj fazi.
            double goalDistance = estimateDistanceToGoal(rt, dribbler, ballTeam);
            List<Player> refreshedDefenders = getNearbyDefenders(rt, dribbler, ballTeam);
            if (goalDistance <= SHOT_TRIGGER_DISTANCE &&
                    (refreshedDefenders.isEmpty() || refreshedDefenders.size() == 1) &&
                    dribbler.getPosition() != Position.DEF &&
                    dribbler.getPosition() != Position.GK &&
                    random.nextDouble() < 0.62) {
                handleShot(rt, match, minute, dribbler, decision, ballTeam);
            }
        }
    }

    /**
     * Rukuje duelom između dva igrača
     */
    private void handleDuel(MatchRuntime rt, Match match, int minute,
                           Player attacker, Player defender) {
        DuelResolver.DuelResult result = duelResolver.resolveTackleDuel(attacker, defender);

        eventGenerator.createDuelEvent(rt, match, minute, attacker, defender, result);

        if (result.isWon()) {
            rt.lastTouchTeam = getTeam(attacker, rt);
            setCurrentCarrier(rt, attacker);
        } else {
            releaseBall(rt, defender, getTeam(defender, rt), Math.toIntExact(defender.getId()), null, 2.0);
            rt.pendingPasserId = null;
            rt.pendingPassTeam = null;
        }

        if (random.nextDouble() < 0.1) {
            eventGenerator.createYellowCardEvent(rt, match, minute, defender);
        }
    }

    /**
     * Rukuje loptom koja ide van terena (corner, throw-in, goal-kick)
     */
    private void handleBallOutOfBounds(MatchRuntime rt, Match match, int minute) {
        int type = random.nextInt(3);
        String restartTeam = rt.lastTouchTeam.equals("HOME") ? "AWAY" : "HOME";
        Player restartPlayer = null;

        switch (type) {
            case 0 -> {
                eventGenerator.createCornerEvent(rt, match, minute);
                restartPlayer = selectRestartPlayer(rt, restartTeam);
            }
            case 1 -> {
                eventGenerator.createThrowInEvent(rt, match, minute);
                restartPlayer = selectRestartPlayer(rt, restartTeam);
            }
            case 2 -> {
                eventGenerator.createGoalKickEvent(rt, match, minute);
                restartPlayer = getGoalkeeper(rt, restartTeam);
            }
        }

        rt.lastTouchTeam = restartTeam;
        rt.pendingPasserId = null;
        rt.pendingPassTeam = null;
        if (restartPlayer != null) {
            releaseBall(rt, restartPlayer, rt.lastTouchTeam, Math.toIntExact(restartPlayer.getId()), null, 2.0);
        }
    }

    /**
     * Generiši periodičke event-e (povrede, zamene, taktičke prilagodbe)
     */
    private void maybeGeneratePeriodicalEvent(MatchRuntime rt, Match match, int minute) {
        double rand = random.nextDouble();
        
        if (rand < 0.05) {
            // Povreda igrača
            Player injured = random.nextBoolean() 
                    ? rt.homePlayers.get(random.nextInt(rt.homePlayers.size()))
                    : rt.awayPlayers.get(random.nextInt(rt.awayPlayers.size()));
            eventGenerator.createInjuryEvent(rt, match, minute, injured);
        } else if (rand < 0.15 && minute != 90) {
            // Zamena
            boolean isHomeTeam = random.nextBoolean();
            List<Player> onPitchList = isHomeTeam ? rt.homePlayers : rt.awayPlayers;
            List<Player> squadList = isHomeTeam ? rt.homeSquad : rt.awaySquad;
            
            if (onPitchList.size() > 0 && squadList.size() > 0) {
                Player onPitch = onPitchList.get(random.nextInt(onPitchList.size()));
                Player substitute = squadList.get(random.nextInt(squadList.size()));
                
                if (!onPitch.equals(substitute) && !onPitchList.contains(substitute)) {
                    eventGenerator.createSubstitutionEvent(rt, match, minute, onPitch, substitute);
                    
                    // Zameni igrače u listi
                    int idx = onPitchList.indexOf(onPitch);
                    onPitchList.set(idx, substitute);
                    
                    // Ažuriraj pozicije
                    PlayerPositionDTO posToReplace = rt.players.stream()
                            .filter(p -> p.getId() == onPitch.getId())
                            .findFirst()
                            .orElse(null);
                    if (posToReplace != null) {
                        int posIdx = rt.players.indexOf(posToReplace);
                        rt.players.set(posIdx, new PlayerPositionDTO(
                                Math.toIntExact(substitute.getId()),
                                posToReplace.getTeam(),
                                posToReplace.getX(),
                                posToReplace.getY(),
                                0, 0
                        ));
                    }
                }
            }
        }
    }

    /**
     * Finalizuje simulaciju
     */
    private void finalizeSimulation(MatchRuntime rt, Match match) {
        // Kreiraj match ended event
        eventGenerator.createMatchEndedEvent(rt, match);
        
        log.info("Match finalized: {} {} - {} {}", 
                rt.homeTeam.getName(), rt.homeGoals,
                rt.awayGoals, rt.awayTeam.getName());
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════════

    private Player findBallCarrier(MatchRuntime rt) {
        if (rt.currentCarrier != null) {
            Player current = findPlayerById(rt, rt.currentCarrier.getId());
            if (current != null) {
                return current;
            }
        }
        return null;
    }

    private Player getGoalkeeper(MatchRuntime rt, String team) {
        List<Player> squad = team.equals("HOME") ? rt.homePlayers : rt.awayPlayers;
        return squad.stream()
                .filter(p -> p.getPosition() == Position.GK)
                .findFirst()
                .orElse(squad.getFirst());
    }

    private PlayerPositionDTO getPlayerPosition(MatchRuntime rt, Player player) {
        return rt.players.stream()
                .filter(p -> p.getId() == player.getId())
                .findFirst()
                .orElse(null);
    }

    private String getTeam(Player player, MatchRuntime rt) {
        if (rt.homePlayers.contains(player)) {
            return "HOME";
        } else if (rt.awayPlayers.contains(player)) {
            return "AWAY";
        }
        return "UNKNOWN";
    }

    private List<Player> getNearbyDefenders(MatchRuntime rt, Player attacker, String attackingTeam) {
        List<Player> defenders = attackingTeam.equals("HOME") ? rt.awayPlayers : rt.homePlayers;
        double threshold = 8.0;
        
        PlayerPositionDTO attackerPos = getPlayerPosition(rt, attacker);
        if (attackerPos == null) return List.of();
        
        return defenders.stream()
                .filter(d -> {
                    PlayerPositionDTO defPos = getPlayerPosition(rt, d);
                    if (defPos == null) return false;
                    double dist = Math.sqrt(
                            Math.pow(attackerPos.getX() - defPos.getX(), 2) +
                            Math.pow(attackerPos.getY() - defPos.getY(), 2)
                    );
                    return dist < threshold;
                })
                .toList();
    }


    private Player findInterceptor(MatchRuntime rt, Player passer, Player receiver, String passTeam) {
        List<Player> defenders = passTeam.equals("HOME") ? rt.awayPlayers : rt.homePlayers;
        List<Player> outfieldDefenders = defenders.stream()
                .filter(p -> p.getPosition() != Position.GK)
                .toList();
        if (outfieldDefenders.isEmpty() || random.nextDouble() >= 0.28) {
            return null;
        }

        PlayerPositionDTO receiverPos = getPlayerPosition(rt, receiver);
        return outfieldDefenders.stream()
                .min(Comparator.comparingDouble(def -> distanceBetween(receiverPos, getPlayerPosition(rt, def))))
                .orElse(null);
    }

    private void setCurrentCarrier(MatchRuntime rt, Player player) {
        PlayerPositionDTO playerPos = getPlayerPosition(rt, player);
        if (playerPos == null) {
            return;
        }

        rt.currentCarrier = new PlayerPositionDTO(
                Math.toIntExact(player.getId()),
                playerPos.getTeam(),
                playerPos.getX(),
                playerPos.getY(),
                0,
                0
        );
        rt.ball = new BallPositionDTO(playerPos.getX(), playerPos.getY());
        rt.pendingReceiverId = null;
    }

    private void syncBallState(MatchRuntime rt) {
        if (rt.currentCarrier == null) {
            return;
        }
        Player carrier = findPlayerById(rt, rt.currentCarrier.getId());
        PlayerPositionDTO carrierPos = carrier != null ? getPlayerPosition(rt, carrier) : null;
        if (carrierPos == null) {
            return;
        }
        rt.currentCarrier.setX(carrierPos.getX());
        rt.currentCarrier.setY(carrierPos.getY());
        rt.ball = new BallPositionDTO(carrierPos.getX(), carrierPos.getY());
    }

    private void resolveLooseBall(MatchRuntime rt) {
        Player target = findLooseBallTarget(rt);
        if (target == null) {
            return;
        }

        movePlayerTowardsBall(rt, target, LOOSE_BALL_STEP);
        PlayerPositionDTO pos = getPlayerPosition(rt, target);
        if (distanceBetween(pos, new PlayerPositionDTO(-1, "", rt.ball.getX(), rt.ball.getY(), 0, 0)) <= LOOSE_BALL_PICKUP_RADIUS) {
            setCurrentCarrier(rt, target);
            String recoveredTeam = getTeam(target, rt);
            if (!Objects.equals(rt.pendingPassTeam, recoveredTeam)) {
                rt.pendingPasserId = null;
                rt.pendingPassTeam = null;
            }
            rt.lastTouchTeam = recoveredTeam;
        }
    }

    private Player findLooseBallTarget(MatchRuntime rt) {
        List<Player> outfield = Stream.concat(rt.homePlayers.stream(), rt.awayPlayers.stream())
                .filter(player -> player.getPosition() != Position.GK || isBallInsidePenaltyArea(rt, getTeam(player, rt)))
                .toList();

        Player intended = rt.pendingReceiverId != null ? findPlayerById(rt, rt.pendingReceiverId) : null;
        if (intended != null) {
            PlayerPositionDTO intendedPos = getPlayerPosition(rt, intended);
            if (distanceBetween(intendedPos, new PlayerPositionDTO(-1, "", rt.ball.getX(), rt.ball.getY(), 0, 0)) <= 12.0) {
                return intended;
            }
        }

        return outfield.stream()
                .min(Comparator.comparingDouble(player -> distanceBetween(
                        getPlayerPosition(rt, player),
                        new PlayerPositionDTO(-1, "", rt.ball.getX(), rt.ball.getY(), 0, 0)
                )))
                .orElse(null);
    }

    private void advanceAttackingShape(MatchRuntime rt, Player passer, Player receiver, String team, double step) {
        movePlayerTowardsGoal(rt, passer, team, step * 0.4);
        movePlayerTowardsGoal(rt, receiver, team, step);

        List<Player> teammates = "HOME".equals(team) ? rt.homePlayers : rt.awayPlayers;
        teammates.stream()
                .filter(p -> !p.equals(passer) && !p.equals(receiver) && p.getPosition() != Position.GK)
                .limit(3)
                .forEach(p -> movePlayerTowardsGoal(rt, p, team, step * 0.25));
    }

    private void movePlayerTowardsGoal(MatchRuntime rt, Player player, String team, double step) {
        PlayerPositionDTO pos = getPlayerPosition(rt, player);
        if (pos == null) {
            return;
        }

        double targetX = "HOME".equals(team) ? 88.0 : 12.0;
        movePosition(pos, targetX, 50.0, step);
    }

    private void movePlayerTowardsBall(MatchRuntime rt, Player player, double step) {
        PlayerPositionDTO pos = getPlayerPosition(rt, player);
        if (pos == null || rt.ball == null) {
            return;
        }
        movePosition(pos, rt.ball.getX(), rt.ball.getY(), step);
    }

    private void updateSupportingMovement(MatchRuntime rt) {
        rt.players.forEach(pos -> {
            Player player = findPlayerById(rt, pos.getId());
            if (player == null) {
                return;
            }
            if (rt.currentCarrier != null && rt.currentCarrier.getId() == pos.getId()) {
                return;
            }

            String team = pos.getTeam();
            boolean inPossession = team.equals(rt.lastTouchTeam);
            double anchorX = baseAnchorX(player, team, inPossession);
            double anchorY = baseAnchorY(player, team);
            double ballPullX = (rt.ball.getX() - anchorX) * (inPossession ? 0.12 : 0.18);
            double ballPullY = (rt.ball.getY() - anchorY) * (inPossession ? 0.14 : 0.22);
            double targetX = anchorX + ballPullX + (random.nextDouble() - 0.5) * 1.2;
            double targetY = anchorY + ballPullY + (random.nextDouble() - 0.5) * 2.4;
            movePosition(pos, targetX, targetY, SUPPORT_STEP);
        });
    }

    private void movePosition(PlayerPositionDTO pos, double targetX, double targetY, double maxStep) {
        double dx = targetX - pos.getX();
        double dy = targetY - pos.getY();
        double distance = Math.sqrt(dx * dx + dy * dy);
        if (distance < 0.01) {
            return;
        }

        double factor = Math.min(1.0, maxStep / distance);
        pos.setX(clamp(pos.getX() + dx * factor, MIN_X, MAX_X));
        pos.setY(clamp(pos.getY() + dy * factor, MIN_Y, MAX_Y));
    }

    private void releaseBall(MatchRuntime rt, Player target, String recoveringTeam, Integer pendingReceiverId, Integer pendingPasserId, double scatter) {
        PlayerPositionDTO targetPos = getPlayerPosition(rt, target);
        if (targetPos == null) {
            return;
        }
        rt.currentCarrier = null;
        rt.pendingReceiverId = pendingReceiverId;
        rt.pendingPasserId = pendingPasserId;
        rt.lastTouchTeam = recoveringTeam;
        rt.ball = new BallPositionDTO(
                clamp(targetPos.getX() + (random.nextDouble() - 0.5) * scatter, MIN_X, MAX_X),
                clamp(targetPos.getY() + (random.nextDouble() - 0.5) * scatter, MIN_Y, MAX_Y)
        );
    }

    private double baseAnchorX(Player player, String team, boolean inPossession) {
        boolean home = "HOME".equals(team);
        return switch (player.getPosition()) {
            case GK -> home ? 8.0 : 92.0;
            case DEF -> home ? (inPossession ? 28.0 : 20.0) : (inPossession ? 72.0 : 80.0);
            case MID -> home ? (inPossession ? 48.0 : 40.0) : (inPossession ? 52.0 : 60.0);
            case WNG, ATT -> home ? (inPossession ? 68.0 : 58.0) : (inPossession ? 32.0 : 42.0);
        };
    }

    private double baseAnchorY(Player player, String team) {
        return switch (player.getPosition()) {
            case GK -> 50.0;
            case DEF -> player.getId() % 2 == 0 ? 38.0 : 62.0;
            case MID -> player.getId() % 2 == 0 ? 35.0 : 65.0;
            case WNG -> player.getId() % 2 == 0 ? 18.0 : 82.0;
            case ATT -> player.getId() % 2 == 0 ? 42.0 : 58.0;
        };
    }

    private boolean isBallInsidePenaltyArea(MatchRuntime rt, String team) {
        return "HOME".equals(team)
                ? rt.ball.getX() <= 18.0
                : rt.ball.getX() >= 82.0;
    }

    private boolean isDangerousAttackingPosition(MatchRuntime rt, Player player, String team) {
        PlayerPositionDTO pos = getPlayerPosition(rt, player);
        if (pos == null || player.getPosition() == Position.GK) {
            return false;
        }

        return "HOME".equals(team) ? pos.getX() >= 66.0 : pos.getX() <= 34.0;
    }

    private double estimateDistanceToGoal(MatchRuntime rt, Player player, String team) {
        PlayerPositionDTO pos = getPlayerPosition(rt, player);
        if (pos == null) {
            return 99.0;
        }

        double goalX = "HOME".equals(team) ? 100.0 : 0.0;
        double goalY = 50.0;
        return Math.hypot(pos.getX() - goalX, pos.getY() - goalY);
    }

    private double distanceBetween(PlayerPositionDTO a, PlayerPositionDTO b) {
        if (a == null || b == null) {
            return Double.MAX_VALUE;
        }
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        return Math.sqrt(dx * dx + dy * dy);
    }

    private Player resolveAssistant(MatchRuntime rt, Player scorer, String scoringTeam) {
        if (rt.pendingPasserId == null || !Objects.equals(rt.pendingPassTeam, scoringTeam)) {
            return null;
        }

        Player assistant = findPlayerById(rt, rt.pendingPasserId);
        if (assistant == null || assistant.equals(scorer) || assistant.getPosition() == Position.GK) {
            return null;
        }
        return assistant;
    }

    private Player findPlayerById(MatchRuntime rt, int playerId) {
        return Stream.concat(rt.homePlayers.stream(), rt.awayPlayers.stream())
                .filter(player -> player.getId() != null && player.getId() == playerId)
                .findFirst()
                .orElse(null);
    }

    private Player selectRestartPlayer(MatchRuntime rt, String team) {
        List<Player> players = "HOME".equals(team) ? rt.homePlayers : rt.awayPlayers;
        return players.stream()
                .filter(p -> p.getPosition() != Position.GK)
                .findFirst()
                .orElse(players.isEmpty() ? null : players.getFirst());
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}

