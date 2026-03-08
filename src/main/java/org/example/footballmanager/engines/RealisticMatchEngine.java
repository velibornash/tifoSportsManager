package org.example.footballmanager.engines;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.dto.BallPositionDTO;
import org.example.footballmanager.dto.PlayerPositionDTO;
import org.example.footballmanager.model.*;
import org.example.footballmanager.model.event.FreeKickEvent;
import org.example.footballmanager.model.event.GoalEvent;
import org.example.footballmanager.model.event.PenaltyEvent;
import org.example.footballmanager.model.event.VARReviewEvent;
import org.example.footballmanager.repository.*;
import org.example.footballmanager.service.SeasonService;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Stream;

/**
 * Realistic Match Simulation Engine
 * 
 * Simulates a football match with structured logic:
 * - Players on the ball make intelligent decisions (pass/shot/dribble)
 * - Poziciona odbrana sa pokrivanjem zona
 * - Duels/collisions when players are close
 * - Coherent event flow
 * 
 * Simulacija traje 90 minuta (events-only, bez 2430 ticks)
 * Each minute generates 1-3 relevant events
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
    private static final int ACTIONS_PER_MINUTE = 12; // Increased from 4 for smoother movement
    private static final double MIN_X = 4.0;
    private static final double MAX_X = 96.0;
    private static final double MIN_Y = 6.0;
    private static final double MAX_Y = 94.0;
    private static final double LOOSE_BALL_PICKUP_RADIUS = 1.0; // TIGHTER PICKUP
    private static final double LOOSE_BALL_STEP = 9.0;
    private static final double SUPPORT_STEP = 6.0;
    private static final double SHOT_TRIGGER_DISTANCE = 26.0;
    private static final double CARRIER_CONTROL_RADIUS = 0.5; // VERY TIGHT CONTROL
    private static final double PENDING_RECEIVER_LOCK_DISTANCE = 20.0;
    private static final double RECEIVER_PRIORITY_MARGIN = 2.2;
    private static final int MAX_RETREAT_TICKS = 8;
    private static final double RETREAT_FORCE = 12.0;
    private static final double DEEP_RETREAT_FORCE = 25.0;
    private static final double CENTER_BACK_ENGAGE_DISTANCE = 16.0;
    private static final int SHOT_WINDOW_TICKS = 3;
    private static final double DUEL_FOUL_CHANCE = 0.08;
    private static final double PENALTY_FOUL_CHANCE = 0.14;

    /**
     * Simulates a full 90-minute realistic match
     */
    public MatchRuntime simulateRealisticMatch(Match match) {
        log.info("Starting realistic match simulation for match {}", match.getId());
        
        MatchRuntime rt = new MatchRuntime();
        initializeRuntime(rt, match);
        rt.tick = 0; // Initialize global tick
        
        // Main simulation loop: 90 minuta sa vise faza po minutu
        for (int minute = 1; minute <= 90; minute++) {
            for (int phase = 0; phase < ACTIONS_PER_MINUTE; phase++) {
                simulatePhase(rt, match, minute, phase);
            }

            if (minute == 15 || minute == 30 || minute == 45 || minute == 60 || minute == 75) {
                maybeGeneratePeriodicalEvent(rt, match, minute);
            }
        }
        
        // Finalize simulation
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
        rt.homePlayers = new ArrayList<>(match.getHomeLineup().getOrderedStartingPlayers());
        rt.awayPlayers = new ArrayList<>(match.getAwayLineup().getOrderedStartingPlayers());
        rt.homeSquad = new ArrayList<>(match.getHomeLineup().getOrderedStartingPlayers());
        rt.awaySquad = new ArrayList<>(match.getAwayLineup().getOrderedStartingPlayers());
        
        // Initialize player positions
        initializePlayerPositions(rt);
        
        // Initialize events and goals
        rt.runtimeEvents = new ArrayList<>();
        rt.runtimeGoals = new ArrayList<>();
        rt.tickStates = new ArrayList<>();
        rt.homeGoals = 0;
        rt.awayGoals = 0;
        rt.ticksPerMinute = ACTIONS_PER_MINUTE;
        rt.tick = 0;
        
        // Initial event
        eventGenerator.createMatchStartEvent(rt, match);
        
        // Initial ball placement
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
     * Initializes player positions on the pitch (4-4-2 vs 4-2-3-1)
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
     * Home 4-4-2 formation (x, y pairs)
     */
    private double[] getHomeFormationPositions() {
        return new double[]{
            // Players 1-11 in order: GK, RB, CB, CB, LB, RM, CM, CM, LM, ST, ST
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
     * Simulates one match phase
     */
    private void simulatePhase(MatchRuntime rt, Match match, int minute, int phase) {
        rt.tick++; // Increment global tick instead of recalculating from minute/phase
        Player ballCarrier = findBallCarrier(rt);
        
        // Reset pass completion flag at start of phase
        rt.passCompletedThisPhase = false;
        
        if (rt.ballInTransit) {
            resolveBallTransit(rt, match, minute);
            
            // If pass was just completed, record an intermediate tick to show receiver with ball
            if (rt.passCompletedThisPhase) {
                updateSupportingMovement(rt);
                syncBallState(rt);
                // Use current tick for intermediate tick record
                rt.recordTick();
                log.debug("[{}'_{}] PASS COMPLETED - Intermediate tick recorded for carrier {}", 
                        minute, phase, rt.currentCarrier != null ? rt.currentCarrier.getId() : "null");
            }
        } else if (ballCarrier == null) {
            resolveLooseBall(rt);
        } else {
            refreshCurrentCarrier(rt, ballCarrier);
            
            // PAUSE LOGIC: If player just passed, they stay still for 1 tick to show the action
            PlayerPositionDTO carrierPos = getPlayerPosition(rt, ballCarrier);
            if (carrierPos != null && carrierPos.getOffsideTicksRemaining() > 0 && carrierPos.getOffsideTicksRemaining() < 2) {
                // We reuse offsideTicksRemaining as a generic 'action pause' counter for simplicity
                // but only if it was just set during a pass
                log.debug("Player {} is pausing after action", ballCarrier.getName());
                // Do nothing else, just record tick
            } else {
                AIDecisionMaker.Decision decision = aiDecisionMaker.makeDecision(ballCarrier, rt, match, minute);
                String ballTeam = getTeam(ballCarrier, rt);

                // Log decision for debugging
                if (decision.getAction() == AIDecisionMaker.ActionType.SHOT) {
                    log.info("[MIN {}, PHASE {}] SHOT by {} ({}) at ({}, {})", 
                            minute, phase, ballCarrier.getName(), ballTeam,
                            Math.round(rt.currentCarrier.getX()), Math.round(rt.currentCarrier.getY()));
                }

                switch (decision.getAction()) {
                    case PASS -> {
                        handlePass(rt, match, minute, ballCarrier, decision, ballTeam);
                    }
                    case SHOT -> handleShot(rt, match, minute, ballCarrier, decision, ballTeam);
                    case DRIBBLE -> handleDribble(rt, match, minute, ballCarrier, decision, ballTeam);
                }

                if (rt.currentCarrier != null && random.nextDouble() < 0.06) {
                    handleBallOutOfBounds(rt, match, minute);
                }
            }
        }

        updateSupportingMovement(rt);
        syncBallState(rt);
        rt.recordTick();
        
        // Decrement pause counter if active
        rt.players.forEach(p -> {
            if (p.getOffsideTicksRemaining() > 0) {
                p.setOffsideTicksRemaining(p.getOffsideTicksRemaining() - 1);
            }
        });

        // Log carrier state for debugging
        if (rt.currentCarrier != null) {
            log.debug("[{}'_{}] Carrier ID: {}, Ball: ({:.1f}, {:.1f})", 
                    minute, phase, rt.currentCarrier.getId(), rt.ball.getX(), rt.ball.getY());
        }
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

        if (isOffsideReceiver(rt, passer, receiver, ballTeam) && random.nextDouble() < 0.65) {
            eventGenerator.createOffsideEvent(rt, match, minute, receiver);
            String defendingTeam = "HOME".equals(ballTeam) ? "AWAY" : "HOME";
            
            // RESET POSITIONS FOR DEFENSIVE RESTART
            resetPositionsForRestart(rt, defendingTeam);
            
            Player restartPlayer = selectRestartPlayer(rt, defendingTeam);
            rt.pendingPasserId = null;
            rt.pendingPassTeam = null;
            rt.pendingReceiverId = null;
            rt.lastTouchTeam = defendingTeam;
            
            if (restartPlayer != null) {
                // Ball starts from defender/GK at a reasonable distance from goal
                PlayerPositionDTO restartPos = getPlayerPosition(rt, restartPlayer);
                if (restartPos != null) {
                    rt.ball = new BallPositionDTO(restartPos.getX(), restartPos.getY());
                }
                releaseBall(rt, restartPlayer, defendingTeam, Math.toIntExact(restartPlayer.getId()), null, 2.0);
            } else {
                rt.currentCarrier = null;
            }
            return;
        }

        eventGenerator.createPassEvent(rt, match, minute, passer, receiver);
        rememberPassPair(rt, passer, receiver, ballTeam);
        startPassTransit(rt, passer, receiver, ballTeam, 1.3); // Slightly faster ball
        advanceAttackingShape(rt, passer, receiver, ballTeam, 8.0);
        
        // PAUSE: Passer stays still for 2 ticks to visually show the release
        PlayerPositionDTO passerPos = getPlayerPosition(rt, passer);
        if (passerPos != null) {
            passerPos.setOffsideTicksRemaining(2);
        }

        List<Player> nearbyDefenders = getNearbyDefenders(rt, receiver, ballTeam);
        if (!nearbyDefenders.isEmpty() && random.nextDouble() < 0.18) {
            Player defender = nearbyDefenders.get(0);
            movePlayerTowardsBall(rt, defender, 4.0);
        }
    }

    /**
     * Handles a shot on goal
     */
    private void handleShot(MatchRuntime rt, Match match, int minute,
                           Player shooter, AIDecisionMaker.Decision decision,
                           String ballTeam) {
        if (!canShootNow(rt, shooter, ballTeam)) {
            movePlayerTowardsBall(rt, shooter, Math.max(LOOSE_BALL_STEP, 8.0));
            if (isPlayerControllingBall(rt, shooter)) {
                setCurrentCarrier(rt, shooter, "carry");
            } else {
                resolveLooseBall(rt);
                return;
            }
        }

        Player goalkeeper = getGoalkeeper(rt, ballTeam.equals("HOME") ? "AWAY" : "HOME");
        if (goalkeeper == null) {
            handleDribble(rt, match, minute, shooter, decision, ballTeam);
            return;
        }

        if (isDangerousAttackingPosition(rt, shooter, ballTeam)) {
            eventGenerator.createChanceEvent(rt, match, minute, shooter, true);
        }

        PlayerPositionDTO shooterPos = getPlayerPosition(rt, shooter);
        DuelResolver.DuelResult duelResult = duelResolver.resolveShotDuel(shooter, goalkeeper, 
                shooterPos != null ? shooterPos.getX() : 85.0, 
                shooterPos != null ? shooterPos.getY() : 50.0);

        if (duelResult.isGoal()) {
            if (ballTeam.equals("HOME")) {
                rt.homeGoals++;
            } else {
                rt.awayGoals++;
            }
            Player assistant = resolveAssistant(rt, shooter, ballTeam);
            movePlayerTowardsGoal(rt, shooter, ballTeam, 10.0);
            GoalEvent goalEvent = eventGenerator.createGoalEvent(rt, match, minute, shooter, assistant, duelResult.getXG());
            log.info("⚽ GOAL! {} scores for {} (xG: {:.2f})", shooter.getName(), ballTeam, duelResult.getXG());
            maybeCreateVarReview(goalEvent, null, rt, match, minute);
            
            // KICK-OFF RESTART
            String restartTeam = ballTeam.equals("HOME") ? "AWAY" : "HOME";
            resetPositionsForRestart(rt, restartTeam);
            
            rt.pendingPasserId = null;
            rt.pendingPassTeam = null;
            Player kickoffPlayer = selectRestartPlayer(rt, restartTeam);
            rt.ball = new BallPositionDTO(50, 50);
            rt.currentCarrier = null;
            rt.pendingReceiverId = kickoffPlayer != null ? Math.toIntExact(kickoffPlayer.getId()) : null;
            rt.lastTouchTeam = restartTeam;
        } else if (duelResult.isSaved()) {
            movePlayerTowardsGoal(rt, shooter, ballTeam, 6.0);
            eventGenerator.createShotSavedEvent(rt, match, minute, shooter, goalkeeper, duelResult.getXG());
            log.info("🧤 SAVE! {} saved by {} (xG: {:.2f})", shooter.getName(), goalkeeper.getName(), duelResult.getXG());
            
            // Chance for corner instead of always catching
            if (random.nextDouble() < 0.45) {
                String restartTeam = ballTeam; // attacking team keeps ball for corner
                resetPositionsForRestart(rt, restartTeam);
                boolean upperSide = shooterPos != null ? shooterPos.getY() < 50.0 : random.nextBoolean();
                Player cornerTaker = selectWideRestartPlayer(rt, restartTeam, upperSide);
                eventGenerator.createCornerEvent(rt, match, minute, restartTeam, cornerTaker);
                rt.ball = new BallPositionDTO(ballTeam.equals("HOME") ? 98.5 : 1.5, upperSide ? 6.0 : 94.0);
                if (cornerTaker != null) {
                    releaseBall(rt, cornerTaker, restartTeam, Math.toIntExact(cornerTaker.getId()), null, 2.0);
                }
            } else {
                releaseBall(rt, goalkeeper, getTeam(goalkeeper, rt), Math.toIntExact(goalkeeper.getId()), null, 1.2);
            }
            rt.pendingPasserId = null;
            rt.pendingPassTeam = null;
        } else {
            movePlayerTowardsGoal(rt, shooter, ballTeam, 8.0);
            eventGenerator.createShotMissedEvent(rt, match, minute, shooter, duelResult.getXG());
            log.info("❌ MISS! {} missed (xG: {:.2f})", shooter.getName(), duelResult.getXG());
            
            // GOAL KICK RESTART
            String restartTeam = ballTeam.equals("HOME") ? "AWAY" : "HOME";
            resetPositionsForRestart(rt, restartTeam);
            
            Player goalkeeperRestart = getGoalkeeper(rt, restartTeam);
            if (goalkeeperRestart != null) {
                eventGenerator.createGoalKickEvent(rt, match, minute, restartTeam, goalkeeperRestart);
                PlayerPositionDTO gkPos = getPlayerPosition(rt, goalkeeperRestart);
                if (gkPos != null) rt.ball = new BallPositionDTO(gkPos.getX(), gkPos.getY());
                releaseBall(rt, goalkeeperRestart, restartTeam, Math.toIntExact(goalkeeperRestart.getId()), null, 5.0);
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

        if (!nearbyDefenders.isEmpty() && random.nextDouble() < 0.22) {
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
            setCurrentCarrier(rt, dribbler, "dribble");

            // The attacker beat the marker and entered the final third: allow an immediate shot attempt.
            double goalDistance = estimateDistanceToGoal(rt, dribbler, ballTeam);
            List<Player> refreshedDefenders = getNearbyDefenders(rt, dribbler, ballTeam);
            if (goalDistance <= SHOT_TRIGGER_DISTANCE &&
                    (refreshedDefenders.isEmpty() || refreshedDefenders.size() == 1) &&
                    dribbler.getPosition() != Position.DEF &&
                    dribbler.getPosition() != Position.GK &&
                    random.nextDouble() < 0.74) {
                handleShot(rt, match, minute, dribbler, decision, ballTeam);
            }
        }
    }

    /**
     * Handles a duel between two players
     */
    private void handleDuel(MatchRuntime rt, Match match, int minute,
                           Player attacker, Player defender) {
        if (shouldCallFoul(rt, attacker, defender)) {
            handleFoul(rt, match, minute, attacker, defender);
            return;
        }

        DuelResolver.DuelResult result = duelResolver.resolveTackleDuel(attacker, defender);

        eventGenerator.createDuelEvent(rt, match, minute, attacker, defender, result);

        if (result.isWon()) {
            rt.lastTouchTeam = getTeam(attacker, rt);
            setCurrentCarrier(rt, attacker, "duel");
        } else {
            releaseBall(rt, defender, getTeam(defender, rt), Math.toIntExact(defender.getId()), null, 2.0);
            rt.pendingPasserId = null;
            rt.pendingPassTeam = null;
        }

        if (random.nextDouble() < 0.1) {
            eventGenerator.createYellowCardEvent(rt, match, minute, defender);
        }
    }

    private boolean shouldCallFoul(MatchRuntime rt, Player attacker, Player defender) {
        PlayerPositionDTO attackerPos = getPlayerPosition(rt, attacker);
        if (attackerPos == null) {
            return false;
        }
        boolean attacksRight = "HOME".equals(getTeam(attacker, rt));
        double foulChance = isInPenaltyBox(attackerPos, attacksRight) ? PENALTY_FOUL_CHANCE : DUEL_FOUL_CHANCE;
        if (defender.getPosition() == Position.DEF) {
            foulChance += 0.03;
        }
        return random.nextDouble() < foulChance;
    }

    private void handleFoul(MatchRuntime rt, Match match, int minute, Player attacker, Player defender) {
        String attackingTeam = getTeam(attacker, rt);
        boolean attacksRight = "HOME".equals(attackingTeam);
        PlayerPositionDTO attackerPos = getPlayerPosition(rt, attacker);
        if (attackerPos == null) {
            return;
        }

        if (isInPenaltyBox(attackerPos, attacksRight)) {
            PenaltyEvent penalty = new PenaltyEvent();
            penalty.setMinute(minute);
            penalty.setTick(rt.tick);
            penalty.setMatch(match);
            penalty.setTeam("HOME".equals(attackingTeam) ? match.getHomeTeam() : match.getAwayTeam());
            penalty.setTaker(attacker);

            Player goalkeeper = getGoalkeeper(rt, attacksRight ? "AWAY" : "HOME");
            DuelResolver.DuelResult penResult = duelResolver.resolveShotDuel(attacker, goalkeeper, 
                    attacksRight ? 88.0 : 12.0, 50.0); // Penalty spot approx
            penalty.setScored(penResult.isGoal());
            rt.runtimeEvents.add(penalty);

            GoalEvent goalEvent = null;
            if (penResult.isGoal()) {
                if ("HOME".equals(attackingTeam)) {
                    rt.homeGoals++;
                } else {
                    rt.awayGoals++;
                }
                goalEvent = eventGenerator.createGoalEvent(rt, match, minute, attacker, null, penResult.getXG());
                log.info("⚽ PENALTY GOAL! {} scores for {} (xG: {:.2f})", attacker.getName(), attackingTeam, penResult.getXG());
                
                // RESTART POSITION AFTER PENALTY GOAL
                String restartTeam = attacksRight ? "AWAY" : "HOME";
                resetPositionsForRestart(rt, restartTeam);
                
                rt.ball = new BallPositionDTO(50, 50);
                rt.currentCarrier = null;
                Player kickoffPlayer = selectRestartPlayer(rt, restartTeam);
                rt.pendingReceiverId = kickoffPlayer != null ? Math.toIntExact(kickoffPlayer.getId()) : null;
                rt.lastTouchTeam = restartTeam;
            } else if (goalkeeper != null) {
                if (penResult.isSaved()) {
                    eventGenerator.createShotSavedEvent(rt, match, minute, attacker, goalkeeper, penResult.getXG());
                    log.info("🧤 PENALTY SAVED! {} by {}", attacker.getName(), goalkeeper.getName());
                } else {
                    eventGenerator.createShotMissedEvent(rt, match, minute, attacker, penResult.getXG());
                    log.info("❌ PENALTY MISSED! {} (xG: {:.2f})", attacker.getName(), penResult.getXG());
                }
                releaseBall(rt, goalkeeper, getTeam(goalkeeper, rt), Math.toIntExact(goalkeeper.getId()), null, 1.2);
            }

            maybeCreateVarReview(goalEvent, penalty, rt, match, minute);
            eventGenerator.createYellowCardEvent(rt, match, minute, defender);
            return;
        }

        FreeKickEvent fk = new FreeKickEvent();
        fk.setMinute(minute);
        fk.setTick(rt.tick);
        fk.setMatch(match);
        fk.setTeam("HOME".equals(attackingTeam) ? match.getHomeTeam() : match.getAwayTeam());
        fk.setTaker(attacker);
        fk.setPlayer(attacker);
        fk.setDirect(estimateDistanceToGoal(rt, attacker, attackingTeam) <= 24.0);
        fk.setDangerous(isDangerousAttackingPosition(rt, attacker, attackingTeam));
        rt.runtimeEvents.add(fk);
        rt.lastTouchTeam = attackingTeam;
        setCurrentCarrier(rt, attacker, "duel");
        if (random.nextDouble() < 0.6) {
            eventGenerator.createYellowCardEvent(rt, match, minute, defender);
        }
    }

    /**
     * Rukuje loptom koja ide van terena (corner, throw-in, goal-kick)
     */
    private void handleBallOutOfBounds(MatchRuntime rt, Match match, int minute) {
        BallPositionDTO ball = rt.ball != null ? rt.ball : new BallPositionDTO(50, 50);
        String lastTouchTeam = "AWAY".equals(rt.lastTouchTeam) ? "AWAY" : "HOME";
        String restartTeam = oppositeTeam(lastTouchTeam);
        boolean upperSide = ball.getY() < 50.0;
        boolean nearSideline = ball.getY() <= 12.0 || ball.getY() >= 88.0;
        boolean nearGoalLine = ball.getX() <= 12.0 || ball.getX() >= 88.0;

        Player restartPlayer;

        if (nearGoalLine && !nearSideline) {
            boolean homeGoalSide = ball.getX() <= 12.0;
            String defendingTeam = homeGoalSide ? "HOME" : "AWAY";
            boolean isCorner = Objects.equals(lastTouchTeam, defendingTeam);
            restartTeam = isCorner ? oppositeTeam(defendingTeam) : defendingTeam;

            resetPositionsForRestart(rt, restartTeam);

            if (isCorner) {
                restartPlayer = selectWideRestartPlayer(rt, restartTeam, upperSide);
                eventGenerator.createCornerEvent(rt, match, minute, restartTeam, restartPlayer);
                rt.ball = new BallPositionDTO(homeGoalSide ? 1.5 : 98.5, upperSide ? 6.0 : 94.0);
                if (restartPlayer != null) {
                    releaseBall(rt, restartPlayer, restartTeam, Math.toIntExact(restartPlayer.getId()), null, 2.0);
                }
            } else {
                restartPlayer = getGoalkeeper(rt, restartTeam);
                eventGenerator.createGoalKickEvent(rt, match, minute, restartTeam, restartPlayer);
                if (restartPlayer != null) {
                    PlayerPositionDTO gkPos = getPlayerPosition(rt, restartPlayer);
                    if (gkPos != null) {
                        rt.ball = new BallPositionDTO(gkPos.getX(), gkPos.getY());
                    }
                    releaseBall(rt, restartPlayer, restartTeam, Math.toIntExact(restartPlayer.getId()), null, 5.0);
                }
            }
        } else {
            resetPositionsForRestart(rt, restartTeam);
            restartPlayer = selectWideRestartPlayer(rt, restartTeam, upperSide);
            eventGenerator.createThrowInEvent(rt, match, minute, restartTeam, restartPlayer);
            double throwInX = clamp(ball.getX(), 8.0, 92.0);
            double throwInY = ball.getY() <= 50.0 ? 6.5 : 93.5;
            rt.ball = new BallPositionDTO(throwInX, throwInY);
            if (restartPlayer != null) {
                releaseBall(rt, restartPlayer, restartTeam, Math.toIntExact(restartPlayer.getId()), null, 2.0);
            }
        }

        rt.lastTouchTeam = restartTeam;
        rt.pendingPasserId = null;
        rt.pendingPassTeam = null;
    }

    /**
     * Generates periodic events (injuries, substitutions, tactical adjustments)
     */
    private void maybeGeneratePeriodicalEvent(MatchRuntime rt, Match match, int minute) {
        double rand = random.nextDouble();
        
        if (rand < 0.05) {
            // Player injury
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
                    
            // Swap players in the list
                    int idx = onPitchList.indexOf(onPitch);
                    onPitchList.set(idx, substitute);
                    
                    // Update positions
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

    // Helper methods

    private Player findBallCarrier(MatchRuntime rt) {
        if (rt.currentCarrier != null) {
            Player current = findPlayerById(rt, rt.currentCarrier.getId());
            if (current != null && isPlayerControllingBall(rt, current)) {
                return current;
            }
            rt.currentCarrier = null;
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
        int playerId = Math.toIntExact(player.getId());
        return rt.players.stream()
                .filter(p -> p.getId() == playerId)
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
                .sorted(Comparator
                        .comparing((Player d) -> d.getPosition() != Position.DEF)
                        .thenComparingDouble(d -> distanceBetween(attackerPos, getPlayerPosition(rt, d))))
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

    private void setCurrentCarrier(MatchRuntime rt, Player player, String controlSource) {
        PlayerPositionDTO playerPos = getPlayerPosition(rt, player);
        
        // Fallback: ako player nije pronađen u rt.players, kreiraj default position
        if (playerPos == null) {
            int playerId = Math.toIntExact(player.getId());
            String team = getTeam(player, rt);
            // Use ball position or center if all else fails
            double x = rt.ball != null ? rt.ball.getX() : 50.0;
            double y = rt.ball != null ? rt.ball.getY() : 50.0;
            playerPos = new PlayerPositionDTO(playerId, team, x, y, 0, 0);
            log.warn("Player {} not found in position list, using fallback position ({}, {})", 
                    player.getId(), x, y);
        }

        // SYNC: Ball and carrier MUST be at EXACTLY same coordinates
        rt.ball = new BallPositionDTO(playerPos.getX(), playerPos.getY());
        
        rt.currentCarrier = new PlayerPositionDTO(
                Math.toIntExact(player.getId()),
                playerPos.getTeam(),
                playerPos.getX(),
                playerPos.getY(),
                0,
                0
        );
        rt.pendingReceiverId = null;
        rt.ballInTransit = false;
        rt.ballTransitCanBeIntercepted = false;
        rt.ballTransitTicks = 0;
        rt.ballTransitMaxTicks = 0;
        rt.lastControllerId = Math.toIntExact(player.getId());
        rt.lastControlTick = rt.tick;
        rt.lastControlSource = controlSource;
        rt.lastControlX = playerPos.getX();
        rt.lastControlTeam = playerPos.getTeam();

        switch (controlSource) {
            case "pass_receive" -> {
                rt.lastPassReceiverId = Math.toIntExact(player.getId());
                rt.lastPassReceiveTick = rt.tick;
                rt.lastPassReceiveX = playerPos.getX();
                rt.lastPassReceiveTeam = playerPos.getTeam();
            }
            case "duel" -> {
                rt.lastDuelWinnerId = Math.toIntExact(player.getId());
                rt.lastDuelWinTick = rt.tick;
                rt.lastDuelWinX = playerPos.getX();
                rt.lastDuelWinTeam = playerPos.getTeam();
            }
            case "loose_ball" -> {
                rt.lastRecoveryPlayerId = Math.toIntExact(player.getId());
                rt.lastRecoveryTick = rt.tick;
                rt.lastRecoveryX = playerPos.getX();
                rt.lastRecoveryTeam = playerPos.getTeam();
            }
            default -> {
            }
        }
    }

    private void refreshCurrentCarrier(MatchRuntime rt, Player player) {
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
        rt.ballInTransit = false;
        rt.ballTransitCanBeIntercepted = false;
        rt.ballTransitTicks = 0;
        rt.ballTransitMaxTicks = 0;
    }

    private void syncBallState(MatchRuntime rt) {
        if (rt.currentCarrier == null || rt.ballInTransit) {
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
        if (rt.ballInTransit) {
            return;
        }
        Player target = findLooseBallTarget(rt);
        if (target == null) {
            return;
        }

        double chaseStep = rt.pendingReceiverId != null && Objects.equals(rt.pendingReceiverId, Math.toIntExact(target.getId()))
                ? LOOSE_BALL_STEP + 1.8
                : LOOSE_BALL_STEP;
        movePlayerTowardsBall(rt, target, chaseStep);
        PlayerPositionDTO pos = getPlayerPosition(rt, target);
        if (distanceBetween(pos, new PlayerPositionDTO(-1, "", rt.ball.getX(), rt.ball.getY(), 0, 0)) <= LOOSE_BALL_PICKUP_RADIUS) {
            String source = rt.pendingReceiverId != null && Objects.equals(rt.pendingReceiverId, Math.toIntExact(target.getId()))
                    ? "pass_receive"
                    : "loose_ball";
            setCurrentCarrier(rt, target, source);
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
            double intendedDistance = distanceBetween(intendedPos, new PlayerPositionDTO(-1, "", rt.ball.getX(), rt.ball.getY(), 0, 0));
            if (intendedDistance <= PENDING_RECEIVER_LOCK_DISTANCE) {
                Player nearestOpponent = outfield.stream()
                        .filter(player -> !Objects.equals(getTeam(player, rt), getTeam(intended, rt)))
                        .min(Comparator.comparingDouble(player -> distanceBetween(
                                getPlayerPosition(rt, player),
                                new PlayerPositionDTO(-1, "", rt.ball.getX(), rt.ball.getY(), 0, 0)
                        )))
                        .orElse(null);
                if (nearestOpponent == null) {
                    return intended;
                }
                double opponentDistance = distanceBetween(
                        getPlayerPosition(rt, nearestOpponent),
                        new PlayerPositionDTO(-1, "", rt.ball.getX(), rt.ball.getY(), 0, 0)
                );
                if (intendedDistance <= opponentDistance + RECEIVER_PRIORITY_MARGIN) {
                    return intended;
                }
            }
            if (intendedDistance <= 12.0) {
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

    private void resolveBallTransit(MatchRuntime rt, Match match, int minute) {
        if (!rt.ballInTransit || rt.ball == null) {
            return;
        }

        rt.ballTransitTicks++;
        double progress = Math.min(1.0, (double) rt.ballTransitTicks / rt.ballTransitMaxTicks);
        
        // Linear interpolation for ball position
        double startX = rt.ballTransitStartX;
        double startY = rt.ballTransitStartY;
        rt.ball.setX(clamp(startX + (rt.ballTransitTargetX - startX) * progress, MIN_X, MAX_X));
        rt.ball.setY(clamp(startY + (rt.ballTransitTargetY - startY) * progress, MIN_Y, MAX_Y));

        PlayerPositionDTO ballPosDTO = new PlayerPositionDTO(-1, "", rt.ball.getX(), rt.ball.getY(), 0, 0);
        if (rt.ballTransitCanBeIntercepted) {
            Player interceptor = findTransitInterceptor(rt, ballPosDTO);
            if (interceptor != null) {
                PlayerPositionDTO intPos = getPlayerPosition(rt, interceptor);
                if (intPos != null) {
                    // Ball must be CLOSE to interceptor's position to be intercepted
                    double dist = distanceBetween(intPos, ballPosDTO);
                    if (dist <= 0.8) { // Tighter pickup
                        Player passer = rt.pendingPasserId != null ? findPlayerById(rt, rt.pendingPasserId) : null;
                        eventGenerator.createInterceptionEvent(rt, match, minute, passer, interceptor);
                        rt.ballInTransit = false;
                        rt.pendingReceiverId = null;
                        rt.pendingPasserId = null;
                        rt.pendingPassTeam = null;
                        rt.lastTouchTeam = getTeam(interceptor, rt);
                        setCurrentCarrier(rt, interceptor, "interception");
                        rt.passCompletedThisPhase = true;
                        return;
                    }
                }
            }
        }

        Player intended = rt.pendingReceiverId != null ? findPlayerById(rt, rt.pendingReceiverId) : null;
        if (intended != null) {
            // Give receiver a speed boost to reach the ball
            movePlayerTowardsBall(rt, intended, LOOSE_BALL_STEP + 1.2);
            PlayerPositionDTO intendedPos = getPlayerPosition(rt, intended);
            // REQUIRE EXTREMELY CLOSE PROXIMITY FOR PICKUP
            if (distanceBetween(intendedPos, ballPosDTO) <= 0.6) { // Very tight pickup
                rt.ballInTransit = false;
                setCurrentCarrier(rt, intended, "pass_receive");
                rt.lastTouchTeam = getTeam(intended, rt);
                rt.passCompletedThisPhase = true;
                return;
            }
        }

        if (rt.ballTransitTicks >= rt.ballTransitMaxTicks) {
            rt.ballInTransit = false;
            // No one picked it up, ball is loose at target
            rt.ball.setX(rt.ballTransitTargetX);
            rt.ball.setY(rt.ballTransitTargetY);
            rt.pendingReceiverId = null;
        }
    }

    private Player findTransitInterceptor(MatchRuntime rt, PlayerPositionDTO ballPos) {
        if (rt.pendingPassTeam == null) {
            return null;
        }
        List<Player> defenders = "HOME".equals(rt.pendingPassTeam) ? rt.awayPlayers : rt.homePlayers;
        return defenders.stream()
                .filter(player -> player.getPosition() != Position.GK)
                .filter(player -> {
                    PlayerPositionDTO pos = getPlayerPosition(rt, player);
                    return pos != null && distanceBetween(pos, ballPos) <= 2.4;
                })
                .min(Comparator.comparingDouble(player -> distanceBetween(getPlayerPosition(rt, player), ballPos)))
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
            // EXCLUDE: Current ball carrier
            if (rt.currentCarrier != null && rt.currentCarrier.getId() == pos.getId()) {
                return;
            }
            // EXCLUDE: Players currently in 'action pause' (like just released a pass)
            if (pos.getOffsideTicksRemaining() > 0) {
                return;
            }
            applyRoleMovement(rt, pos, player);
        });
        spreadSameTeamPlayers(rt);
    }

    private void movePosition(PlayerPositionDTO pos, double targetX, double targetY, double maxStep) {
        double dx = targetX - pos.getX();
        double dy = targetY - pos.getY();
        double distance = Math.sqrt(dx * dx + dy * dy);

        // Always apply a minimal movement if not in pause
        double minStep = 0.4;
        double effectiveStep = Math.max(minStep, Math.min(distance, maxStep));
        
        if (distance < 0.05 && effectiveStep <= minStep) {
            // Reached target, apply idle jitter
            pos.setX(clamp(pos.getX() + (random.nextDouble() - 0.5) * 0.5, MIN_X, MAX_X));
            pos.setY(clamp(pos.getY() + (random.nextDouble() - 0.5) * 0.5, MIN_Y, MAX_Y));
            return;
        }

        double factor = effectiveStep / distance;
        pos.setX(clamp(pos.getX() + dx * factor, MIN_X, MAX_X));
        pos.setY(clamp(pos.getY() + dy * factor, MIN_Y, MAX_Y));
    }

    private Player findCenterBackThreat(MatchRuntime rt, PlayerPositionDTO defenderPos, String defenderTeam) {
        List<Player> attackers = "HOME".equals(defenderTeam) ? rt.awayPlayers : rt.homePlayers;
        boolean homeDefender = "HOME".equals(defenderTeam);

        return attackers.stream()
                .filter(player -> player.getPosition() == Position.ATT || player.getPosition() == Position.WNG)
                .filter(player -> {
                    PlayerPositionDTO attackerPos = getPlayerPosition(rt, player);
                    if (attackerPos == null) {
                        return false;
                    }

                    boolean inChannel = Math.abs(attackerPos.getY() - defenderPos.getY()) <= 16.0;
                    boolean inPatrolZone = homeDefender
                            ? attackerPos.getX() >= 18.0 && attackerPos.getX() <= 40.0
                            : attackerPos.getX() <= 82.0 && attackerPos.getX() >= 60.0;
                    return inChannel && inPatrolZone;
                })
                .min(Comparator.comparingDouble(player -> distanceBetween(defenderPos, getPlayerPosition(rt, player))))
                .filter(player -> {
                    PlayerPositionDTO attackerPos = getPlayerPosition(rt, player);
                    return attackerPos != null && distanceBetween(defenderPos, attackerPos) <= CENTER_BACK_ENGAGE_DISTANCE;
                })
                .orElse(null);
    }

    private void applyRoleMovement(MatchRuntime rt, PlayerPositionDTO pos, Player player) {
        String team = pos.getTeam();
        boolean home = "HOME".equals(team);
        boolean inPossession = team.equals(rt.lastTouchTeam);
        boolean upperLane = pos.getY() < 50.0;
        double targetX;
        double targetY;

        // Dynamic roaming for off-ball players
        double roamingX = Math.sin(rt.tick * 0.15 + player.getId()) * 2.5;
        double roamingY = Math.cos(rt.tick * 0.12 + player.getId() * 0.7) * 3.5;

        switch (player.getPosition()) {
            case GK -> {
                double goalX = home ? 8.0 : 92.0;
                targetX = goalX + (rt.ball.getX() - goalX) * 0.08;
                targetY = 50.0 + (rt.ball.getY() - 50.0) * 0.15;
            }
            case DEF -> {
                double flankY = upperLane ? 12.0 : 88.0;
                double centralY = upperLane ? 43.0 : 57.0;
                boolean wideDefender = Math.abs(pos.getY() - flankY) < Math.abs(pos.getY() - centralY);
                
                double baseX = wideDefender
                        ? (home ? (inPossession ? 52.0 : 18.0) : (inPossession ? 48.0 : 82.0))
                        : (home ? (inPossession ? 35.0 : 16.5) : (inPossession ? 65.0 : 83.5));
                
                double baseY = wideDefender ? flankY : centralY;
                
                if (wideDefender && inPossession) {
                    baseY = upperLane ? 6.0 : 94.0;
                }

                targetX = baseX + (rt.ball.getX() - baseX) * (inPossession ? 0.20 : 0.30);
                targetY = baseY + (rt.ball.getY() - baseY) * (wideDefender ? 0.10 : 0.20);
                
                if (!wideDefender) {
                    Player strikerThreat = findCenterBackThreat(rt, pos, team);
                    if (strikerThreat != null) {
                        PlayerPositionDTO threatPos = getPlayerPosition(rt, strikerThreat);
                        if (threatPos != null) {
                            targetX += (threatPos.getX() - pos.getX()) * 0.50;
                            targetY += (threatPos.getY() - pos.getY()) * 0.40;
                        }
                    }
                    targetX = clamp(targetX, home ? 10.0 : 65.0, home ? 45.0 : 90.0);
                }
            }
            case WNG -> {
                double wingY = upperLane ? 8.0 : 92.0;
                double baseX = home ? (inPossession ? 75.0 : 55.0) : (inPossession ? 25.0 : 45.0);
                double advance = inPossession ? (home ? 20.0 : -20.0) : (home ? -5.0 : 5.0);
                
                targetX = baseX + (rt.ball.getX() - baseX) * (inPossession ? 0.40 : 0.25) + advance;
                targetY = wingY + (rt.ball.getY() - wingY) * (inPossession ? 0.15 : 0.18);
                targetX = applyOffsideTolerance(rt, pos, targetX, home);
            }
            case ATT -> {
                double laneY = upperLane ? 40.0 : 60.0;
                double baseX = home ? (inPossession ? 85.0 : 65.0) : (inPossession ? 15.0 : 35.0);
                double offensivePush = inPossession ? (home ? 14.0 : -14.0) : 0;
                
                targetX = baseX + (rt.ball.getX() - baseX) * (inPossession ? 0.35 : 0.20) + offensivePush;
                targetY = laneY + (rt.ball.getY() - laneY) * 0.25;
                targetX = applyOffsideTolerance(rt, pos, targetX, home);
            }
            case MID -> {
                double laneY = upperLane ? 30.0 : 70.0;
                double baseX = home ? (inPossession ? 62.0 : 42.0) : (inPossession ? 38.0 : 58.0);
                
                targetX = baseX + (rt.ball.getX() - baseX) * (inPossession ? 0.30 : 0.32);
                targetY = laneY + (rt.ball.getY() - laneY) * (inPossession ? 0.20 : 0.25);
            }
            default -> {
                targetX = baseAnchorX(player, team, inPossession);
                targetY = baseAnchorY(player, team);
            }
        }

        if (player.getPosition() != Position.GK) {
            double directionalNudge = inPossession ? (home ? 1.5 : -1.5) : (home ? -0.8 : 0.8);
            targetX += directionalNudge;
        }

        targetX += (random.nextDouble() - 0.5) * 1.8;
        targetY += (random.nextDouble() - 0.5) * 2.8;

        movePosition(pos, targetX + roamingX, targetY + roamingY, SUPPORT_STEP);
    }

    private void spreadSameTeamPlayers(MatchRuntime rt) {
        separateCluster(rt.homePlayers, rt);
        separateCluster(rt.awayPlayers, rt);
        separateSameLanePlayers(rt.homePlayers, rt);
        separateSameLanePlayers(rt.awayPlayers, rt);
    }

    private void separateCluster(List<Player> teamPlayers, MatchRuntime rt) {
        final double minDistance = 7.2;
        for (int i = 0; i < teamPlayers.size(); i++) {
            PlayerPositionDTO a = getPlayerPosition(rt, teamPlayers.get(i));
            if (a == null) {
                continue;
            }
            for (int j = i + 1; j < teamPlayers.size(); j++) {
                PlayerPositionDTO b = getPlayerPosition(rt, teamPlayers.get(j));
                if (b == null) {
                    continue;
                }
                double dx = a.getX() - b.getX();
                double dy = a.getY() - b.getY();
                double distance = Math.hypot(dx, dy);
                if (distance >= minDistance) {
                    continue;
                }

                double safeDx = Math.abs(dx) < 0.01 ? (random.nextBoolean() ? 1.0 : -1.0) : dx;
                double safeDy = Math.abs(dy) < 0.01 ? (random.nextBoolean() ? 0.8 : -0.8) : dy;
                double factor = (minDistance - Math.max(distance, 0.1)) / 1.6;
                double norm = Math.hypot(safeDx, safeDy);
                double pushX = (safeDx / norm) * factor;
                double pushY = (safeDy / norm) * factor;

                a.setX(clamp(a.getX() + pushX, MIN_X, MAX_X));
                a.setY(clamp(a.getY() + pushY, MIN_Y, MAX_Y));
                b.setX(clamp(b.getX() - pushX, MIN_X, MAX_X));
                b.setY(clamp(b.getY() - pushY, MIN_Y, MAX_Y));
            }
        }
    }

    private void separateSameLanePlayers(List<Player> teamPlayers, MatchRuntime rt) {
        final double laneDistanceY = 8.0;
        final double laneDistanceX = 6.5;
        final double minLaneGap = 9.0;

        for (int i = 0; i < teamPlayers.size(); i++) {
            Player first = teamPlayers.get(i);
            PlayerPositionDTO a = getPlayerPosition(rt, first);
            if (a == null || first.getPosition() == Position.GK) {
                continue;
            }

            for (int j = i + 1; j < teamPlayers.size(); j++) {
                Player second = teamPlayers.get(j);
                PlayerPositionDTO b = getPlayerPosition(rt, second);
                if (b == null || second.getPosition() == Position.GK) {
                    continue;
                }

                double yGap = Math.abs(a.getY() - b.getY());
                double xGap = Math.abs(a.getX() - b.getX());
                if (yGap > laneDistanceY || xGap > laneDistanceX) {
                    continue;
                }

                double pushY = ((minLaneGap - yGap) / 2.0) + 0.8;
                if (a.getY() <= b.getY()) {
                    a.setY(clamp(a.getY() - pushY, MIN_Y, MAX_Y));
                    b.setY(clamp(b.getY() + pushY, MIN_Y, MAX_Y));
                } else {
                    a.setY(clamp(a.getY() + pushY, MIN_Y, MAX_Y));
                    b.setY(clamp(b.getY() - pushY, MIN_Y, MAX_Y));
                }

                double directionalPush = ((laneDistanceX - xGap) / 2.5) + 0.6;
                if (first.getPosition() == Position.ATT || first.getPosition() == Position.WNG) {
                    a.setX(clamp(a.getX() + ("HOME".equals(a.getTeam()) ? directionalPush : -directionalPush), MIN_X, MAX_X));
                }
                if (second.getPosition() == Position.ATT || second.getPosition() == Position.WNG) {
                    b.setX(clamp(b.getX() + ("HOME".equals(b.getTeam()) ? directionalPush : -directionalPush), MIN_X, MAX_X));
                }
            }
        }
    }

    private void rememberPassPair(MatchRuntime rt, Player passer, Player receiver, String team) {
        rt.previousPassFromId = rt.lastPassFromId;
        rt.previousPassToId = rt.lastPassToId;
        rt.previousPassPairTick = rt.lastPassPairTick;
        rt.lastPassFromId = Math.toIntExact(passer.getId());
        rt.lastPassToId = Math.toIntExact(receiver.getId());
        rt.lastPassPairTick = rt.tick;
        if ("HOME".equals(team)) {
            rt.homeLastReceiverId = Math.toIntExact(receiver.getId());
        } else if ("AWAY".equals(team)) {
            rt.awayLastReceiverId = Math.toIntExact(receiver.getId());
        }
    }

    private void startPassTransit(MatchRuntime rt, Player passer, Player receiver, String team, double scatter) {
        PlayerPositionDTO passerPos = getPlayerPosition(rt, passer);
        PlayerPositionDTO receiverPos = getPlayerPosition(rt, receiver);
        if (passerPos == null || receiverPos == null) {
            return;
        }

        // Ball starts EXACTLY at passer's position
        rt.ball = new BallPositionDTO(passerPos.getX(), passerPos.getY());

        double targetX = clamp(receiverPos.getX() + (random.nextDouble() - 0.5) * scatter, MIN_X, MAX_X);
        double targetY = clamp(receiverPos.getY() + (random.nextDouble() - 0.5) * scatter, MIN_Y, MAX_Y);
        double distance = Math.hypot(targetX - rt.ball.getX(), targetY - rt.ball.getY());

        rt.currentCarrier = null; // No carrier while in flight
        rt.ballInTransit = true;
        rt.ballTransitCanBeIntercepted = true;
        rt.ballTransitTargetX = targetX;
        rt.ballTransitTargetY = targetY;
        rt.ballTransitTicks = 0;
        // Adjust ticks based on distance to make it look natural
        rt.ballTransitMaxTicks = Math.max(2, Math.min(6, (int) Math.round(distance / 6.5)));
        rt.pendingReceiverId = Math.toIntExact(receiver.getId());
        rt.pendingPasserId = Math.toIntExact(passer.getId());
        rt.pendingPassTeam = team;
        rt.lastTouchTeam = team;
    }

    private void releaseBall(MatchRuntime rt, Player target, String recoveringTeam, Integer pendingReceiverId, Integer pendingPasserId, double scatter) {
        PlayerPositionDTO targetPos = getPlayerPosition(rt, target);
        if (targetPos == null) {
            return;
        }
        double startX = rt.ball != null ? rt.ball.getX() : targetPos.getX();
        double startY = rt.ball != null ? rt.ball.getY() : targetPos.getY();
        double intendedX = clamp(targetPos.getX() + (random.nextDouble() - 0.5) * scatter, MIN_X, MAX_X);
        double intendedY = clamp(targetPos.getY() + (random.nextDouble() - 0.5) * scatter, MIN_Y, MAX_Y);
        double dx = intendedX - startX;
        double dy = intendedY - startY;
        double distance = Math.hypot(dx, dy);
        double maxTravel = pendingReceiverId != null ? 10.0 : 8.0;
        double factor = distance > 0.01 ? Math.min(1.0, maxTravel / distance) : 0.0;

        rt.currentCarrier = null;
        rt.ballInTransit = true;
        rt.ballTransitCanBeIntercepted = false;
        rt.ballTransitTargetX = intendedX;
        rt.ballTransitTargetY = intendedY;
        rt.ballTransitTicks = 0;
        rt.ballTransitMaxTicks = Math.max(2, Math.min(5, (int) Math.round(distance / 5.0)));
        rt.pendingReceiverId = pendingReceiverId;
        rt.pendingPasserId = pendingPasserId;
        rt.lastTouchTeam = recoveringTeam;
        rt.lastPassReceiverId = null;
        rt.lastPassReceiveTick = -100;
        rt.lastPassReceiveTeam = null;
        rt.lastDuelWinnerId = null;
        rt.lastDuelWinTick = -100;
        rt.lastDuelWinTeam = null;
        rt.lastRecoveryPlayerId = null;
        rt.lastRecoveryTick = -100;
        rt.lastRecoveryTeam = null;
        rt.ball = new BallPositionDTO(
                clamp(startX + dx * factor, MIN_X, MAX_X),
                clamp(startY + dy * factor, MIN_Y, MAX_Y)
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

    private boolean isInPenaltyBox(PlayerPositionDTO carrier, boolean attacksRight) {
        if (carrier == null) {
            return false;
        }
        if (attacksRight) {
            return carrier.getX() >= 84.0 && carrier.getY() >= 22.0 && carrier.getY() <= 78.0;
        }
        return carrier.getX() <= 16.0 && carrier.getY() >= 22.0 && carrier.getY() <= 78.0;
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

    private boolean isPlayerControllingBall(MatchRuntime rt, Player player) {
        PlayerPositionDTO playerPos = getPlayerPosition(rt, player);
        if (playerPos == null || rt.ball == null) {
            return false;
        }
        return distanceBetween(playerPos, new PlayerPositionDTO(-1, "", rt.ball.getX(), rt.ball.getY(), 0, 0)) <= CARRIER_CONTROL_RADIUS;
    }

    private boolean canShootNow(MatchRuntime rt, Player shooter, String ballTeam) {
        if (rt.currentCarrier == null || shooter == null || shooter.getId() == null) {
            return false;
        }
        if (rt.currentCarrier.getId() != Math.toIntExact(shooter.getId())) {
            return false;
        }
        if (!isPlayerControllingBall(rt, shooter)) {
            return false;
        }
        if (!Objects.equals(rt.lastTouchTeam, ballTeam)) {
            return false;
        }
        if (!isOnOpponentHalf(rt, shooter, ballTeam)) {
            return false;
        }
        if (!isDangerousAttackingPosition(rt, shooter, ballTeam)) {
            return false;
        }

        int shooterId = Math.toIntExact(shooter.getId());
        boolean receivedShotPass = Objects.equals(rt.lastPassReceiverId, shooterId)
                && Objects.equals(rt.lastPassReceiveTeam, ballTeam)
                && (rt.tick - rt.lastPassReceiveTick) <= SHOT_WINDOW_TICKS
                && isInShotZone(ballTeam, rt.lastPassReceiveX);

        boolean wonDuelHigh = Objects.equals(rt.lastDuelWinnerId, shooterId)
                && Objects.equals(rt.lastDuelWinTeam, ballTeam)
                && (rt.tick - rt.lastDuelWinTick) <= SHOT_WINDOW_TICKS
                && isOnOpponentHalf(ballTeam, rt.lastDuelWinX);

        boolean recoveredHigh = Objects.equals(rt.lastRecoveryPlayerId, shooterId)
                && Objects.equals(rt.lastRecoveryTeam, ballTeam)
                && (rt.tick - rt.lastRecoveryTick) <= SHOT_WINDOW_TICKS
                && isOnOpponentHalf(ballTeam, rt.lastRecoveryX);

        return receivedShotPass || wonDuelHigh || recoveredHigh;
    }

    private boolean isOnOpponentHalf(MatchRuntime rt, Player player, String team) {
        PlayerPositionDTO pos = getPlayerPosition(rt, player);
        if (pos == null) {
            return false;
        }
        return isOnOpponentHalf(team, pos.getX());
    }

    private boolean isOnOpponentHalf(String team, double x) {
        return "HOME".equals(team) ? x > 50.0 : x < 50.0;
    }

    private boolean isInShotZone(String team, double x) {
        return "HOME".equals(team) ? x >= 66.0 : x <= 34.0;
    }

    private void maybeCreateVarReview(GoalEvent goal, PenaltyEvent penalty, MatchRuntime rt, Match match, int minute) {
        if (goal == null && penalty == null) {
            return;
        }
        if (random.nextDouble() >= 0.20) {
            return;
        }

        VARReviewEvent var = new VARReviewEvent();
        var.setMinute(minute);
        var.setMatch(match);

        if (goal != null) {
            var.setReviewedGoalEvent(goal);
            var.setNumber(1);
            if (random.nextDouble() < 0.80) {
                var.setDecision("Confirmed");
            } else {
                var.setDecision("Overturned");
                var.setOverturnReason(randomOverturnReason());
                goal.setScored(false);
                rt.runtimeGoals.remove(goal);
                if (goal.getTeam() != null && goal.getTeam().equals(match.getHomeTeam())) {
                    rt.homeGoals = Math.max(0, rt.homeGoals - 1);
                } else {
                    rt.awayGoals = Math.max(0, rt.awayGoals - 1);
                }
            }
        } else {
            var.setReviewedPenaltyEvent(penalty);
            var.setNumber(2);
            if (random.nextDouble() < 0.80) {
                var.setDecision("Confirmed");
            } else {
                var.setDecision("Overturned");
                var.setOverturnReason("encroachment");
                penalty.setScored(false);
            }
        }

        rt.runtimeEvents.add(var);
    }

    private String randomOverturnReason() {
        double roll = random.nextDouble();
        if (roll < 0.45) return "offside";
        if (roll < 0.80) return "foul in build-up";
        return "handball";
    }

    private boolean isOffsideReceiver(MatchRuntime rt, Player passer, Player receiver, String attackingTeam) {
        if (receiver == null || passer == null || receiver.getId() == null || passer.getId() == null) {
            return false;
        }
        if (receiver.getPosition() == Position.GK || Objects.equals(receiver.getId(), passer.getId())) {
            return false;
        }

        PlayerPositionDTO receiverPos = getPlayerPosition(rt, receiver);
        PlayerPositionDTO passerPos = getPlayerPosition(rt, passer);
        if (receiverPos == null || passerPos == null) {
            return false;
        }

        boolean homeAttack = "HOME".equals(attackingTeam);
        double offsideLine = calculateOffsideLine(rt, attackingTeam);
        boolean aheadOfBall = homeAttack
                ? receiverPos.getX() > passerPos.getX() + 1.0
                : receiverPos.getX() < passerPos.getX() - 1.0;
        boolean inOppositionHalf = homeAttack ? receiverPos.getX() > 50.0 : receiverPos.getX() < 50.0;
        boolean beyondLine = homeAttack
                ? receiverPos.getX() > offsideLine + 1.0
                : receiverPos.getX() < offsideLine - 1.0;

        return aheadOfBall && inOppositionHalf && beyondLine;
    }

    private double calculateOffsideLine(MatchRuntime rt, String attackingTeam) {
        List<Player> defenders = "HOME".equals(attackingTeam) ? rt.awayPlayers : rt.homePlayers;
        List<Double> defenderLine = defenders.stream()
                .filter(player -> player.getPosition() != Position.GK)
                .map(player -> getPlayerPosition(rt, player))
                .filter(Objects::nonNull)
                .map(PlayerPositionDTO::getX)
                .sorted()
                .toList();

        if (defenderLine.isEmpty()) {
            return "HOME".equals(attackingTeam) ? 95.0 : 5.0;
        }

        if ("HOME".equals(attackingTeam)) {
            return defenderLine.size() >= 2 ? defenderLine.get(defenderLine.size() - 2) : defenderLine.get(defenderLine.size() - 1);
        }
        return defenderLine.size() >= 2 ? defenderLine.get(1) : defenderLine.get(0);
    }

    private double applyOffsideTolerance(MatchRuntime rt, PlayerPositionDTO pos, double targetX, boolean homeTeam) {
        if (rt.currentCarrier == null || !Objects.equals(rt.currentCarrier.getTeam(), pos.getTeam())) {
            pos.setOffsideTicksRemaining(0);
            pos.setRetreatTicksRemaining(0);
            return targetX;
        }

        double line = calculateOffsideLine(rt, pos.getTeam());
        double tolerance = 1.0;
        boolean isOffside = homeTeam ? pos.getX() > line + tolerance : pos.getX() < line - tolerance;
        if (!isOffside) {
            pos.setOffsideTicksRemaining(0);
            pos.setRetreatTicksRemaining(0);
            return targetX;
        }

        if (pos.getOffsideTicksRemaining() >= 2) {
            if (pos.getRetreatTicksRemaining() < MAX_RETREAT_TICKS) {
                pos.setRetreatTicksRemaining(pos.getRetreatTicksRemaining() + 1);
            }
            double progress = pos.getRetreatTicksRemaining() / (double) MAX_RETREAT_TICKS;
            double effectiveForce = RETREAT_FORCE + DEEP_RETREAT_FORCE * progress;
            targetX = homeTeam ? (line - effectiveForce) : (line + effectiveForce);
        } else {
            targetX = homeTeam ? Math.min(targetX, line + 0.8) : Math.max(targetX, line - 0.8);
        }

        pos.setOffsideTicksRemaining(pos.getOffsideTicksRemaining() + 1);
        return targetX;
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

    /**
     * Resets players to their formation zones for a restart (Goal, Offside, Goal Kick)
     * Now performs a smooth transition over multiple ticks instead of teleporting.
     */
    private void resetPositionsForRestart(MatchRuntime rt, String teamInPossession) {
        // 1. Determine target positions for all players
        Map<Integer, double[]> targetMap = new HashMap<>();
        
        double[] homeFormation = getHomeFormationPositions();
        List<Player> homeSorted = rt.homePlayers.stream()
                .sorted(Comparator.comparingInt(p -> positionPriority(p.getPosition(), "HOME")))
                .toList();
        for (int i = 0; i < homeSorted.size(); i++) {
            targetMap.put(Math.toIntExact(homeSorted.get(i).getId()), 
                    new double[]{homeFormation[i * 2], homeFormation[i * 2 + 1]});
        }
        
        double[] awayFormation = getAwayFormationPositions();
        List<Player> awaySorted = rt.awayPlayers.stream()
                .sorted(Comparator.comparingInt(p -> positionPriority(p.getPosition(), "AWAY")))
                .toList();
        for (int i = 0; i < awaySorted.size(); i++) {
            targetMap.put(Math.toIntExact(awaySorted.get(i).getId()), 
                    new double[]{awayFormation[i * 2], awayFormation[i * 2 + 1]});
        }
        
        // 2. Transition over multiple ticks
        int transitionTicks = 12; // About 1.5 - 2 seconds of smooth movement
        for (int t = 0; t < transitionTicks; t++) {
            rt.tick++; // Advance game clock during transition
            final int remainingSteps = transitionTicks - t;
            
            rt.players.forEach(pos -> {
                double[] target = targetMap.get(pos.getId());
                if (target != null) {
                    double dx = target[0] - pos.getX();
                    double dy = target[1] - pos.getY();
                    double dist = Math.sqrt(dx * dx + dy * dy);
                    if (dist > 0.1) {
                        // Move a fraction of the remaining distance
                        double step = dist / remainingSteps;
                        pos.setX(pos.getX() + dx * (step / dist));
                        pos.setY(pos.getY() + dy * (step / dist));
                    }
                }
            });
            
            // Move ball slowly towards the center if needed, or just let it stay
            if (rt.ball != null && rt.currentCarrier == null) {
               // Optional: move ball towards center or restart spot
            }
            
            rt.recordTick();
        }
        
        log.debug("Smooth transition finished for restart ({} ticks)", transitionTicks);
    }

    private Player selectRestartPlayer(MatchRuntime rt, String team) {
        List<Player> players = "HOME".equals(team) ? rt.homePlayers : rt.awayPlayers;
        
        // 1. Prefer Goalkeeper
        Player gk = players.stream()
                .filter(p -> p.getPosition() == Position.GK)
                .findFirst()
                .orElse(null);
        if (gk != null && random.nextDouble() < 0.7) return gk;

        // 2. Prefer Defenders
        List<Player> defenders = players.stream()
                .filter(p -> p.getPosition() == Position.DEF)
                .toList();
                
        if (!defenders.isEmpty()) {
            return defenders.get(random.nextInt(defenders.size()));
        }
        
        // 3. Last resort: any non-striker
        return players.stream()
                .filter(p -> p.getPosition() != Position.ATT && p.getPosition() != Position.WNG)
                .findFirst()
                .orElse(players.isEmpty() ? null : players.getFirst());
    }

    private Player selectWideRestartPlayer(MatchRuntime rt, String team, boolean upperSide) {
        List<Player> players = "HOME".equals(team) ? rt.homePlayers : rt.awayPlayers;
        double preferredY = upperSide ? 18.0 : 82.0;

        return players.stream()
                .filter(player -> player.getPosition() != Position.GK)
                .min(Comparator.comparingDouble(player -> {
                    PlayerPositionDTO pos = getPlayerPosition(rt, player);
                    double laneDistance = pos != null ? Math.abs(pos.getY() - preferredY) : 100.0;
                    double rolePenalty = switch (player.getPosition()) {
                        case WNG, MID -> 0.0;
                        case DEF -> 4.0;
                        case ATT -> 6.0;
                        default -> 10.0;
                    };
                    return laneDistance + rolePenalty;
                }))
                .orElse(selectRestartPlayer(rt, team));
    }

    private String oppositeTeam(String team) {
        return "HOME".equals(team) ? "AWAY" : "HOME";
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}

