package org.example.footballmanager.engines;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.dto.BallPositionDTO;
import org.example.footballmanager.dto.PlayerPositionDTO;
import org.example.footballmanager.dto.TacticsSetPieceDTO;
import org.example.footballmanager.dto.TacticsSlotDTO;
import org.example.footballmanager.model.*;
import org.example.footballmanager.model.event.FreeKickEvent;
import org.example.footballmanager.model.event.GoalEvent;
import org.example.footballmanager.model.event.PenaltyEvent;
import org.example.footballmanager.model.event.VARReviewEvent;
import org.example.footballmanager.repository.*;
import org.example.footballmanager.service.FormationSlotCatalog;
import org.example.footballmanager.service.SeasonService;
import org.example.footballmanager.service.TeamTacticsService;
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
    private final TeamTacticsService teamTacticsService;
    
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
    private static final double SHOT_TRIGGER_DISTANCE = 24.5;
    private static final double CARRIER_CONTROL_RADIUS = 0.5; // VERY TIGHT CONTROL
    private static final double PENDING_RECEIVER_LOCK_DISTANCE = 20.0;
    private static final double RECEIVER_PRIORITY_MARGIN = 2.2;
    private static final int MAX_RETREAT_TICKS = 6;
    private static final int OFFSIDE_RETREAT_TRIGGER_STREAK = 2;
    private static final double RETREAT_FORCE = 15.0;
    private static final double DEEP_RETREAT_FORCE = 28.0;
    private static final double ONSIDE_BUFFER = 1.8;
    private static final double CENTER_BACK_ENGAGE_DISTANCE = 16.0;
    private static final double TACTICAL_DISCIPLINE = 1.0;
    private static final double DEFENSIVE_LINE_GAP = 10.0;
    private static final double DEFENSIVE_COVER_GAP = 4.5;
    private static final double VAR_GOAL_REVIEW_CHANCE = 0.42;
    private static final double VAR_PENALTY_REVIEW_CHANCE = 0.72;
    private static final int SHOT_WINDOW_TICKS = 3;
    private static final double DUEL_FOUL_CHANCE = 0.034;
    private static final double PENALTY_FOUL_CHANCE = 0.038;
    private static final double RECKLESS_DUEL_CARD_CHANCE = 0.012;
    private static final double OVERLAP_DUEL_DISTANCE = 1.55;
    private static final double DEFENSIVE_MARK_RADIUS = 8.5;
    private static final double DANGER_PRESS_DISTANCE_TO_GOAL = 32.0;
    private static final double RESTART_BLEND_RATIO = 0.62;
    private static final double KICKOFF_RESTART_BLEND_RATIO = 0.56;
    private static final double RESTART_BLEND_MAX_X_SHIFT = 18.0;
    private static final double RESTART_BLEND_MAX_Y_SHIFT = 14.0;
    private static final double PENALTY_AREA_MIN_Y = 22.0;
    private static final double PENALTY_AREA_MAX_Y = 78.0;
    private static final double GOAL_KICK_EXIT_X = 22.0;
    private static final double PENALTY_SPOT_X = 88.0;
    private static final double PENALTY_KEEPER_X = 96.0;
    private static final int PENALTY_PRE_SHOT_PAUSE_TICKS = 4;
    private static final int PENALTY_POST_SHOT_PAUSE_TICKS = 4;
    private static final int RESTART_PAUSE_TICKS = 3;
    private static final int CORNER_TAKER_APPROACH_TICKS = 4;
    private static final int CORNER_PRE_CROSS_PAUSE_TICKS = 3;
    private static final double CORNER_TAKER_APPROACH_STEP = 2.3;
    private static final double GOALKEEPER_TACTICAL_MAX_X_DRIFT = 2.8;
    private static final double GOALKEEPER_TACTICAL_MAX_Y_DRIFT = 3.8;
    private static final double GOALKEEPER_HARD_AREA_MIN_X_HOME = 1.5;
    private static final double GOALKEEPER_HARD_AREA_MAX_X_HOME = 13.5;
    private static final double GOALKEEPER_HARD_AREA_MIN_X_AWAY = 86.5;
    private static final double GOALKEEPER_HARD_AREA_MAX_X_AWAY = 98.5;
    private static final double GOALKEEPER_HARD_AREA_MIN_Y = 36.0;
    private static final double GOALKEEPER_HARD_AREA_MAX_Y = 64.0;
    private static final double GOALKEEPER_SHOT_COVERAGE_X = 7.5;
    private static final double GOALKEEPER_SHOT_COVERAGE_Y = 9.5;
    private static final double[] ANCHOR_X_CENTERS = {10.0, 22.0, 38.0, 52.0, 66.0};
    private static final double[] TARGET_X_CENTERS = {10.0, 30.0, 50.0, 70.0, 90.0};
    private static final double[] BAND_Y_CENTERS = {18.0, 34.0, 50.0, 66.0, 82.0};

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

            updateFatigue(rt, minute);
            maybeGeneratePeriodicalEvent(rt, match, minute);
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
        rt.home = new ArrayList<>(Stream.concat(
                match.getHomeLineup().getOrderedStartingPlayers().stream(),
                match.getHomeLineup().getOrderedSubstitutePlayers().stream()
        ).toList());
        rt.away = new ArrayList<>(Stream.concat(
                match.getAwayLineup().getOrderedStartingPlayers().stream(),
                match.getAwayLineup().getOrderedSubstitutePlayers().stream()
        ).toList());
        rt.homeSquad = new ArrayList<>(rt.homePlayers);
        rt.awaySquad = new ArrayList<>(rt.awayPlayers);
        rt.home.forEach(player -> {
            if (player != null && player.getId() != null) {
                rt.playerTeamSide.put(player.getId(), "HOME");
            }
        });
        rt.away.forEach(player -> {
            if (player != null && player.getId() != null) {
                rt.playerTeamSide.put(player.getId(), "AWAY");
            }
        });

        initializeTacticalProfiles(rt, match);
        
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
        rt.currentPossessionTeam = "HOME";
        rt.possessionStartTick = 0;
        rt.possessionStartX = 50.0;
        rt.possessionStartY = 50.0;
        rt.possessionPhase = MatchRuntime.PossessionPhase.BUILD_UP;
        rt.pendingPasserId = null;
        rt.pendingPassTeam = null;
        
        log.debug("Runtime initialized. Home: {} vs Away: {}", 
                rt.homeTeam.getName(), rt.awayTeam.getName());
    }

    /**
     * Initializes player positions on the pitch (4-4-2 vs 4-2-3-1)
     */
    private void initializePlayerPositions(MatchRuntime rt) {
        List<PlayerPositionDTO> homePositions = new ArrayList<>();
        List<PlayerPositionDTO> awayPositions = new ArrayList<>();
        placeTeamInFormation(homePositions, rt.homePlayers, "HOME", rt.homeSlots, getHomeFormationPositions());
        placeTeamInFormation(awayPositions, rt.awayPlayers, "AWAY", rt.awaySlots, getAwayFormationPositions());
        
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
        updatePossessionPhase(rt);
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

            Player forcedDefender = findImmediateBallPressureDefender(rt, ballCarrier);
            if (forcedDefender != null) {
                handleDuel(rt, match, minute, ballCarrier, forcedDefender);
            } else {
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
                        case PASS -> handlePass(rt, match, minute, ballCarrier, decision, ballTeam);
                        case SHOT -> handleShot(rt, match, minute, ballCarrier, decision, ballTeam);
                        case DRIBBLE -> handleDribble(rt, match, minute, ballCarrier, decision, ballTeam);
                    }

                    if (rt.currentCarrier != null && shouldTriggerCarrierOutOfBounds(rt, ballCarrier, ballTeam)) {
                        handleBallOutOfBounds(rt, match, minute);
                    }
                }
            }
        }

        updateSupportingMovement(rt);
        syncBallState(rt);
        updatePossessionPhase(rt);
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
            
            Player restartPlayer = selectRestartPlayer(rt, defendingTeam, "DEFENSIVE_RESTART");
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
        rememberAssistChain(rt, passer, receiver, ballTeam);
        startPassTransit(rt, passer, receiver, ballTeam, resolvePassScatter(passer, receiver));
        advanceAttackingShape(rt, passer, receiver, ballTeam, resolveBallProgressionStep(receiver, 8.0));
        
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

        PlayerPositionDTO shooterPos = getPlayerPosition(rt, shooter);
        List<Player> nearbyDefenders = getNearbyDefenders(rt, shooter, ballTeam);
        Player shotBlocker = nearbyDefenders.isEmpty() ? null : nearbyDefenders.getFirst();
        if (isDangerousAttackingPosition(rt, shooter, ballTeam)
                && rt.possessionPhase.ordinal() >= MatchRuntime.PossessionPhase.FINAL_THIRD.ordinal()) {
            eventGenerator.createChanceEvent(rt, match, minute, shooter, true);
        }

        String defendingTeam = ballTeam.equals("HOME") ? "AWAY" : "HOME";
        Player goalkeeper = getGoalkeeper(rt, defendingTeam);
        boolean openGoal = !isGoalkeeperProtectingGoal(rt, goalkeeper, defendingTeam);
        DuelResolver.DuelResult duelResult = openGoal
                ? duelResolver.resolveOpenGoalShot(
                        shooter,
                        shooterPos != null ? shooterPos.getX() : 85.0,
                        shooterPos != null ? shooterPos.getY() : 50.0)
                : duelResolver.resolveShotDuel(
                        shooter,
                        goalkeeper,
                        shooterPos != null ? shooterPos.getX() : 85.0,
                        shooterPos != null ? shooterPos.getY() : 50.0);

        if (shotBlocker != null && shouldTriggerShotDeflection(rt, shooter, shotBlocker, openGoal)) {
            PlayerPositionDTO blockerPos = getPlayerPosition(rt, shotBlocker);
            eventGenerator.createShotMissedEvent(rt, match, minute, shooter, duelResult.getXG());
            log.info("🪃 DEFLECTION! {}'s shot is blocked by {}", shooter.getName(), shotBlocker.getName());
            movePlayerTowardsBall(rt, shotBlocker, 2.4);
            startRandomDeflection(
                    rt,
                    shotBlocker,
                    blockerPos != null ? blockerPos.getX() : (shooterPos != null ? shooterPos.getX() : 50.0),
                    blockerPos != null ? blockerPos.getY() : (shooterPos != null ? shooterPos.getY() : 50.0),
                    5.5,
                    15.0
            );
            rt.pendingPasserId = null;
            rt.pendingPassTeam = null;
            return;
        }

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
            recordGoalSnapshotBeforeRestart(rt, shooter, goalkeeper, ballTeam);
            
            // KICK-OFF RESTART
            String restartTeam = ballTeam.equals("HOME") ? "AWAY" : "HOME";
            resetPositionsForRestart(rt, restartTeam, "KICKOFF");
            
            rt.pendingPasserId = null;
            rt.pendingPassTeam = null;
            Player kickoffPlayer = selectRestartPlayer(rt, restartTeam, "KICKOFF");
            rt.ball = new BallPositionDTO(50, 50);
            rt.currentCarrier = null;
            rt.pendingReceiverId = kickoffPlayer != null ? Math.toIntExact(kickoffPlayer.getId()) : null;
            rt.lastTouchTeam = restartTeam;
        } else if (!openGoal && goalkeeper != null && duelResult.isSaved()) {
            movePlayerTowardsGoal(rt, shooter, ballTeam, 6.0);
            eventGenerator.createShotSavedEvent(rt, match, minute, shooter, goalkeeper, duelResult.getXG());
            log.info("🧤 SAVE! {} saved by {} (xG: {:.2f})", shooter.getName(), goalkeeper.getName(), duelResult.getXG());

            PlayerPositionDTO goalkeeperPos = getPlayerPosition(rt, goalkeeper);
            double saveOutcomeRoll = random.nextDouble();
            double reboundChance = clamp(0.28 + duelResult.getXG() * 0.34, 0.28, 0.54);
            if (saveOutcomeRoll < reboundChance) {
                startGoalkeeperParryRebound(
                        rt,
                        goalkeeper,
                        goalkeeperPos != null ? goalkeeperPos.getX() : (ballTeam.equals("HOME") ? 94.0 : 6.0),
                        goalkeeperPos != null ? goalkeeperPos.getY() : 50.0,
                        ballTeam
                );
            } else if (saveOutcomeRoll < reboundChance + 0.23) {
                // Chance for corner instead of always catching
                String restartTeam = ballTeam; // attacking team keeps ball for corner
                boolean upperSide = shooterPos != null ? shooterPos.getY() < 50.0 : random.nextBoolean();
                setupCornerRestart(rt, match, minute, restartTeam, upperSide);
            } else if (saveOutcomeRoll < reboundChance + 0.38) {
                startRandomDeflection(
                        rt,
                        goalkeeper,
                        goalkeeperPos != null ? goalkeeperPos.getX() : (ballTeam.equals("HOME") ? 94.0 : 6.0),
                        goalkeeperPos != null ? goalkeeperPos.getY() : 50.0,
                        6.0,
                        14.5
                );
            } else {
                releaseBall(rt, goalkeeper, getTeam(goalkeeper, rt), Math.toIntExact(goalkeeper.getId()), null, 1.2);
            }
            rt.pendingPasserId = null;
            rt.pendingPassTeam = null;
        } else {
            movePlayerTowardsGoal(rt, shooter, ballTeam, 8.0);
            eventGenerator.createShotMissedEvent(rt, match, minute, shooter, duelResult.getXG());
            log.info(openGoal ? "🚨 OPEN GOAL MISS! {} missed (xG: {:.2f})" : "❌ MISS! {} missed (xG: {:.2f})",
                    shooter.getName(), duelResult.getXG());
            
            // GOAL KICK RESTART
            String restartTeam = ballTeam.equals("HOME") ? "AWAY" : "HOME";
            resetPositionsForRestart(rt, restartTeam, "GOAL_KICK");
            boolean upperSide = shooterPos != null ? shooterPos.getY() < 50.0 : random.nextBoolean();
            Player restartPlayer = setupGoalKickRestart(rt, match, minute, restartTeam, "HOME".equals(restartTeam), upperSide);
            if (restartPlayer == null) {
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
        Player forcedDefender = findImmediateBallPressureDefender(rt, dribbler);
        if (forcedDefender != null) {
            handleDuel(rt, match, minute, dribbler, forcedDefender);
            return;
        }

        List<Player> nearbyDefenders = getNearbyDefenders(rt, dribbler, ballTeam);
        PlayerPositionDTO dribblerPos = getPlayerPosition(rt, dribbler);

        if (!nearbyDefenders.isEmpty() && (isCloseContactDefender(rt, dribblerPos, nearbyDefenders.get(0)) || random.nextDouble() < 0.22)) {
            Player defender = nearbyDefenders.get(0);
            handleDuel(rt, match, minute, dribbler, defender);
        } else {
            if (random.nextDouble() < resolveDribbleEventChance(dribbler)) {
                eventGenerator.createDribbleEvent(rt, match, minute, dribbler);
            }
            movePlayerTowardsGoal(rt, dribbler, ballTeam, resolveBallProgressionStep(dribbler, 9.0));
            if (isDangerousAttackingPosition(rt, dribbler, ballTeam)
                    && rt.possessionPhase.ordinal() >= MatchRuntime.PossessionPhase.FINAL_THIRD.ordinal()
                    && random.nextDouble() < resolveChanceCreationChance(dribbler)) {
                eventGenerator.createChanceEvent(rt, match, minute, dribbler, false);
            }
            rt.lastTouchTeam = ballTeam;
            setCurrentCarrier(rt, dribbler, "dribble");

            // The attacker beat the marker and entered the final third: allow an immediate shot attempt,
            // but keep it mostly for cleaner close-range breaks so shot volume does not inflate unrealistically.
            double goalDistance = estimateDistanceToGoal(rt, dribbler, ballTeam);
            List<Player> refreshedDefenders = getNearbyDefenders(rt, dribbler, ballTeam);
            boolean cleanBreak = refreshedDefenders.isEmpty()
                    || (refreshedDefenders.size() == 1 && goalDistance <= 16.0);
            double immediateShotChance = goalDistance <= 12.0 ? 0.60
                    : goalDistance <= 16.0 ? 0.34
                    : goalDistance <= 20.0 ? 0.16
                    : goalDistance <= SHOT_TRIGGER_DISTANCE ? 0.07
                    : 0.0;
            if (goalDistance <= SHOT_TRIGGER_DISTANCE &&
                    cleanBreak &&
                    dribbler.getPosition() != Position.DEF &&
                    dribbler.getPosition() != Position.GK &&
                    immediateShotChance > 0.0 &&
                    random.nextDouble() < immediateShotChance) {
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
            applyDuelFreeze(rt, defender, 4);
        } else {
            releaseBall(rt, defender, getTeam(defender, rt), Math.toIntExact(defender.getId()), null, 2.0);
            rt.pendingPasserId = null;
            rt.pendingPassTeam = null;
            applyDuelFreeze(rt, attacker, 4);
        }

        maybeIssueRecklessDuelCard(rt, match, minute, attacker, defender, result);
    }

    private void applyDuelFreeze(MatchRuntime rt, Player player, int ticks) {
        PlayerPositionDTO position = getPlayerPosition(rt, player);
        if (position == null) {
            return;
        }
        position.setOffsideTicksRemaining(Math.max(position.getOffsideTicksRemaining(), Math.max(3, ticks)));
    }

    private boolean shouldCallFoul(MatchRuntime rt, Player attacker, Player defender) {
        PlayerPositionDTO attackerPos = getPlayerPosition(rt, attacker);
        if (attackerPos == null) {
            return false;
        }
        boolean attacksRight = "HOME".equals(getTeam(attacker, rt));
        boolean inPenaltyBox = isInPenaltyBox(attackerPos, attacksRight);
        if (inPenaltyBox && !isPenaltyFoulLocation(attackerPos, attacksRight)) {
            return false;
        }

        double foulChance = inPenaltyBox ? PENALTY_FOUL_CHANCE : DUEL_FOUL_CHANCE;
        if (defender.getPosition() == Position.DEF) {
            foulChance += inPenaltyBox ? 0.006 : 0.012;
        } else if (defender.getPosition() == Position.MID) {
            foulChance += inPenaltyBox ? 0.004 : 0.008;
        }
        if (isDangerousAttackingPosition(rt, attacker, getTeam(attacker, rt))) {
            foulChance += inPenaltyBox ? 0.004 : 0.002;
        }
        if (inPenaltyBox && defender.getPosition() == Position.GK) {
            foulChance += 0.012;
        }
        return random.nextDouble() < foulChance;
    }

    private void maybeIssueRecklessDuelCard(MatchRuntime rt,
                                            Match match,
                                            int minute,
                                            Player attacker,
                                            Player defender,
                                            DuelResolver.DuelResult result) {
        if (defender == null || defender.getId() == null) {
            return;
        }

        double chance = RECKLESS_DUEL_CARD_CHANCE;
        if (result != null && result.isWon()) {
            chance += 0.006;
        }
        if (defender.getPosition() == Position.DEF) {
            chance += 0.004;
        }
        if (isDangerousAttackingPosition(rt, attacker, getTeam(attacker, rt))) {
            chance += 0.006;
        }

        if (random.nextDouble() < chance) {
            eventGenerator.createYellowCardEvent(rt, match, minute, defender);
        }
    }

    private void maybeIssueFoulCard(MatchRuntime rt,
                                    Match match,
                                    int minute,
                                    Player attacker,
                                    Player defender,
                                    boolean penaltyFoul) {
        if (defender == null || defender.getId() == null) {
            return;
        }

        String attackingTeam = getTeam(attacker, rt);
        double goalDistance = estimateDistanceToGoal(rt, attacker, attackingTeam);
        boolean dangerous = goalDistance <= 24.0 || isDangerousAttackingPosition(rt, attacker, attackingTeam);
        double chance = penaltyFoul ? 0.28 : dangerous ? 0.22 : 0.12;

        if (defender.getPosition() == Position.DEF) {
            chance += 0.04;
        } else if (defender.getPosition() == Position.MID) {
            chance += 0.02;
        } else if (defender.getPosition() == Position.GK && penaltyFoul) {
            chance += 0.03;
        }

        if (random.nextDouble() < chance) {
            eventGenerator.createYellowCardEvent(rt, match, minute, defender);
        }
    }

    private void handleFoul(MatchRuntime rt, Match match, int minute, Player attacker, Player defender) {
        String attackingTeam = getTeam(attacker, rt);
        boolean attacksRight = "HOME".equals(attackingTeam);
        PlayerPositionDTO attackerPos = getPlayerPosition(rt, attacker);
        if (attackerPos == null) {
            return;
        }

        if (isInPenaltyBox(attackerPos, attacksRight)) {
            clearAssistChain(rt);
            String defendingTeam = attacksRight ? "AWAY" : "HOME";
            Player goalkeeper = getGoalkeeper(rt, defendingTeam);
            Player penaltyTaker = resolveSetPieceTaker(rt, attackingTeam, resolvePenaltyTakerSlot(rt, attackingTeam), attacker);
            Player effectiveTaker = penaltyTaker != null ? penaltyTaker : attacker;

            preparePenaltySetup(rt, effectiveTaker, goalkeeper, attackingTeam, attacksRight);
            recordStoppagePause(rt, MatchRuntime.StoppageType.PENALTY, PENALTY_PRE_SHOT_PAUSE_TICKS);

            PenaltyEvent penalty = new PenaltyEvent();
            penalty.setMinute(minute);
            penalty.setTick(rt.tick);
            penalty.setMatch(match);
            penalty.setTeam("HOME".equals(attackingTeam) ? match.getHomeTeam() : match.getAwayTeam());
            penalty.setTaker(effectiveTaker);

            DuelResolver.DuelResult penResult = duelResolver.resolvePenalty(effectiveTaker, goalkeeper);
            penalty.setScored(penResult.isGoal());
            rt.runtimeEvents.add(penalty);

            GoalEvent goalEvent = null;
            if (penResult.isGoal()) {
                if ("HOME".equals(attackingTeam)) {
                    rt.homeGoals++;
                } else {
                    rt.awayGoals++;
                }
                goalEvent = eventGenerator.createGoalEvent(rt, match, minute, effectiveTaker, null, penResult.getXG());
                log.info("⚽ PENALTY GOAL! {} scores for {} (xG: {:.2f})", effectiveTaker.getName(), attackingTeam, penResult.getXG());

                rt.currentCarrier = null;
                rt.ball = new BallPositionDTO(attacksRight ? MAX_X : MIN_X, 50.0);
                recordStoppagePause(rt, MatchRuntime.StoppageType.PENALTY, PENALTY_POST_SHOT_PAUSE_TICKS);
                recordGoalSnapshotBeforeRestart(rt, effectiveTaker, goalkeeper, attackingTeam);

                // RESTART POSITION AFTER PENALTY GOAL
                String restartTeam = defendingTeam;
                resetPositionsForRestart(rt, restartTeam, "KICKOFF");

                rt.ball = new BallPositionDTO(50, 50);
                rt.currentCarrier = null;
                Player kickoffPlayer = selectRestartPlayer(rt, restartTeam, "KICKOFF");
                rt.pendingReceiverId = kickoffPlayer != null ? Math.toIntExact(kickoffPlayer.getId()) : null;
                rt.lastTouchTeam = restartTeam;
            } else if (goalkeeper != null && penResult.isSaved()) {
                eventGenerator.createShotSavedEvent(rt, match, minute, effectiveTaker, goalkeeper, penResult.getXG());
                log.info("🧤 PENALTY SAVED! {} by {}", effectiveTaker.getName(), goalkeeper.getName());

                setCurrentCarrier(rt, goalkeeper, "carry");
                recordStoppagePause(rt, MatchRuntime.StoppageType.PENALTY, PENALTY_POST_SHOT_PAUSE_TICKS);
                releaseBall(rt, goalkeeper, defendingTeam, Math.toIntExact(goalkeeper.getId()), null, 1.2);
            } else {
                eventGenerator.createShotMissedEvent(rt, match, minute, effectiveTaker, penResult.getXG());
                log.info("❌ PENALTY MISSED! {} (xG: {:.2f})", effectiveTaker.getName(), penResult.getXG());

                rt.currentCarrier = null;
                rt.pendingReceiverId = null;
                rt.ballInTransit = false;
                rt.ballTransitCanBeIntercepted = false;
                rt.ballTransitTicks = 0;
                rt.ballTransitMaxTicks = 0;
                rt.ballTransitMode = "CONTROLLED";
                rt.ball = new BallPositionDTO(
                        attacksRight ? MAX_X : MIN_X,
                        clamp(50.0 + (random.nextDouble() - 0.5) * 8.0, MIN_Y + 2.0, MAX_Y - 2.0)
                );
                recordStoppagePause(rt, MatchRuntime.StoppageType.PENALTY, PENALTY_POST_SHOT_PAUSE_TICKS);

                resetPositionsForRestart(rt, defendingTeam, "GOAL_KICK");
                setupGoalKickRestart(rt, match, minute, defendingTeam, "HOME".equals(defendingTeam), rt.ball.getY() < 50.0);
            }

            rt.pendingPasserId = null;
            rt.pendingPassTeam = null;
            maybeCreateVarReview(goalEvent, penalty, rt, match, minute);
            maybeIssueFoulCard(rt, match, minute, attacker, defender, true);
            return;
        }

        FreeKickEvent fk = new FreeKickEvent();
        clearAssistChain(rt);
        boolean upperSide = attackerPos.getY() < 50.0;
        Player freeKickTaker = resolveSetPieceTaker(
                rt,
                attackingTeam,
                upperSide ? resolveFreeKickLeftTakerSlot(rt, attackingTeam) : resolveFreeKickRightTakerSlot(rt, attackingTeam),
                attacker
        );
        Player effectiveTaker = freeKickTaker != null ? freeKickTaker : attacker;
        fk.setMinute(minute);
        fk.setTick(rt.tick);
        fk.setMatch(match);
        fk.setTeam("HOME".equals(attackingTeam) ? match.getHomeTeam() : match.getAwayTeam());
        fk.setTaker(effectiveTaker);
        fk.setPlayer(effectiveTaker);
        fk.setDirect(estimateDistanceToGoal(rt, attacker, attackingTeam) <= 24.0);
        fk.setDangerous(isDangerousAttackingPosition(rt, attacker, attackingTeam));
        rt.runtimeEvents.add(fk);
        rt.lastTouchTeam = attackingTeam;
        executeFreeKickRestart(rt, match, minute, effectiveTaker, attackerPos, attackingTeam, fk.isDirect(), upperSide);
        maybeIssueFoulCard(rt, match, minute, attacker, defender, false);
    }

    /**
     * Rukuje loptom koja ide van terena (corner, throw-in, goal-kick)
     */
    private void handleBallOutOfBounds(MatchRuntime rt, Match match, int minute) {
        BallPositionDTO ball = rt.ball != null ? rt.ball : new BallPositionDTO(50, 50);
        clearAssistChain(rt);
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

            resetPositionsForRestart(rt, restartTeam, isCorner ? "CORNER" : "GOAL_KICK");

            if (isCorner) {
                restartPlayer = setupCornerRestart(rt, match, minute, restartTeam, upperSide);
            } else {
                restartPlayer = setupGoalKickRestart(rt, match, minute, restartTeam, homeGoalSide, upperSide);
            }
        } else {
            resetPositionsForRestart(rt, restartTeam);
            restartPlayer = selectWideRestartPlayer(rt, restartTeam, upperSide);
            eventGenerator.createThrowInEvent(rt, match, minute, restartTeam, restartPlayer);
            double throwInX = clamp(ball.getX(), 8.0, 92.0);
            double throwInY = ball.getY() <= 50.0 ? 6.5 : 93.5;
            rt.ball = new BallPositionDTO(throwInX, throwInY);
            recordStoppagePause(rt, MatchRuntime.StoppageType.THROW_IN, RESTART_PAUSE_TICKS);
            if (restartPlayer != null) {
                releaseBall(rt, restartPlayer, restartTeam, Math.toIntExact(restartPlayer.getId()), null, 2.0);
            }
        }

        rt.lastTouchTeam = restartTeam;
        rt.pendingPasserId = null;
        rt.pendingPassTeam = null;
    }

    private void preparePenaltySetup(MatchRuntime rt,
                                     Player taker,
                                     Player goalkeeper,
                                     String attackingTeam,
                                     boolean attacksRight) {
        double takerX = attacksRight ? PENALTY_SPOT_X : 100.0 - PENALTY_SPOT_X;
        double keeperX = attacksRight ? PENALTY_KEEPER_X : 100.0 - PENALTY_KEEPER_X;

        PlayerPositionDTO takerPos = getPlayerPosition(rt, taker);
        if (takerPos != null) {
            takerPos.setX(takerX);
            takerPos.setY(50.0);
        }

        PlayerPositionDTO goalkeeperPos = goalkeeper != null ? getPlayerPosition(rt, goalkeeper) : null;
        if (goalkeeperPos != null) {
            goalkeeperPos.setX(keeperX);
            goalkeeperPos.setY(50.0);
        }

        anchorNonInvolvedGoalkeepersForPenalty(rt, taker, goalkeeper);
        positionPenaltySupportPlayers(rt, taker, goalkeeper, attackingTeam, attacksRight);

        rt.lastTouchTeam = attackingTeam;
        refreshCurrentCarrier(rt, taker);
        rt.ball = new BallPositionDTO(takerX, 50.0);
    }

    private void positionPenaltySupportPlayers(MatchRuntime rt,
                                               Player taker,
                                               Player goalkeeper,
                                               String attackingTeam,
                                               boolean attacksRight) {
        List<Player> attackingPlayers = "HOME".equals(attackingTeam) ? rt.homePlayers : rt.awayPlayers;
        List<Player> defendingPlayers = "HOME".equals(attackingTeam) ? rt.awayPlayers : rt.homePlayers;

        placePenaltySupportPlayers(rt, attackingPlayers, taker, goalkeeper, attacksRight ? 79.2 : 20.8);
        placePenaltySupportPlayers(rt, defendingPlayers, taker, goalkeeper, attacksRight ? 75.2 : 24.8);
    }

    private void placePenaltySupportPlayers(MatchRuntime rt,
                                            List<Player> players,
                                            Player taker,
                                            Player goalkeeper,
                                            double targetX) {
        double[] lanes = {30.0, 38.0, 46.0, 54.0, 62.0, 70.0};
        int laneIndex = 0;

        for (Player player : players) {
            if (player == null || player.getId() == null) {
                continue;
            }
            if (player.getPosition() == Position.GK || samePlayer(player, taker) || samePlayer(player, goalkeeper)) {
                continue;
            }

            PlayerPositionDTO pos = getPlayerPosition(rt, player);
            if (pos == null) {
                continue;
            }

            double offsetX = laneIndex % 2 == 0 ? -1.1 : 1.1;
            double laneY = lanes[laneIndex % lanes.length];
            pos.setX(clamp(targetX + offsetX, MIN_X + 1.0, MAX_X - 1.0));
            pos.setY(clamp(laneY, MIN_Y + 1.0, MAX_Y - 1.0));
            laneIndex++;
        }
    }

    private void anchorNonInvolvedGoalkeepersForPenalty(MatchRuntime rt,
                                                         Player taker,
                                                         Player defendingGoalkeeper) {
        Stream.concat(rt.homePlayers.stream(), rt.awayPlayers.stream())
                .filter(player -> player != null
                        && player.getPosition() == Position.GK
                        && !samePlayer(player, taker)
                        && !samePlayer(player, defendingGoalkeeper))
                .forEach(goalkeeper -> {
                    PlayerPositionDTO goalkeeperPos = getPlayerPosition(rt, goalkeeper);
                    if (goalkeeperPos == null) {
                        return;
                    }
                    String team = getTeam(goalkeeper, rt);
                    goalkeeperPos.setX("HOME".equals(team) ? 8.5 : 91.5);
                    goalkeeperPos.setY(50.0);
                });
    }

    private void recordStoppagePause(MatchRuntime rt, MatchRuntime.StoppageType stoppageType, int pauseTicks) {
        recordProgressiveStoppagePause(rt, stoppageType, pauseTicks, null);
    }

    private void recordProgressiveStoppagePause(MatchRuntime rt,
                                                MatchRuntime.StoppageType stoppageType,
                                                int pauseTicks,
                                                Runnable tickAction) {
        if (pauseTicks <= 0) {
            return;
        }

        MatchRuntime.StoppageType previousStoppage = rt.activeStoppage;
        int previousTicks = rt.stoppageTicks;
        rt.activeStoppage = stoppageType;

        for (int i = 0; i < pauseTicks; i++) {
            if (tickAction != null) {
                tickAction.run();
            }
            rt.tick++;
            rt.stoppageTicks = pauseTicks - i;
            rt.recordTick();
        }

        rt.activeStoppage = previousStoppage;
        rt.stoppageTicks = previousTicks;
    }

    /**
     * Generates periodic events (injuries, substitutions, tactical adjustments)
     */
    private void maybeGeneratePeriodicalEvent(MatchRuntime rt, Match match, int minute) {
        maybeTriggerInjury(rt, match, minute, "HOME");
        maybeTriggerInjury(rt, match, minute, "AWAY");
        maybePerformTacticalSubstitution(rt, match, minute, "HOME");
        maybePerformTacticalSubstitution(rt, match, minute, "AWAY");
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

    private void updateFatigue(MatchRuntime rt, int minute) {
        rt.homePlayers.forEach(player -> increasePlayerFatigue(player, minute));
        rt.awayPlayers.forEach(player -> increasePlayerFatigue(player, minute));
    }

    private void increasePlayerFatigue(Player player, int minute) {
        if (player == null || player.getSkills() == null) {
            return;
        }
        int current = clampFatigue(player.getSkills().getFatigue());
        if (player.getPosition() == Position.GK) {
            if (minute % 18 == 0) {
                player.getSkills().setSkill(SkillName.FATIGUE, clampFatigue(current + 1));
            }
            return;
        }

        double staminaPenalty = Math.max(0.0, 13.0 - player.getSkills().getStamina()) / 24.0;
        double positionLoad = switch (player.getPosition()) {
            case WNG, ATT -> 0.05;
            case MID -> 0.04;
            case DEF -> 0.03;
            case GK -> 0.0;
        };
        double gainChance = 0.16 + staminaPenalty + positionLoad;
        if (minute >= 60) gainChance += 0.07;
        if (minute >= 78) gainChance += 0.08;

        int gain = random.nextDouble() < gainChance ? 1 : 0;
        if (minute >= 75 && random.nextDouble() < 0.14 + staminaPenalty) {
            gain++;
        }
        if (gain > 0) {
            player.getSkills().setSkill(SkillName.FATIGUE, clampFatigue(current + gain));
        }
    }

    private void maybePerformTacticalSubstitution(MatchRuntime rt, Match match, int minute, String side) {
        if (minute < 58 || minute > 84) {
            return;
        }

        List<Player> onPitch = "HOME".equals(side) ? rt.homeSquad : rt.awaySquad;
        List<Player> bench = getAvailableBench(rt, side);
        int substitutionsUsed = "HOME".equals(side) ? rt.homeSubstitutionsUsed : rt.awaySubstitutionsUsed;
        if (substitutionsUsed >= 3 || bench.isEmpty()) {
            return;
        }

        Player out = pickMostFatigued(onPitch);
        if (out == null) {
            return;
        }

        int fatigue = fatigueOf(out);
        double chance = 0.012;
        if (fatigue >= 18) chance += 0.04;
        if (fatigue >= 26) chance += 0.06;
        if (minute >= 72) chance += 0.018;
        if (random.nextDouble() >= chance) {
            return;
        }

        Player in = pickReplacementFor(bench, out.getPosition());
        if (in != null) {
            applySubstitution(rt, match, side, out, in, minute);
        }
    }

    private void maybeTriggerInjury(MatchRuntime rt, Match match, int minute, String side) {
        if (minute < 8 || minute > 88) {
            return;
        }

        List<Player> onPitch = "HOME".equals(side) ? rt.homeSquad : rt.awaySquad;
        if (onPitch.isEmpty()) {
            return;
        }

        Player injured = pickInjuryRiskPlayer(onPitch);
        if (injured == null) {
            return;
        }

        int fatigue = fatigueOf(injured);
        double chance = 0.00028 + Math.max(0, fatigue - 18) * 0.00008;
        if (injured.getPosition() == Position.WNG || injured.getPosition() == Position.ATT) {
            chance += 0.00008;
        }
        if (random.nextDouble() >= chance) {
            return;
        }

        applyInjury(rt, match, injured, minute);
        List<Player> bench = getAvailableBench(rt, side);
        int substitutionsUsed = "HOME".equals(side) ? rt.homeSubstitutionsUsed : rt.awaySubstitutionsUsed;
        if (substitutionsUsed < 3) {
            Player replacement = pickReplacementFor(bench, injured.getPosition());
            if (replacement != null) {
                applySubstitution(rt, match, side, injured, replacement, minute);
            }
        }
    }

    private void applyInjury(MatchRuntime rt, Match match, Player injured, int minute) {
        int days = rollInjuryDays();
        GameClock clock = seasonService.getOrCreateClock();
        int season = clock.getCurrentSeason() == null ? 1 : clock.getCurrentSeason();
        int week = clock.getCurrentWeek() == null ? 1 : clock.getCurrentWeek();

        injured.setInjuryDaysRemaining(days);
        injured.setInjurySeasonNumber(season);
        injured.setInjuryWeekNumber(week);
        injured.setInjured(true);
        if (injured.getSkills() != null) {
            injured.getSkills().setSkill(SkillName.FATIGUE, clampFatigue(fatigueOf(injured) + 6));
        }
        playerRepository.save(injured);
        eventGenerator.createInjuryEvent(rt, match, minute, injured);
    }

    private int rollInjuryDays() {
        double roll = random.nextDouble();
        if (roll < 0.72) return random.nextInt(10) + 1;
        if (roll < 0.95) return random.nextInt(6) + 11;
        return random.nextInt(4) + 17;
    }

    private Player pickMostFatigued(List<Player> squad) {
        return squad.stream()
                .filter(Objects::nonNull)
                .filter(player -> player.getPosition() != Position.GK)
                .max(Comparator.comparingInt(this::fatigueOf))
                .orElse(null);
    }

    private Player pickInjuryRiskPlayer(List<Player> squad) {
        List<Player> candidates = squad.stream()
                .filter(Objects::nonNull)
                .filter(player -> player.getPosition() != Position.GK)
                .toList();
        if (candidates.isEmpty()) {
            return null;
        }

        double totalWeight = candidates.stream()
                .mapToDouble(player -> 1.0 + Math.max(0, fatigueOf(player) - 10) * 0.25)
                .sum();
        double roll = random.nextDouble() * totalWeight;
        for (Player player : candidates) {
            roll -= 1.0 + Math.max(0, fatigueOf(player) - 10) * 0.25;
            if (roll <= 0) {
                return player;
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    private List<Player> getAvailableBench(MatchRuntime rt, String side) {
        List<Player> fullSquad = "HOME".equals(side) ? rt.home : rt.away;
        List<Player> onPitch = "HOME".equals(side) ? rt.homeSquad : rt.awaySquad;
        Set<Long> onPitchIds = onPitch.stream()
                .filter(Objects::nonNull)
                .map(Player::getId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        return fullSquad.stream()
                .filter(Objects::nonNull)
                .filter(player -> player.getId() != null)
                .filter(player -> !onPitchIds.contains(player.getId()))
                .filter(player -> !rt.playerMinutes.containsKey(player.getId()))
                .filter(player -> !player.isInjured())
                .toList();
    }

    private Player pickReplacementFor(List<Player> bench, Position targetPosition) {
        if (bench == null || bench.isEmpty()) {
            return null;
        }
        Player exact = bench.stream()
                .filter(player -> player.getPosition() == targetPosition)
                .findFirst()
                .orElse(null);
        if (exact != null) {
            return exact;
        }
        if (targetPosition != Position.GK) {
            return bench.stream()
                    .filter(player -> player.getPosition() != Position.GK)
                    .findFirst()
                    .orElse(null);
        }
        return bench.get(0);
    }

    private void applySubstitution(MatchRuntime rt, Match match, String side, Player playerOut, Player playerIn, int minute) {
        if (playerOut == null || playerIn == null || Objects.equals(playerOut.getId(), playerIn.getId())) {
            return;
        }

        List<Player> onPitch = "HOME".equals(side) ? rt.homePlayers : rt.awayPlayers;
        List<Player> runtimeSquad = "HOME".equals(side) ? rt.homeSquad : rt.awaySquad;
        if ("HOME".equals(side)) {
            if (rt.homeSubstitutionsUsed >= 3) return;
            rt.homeSubstitutionsUsed++;
        } else {
            if (rt.awaySubstitutionsUsed >= 3) return;
            rt.awaySubstitutionsUsed++;
        }

        eventGenerator.createSubstitutionEvent(rt, match, minute, playerOut, playerIn);

        replacePlayerReference(onPitch, playerOut, playerIn);
        replacePlayerReference(runtimeSquad, playerOut, playerIn);
        replacePlayerPosition(rt, playerOut, playerIn);
        reassignTacticalSlot(rt, playerOut, playerIn);

        rt.playerMinutes.put(playerOut.getId(), Math.max(1, minute));
        rt.playerMinutes.put(playerIn.getId(), Math.max(0, 91 - minute));
        rt.playerTeamSide.put(playerIn.getId(), side);
    }

    private void replacePlayerReference(List<Player> players, Player oldPlayer, Player newPlayer) {
        int index = players.indexOf(oldPlayer);
        if (index >= 0) {
            players.set(index, newPlayer);
        }
    }

    private void replacePlayerPosition(MatchRuntime rt, Player playerOut, Player playerIn) {
        PlayerPositionDTO posToReplace = rt.players.stream()
                .filter(position -> position.getId() == Math.toIntExact(playerOut.getId()))
                .findFirst()
                .orElse(null);
        if (posToReplace == null) {
            return;
        }
        int posIdx = rt.players.indexOf(posToReplace);
        rt.players.set(posIdx, new PlayerPositionDTO(
                Math.toIntExact(playerIn.getId()),
                posToReplace.getTeam(),
                posToReplace.getX(),
                posToReplace.getY(),
                0, 0
        ));
    }

    private int fatigueOf(Player player) {
        if (player == null || player.getSkills() == null) {
            return 0;
        }
        return clampFatigue(player.getSkills().getFatigue());
    }

    private int clampFatigue(int value) {
        return Math.max(0, Math.min(100, value));
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

        String possessionTeam = playerPos.getTeam();
        if (!Objects.equals(rt.currentPossessionTeam, possessionTeam)) {
            rt.currentPossessionTeam = possessionTeam;
            rt.possessionTicks = 0;
            rt.possessionStartTick = rt.tick;
            rt.possessionStartX = playerPos.getX();
            rt.possessionStartY = playerPos.getY();
        } else {
            rt.possessionTicks = Math.max(rt.possessionTicks, rt.tick - rt.possessionStartTick);
        }
        
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
        rt.ballTransitMode = "CONTROLLED";
        rt.lastControllerId = Math.toIntExact(player.getId());
        rt.lastControlTick = rt.tick;
        rt.lastControlSource = controlSource;
        rt.lastControlX = playerPos.getX();
        rt.lastControlTeam = playerPos.getTeam();
        updateAssistChainOnControl(rt, player, controlSource);
        updatePossessionPhase(rt);

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
        String possessionTeam = playerPos.getTeam();
        if (!Objects.equals(rt.currentPossessionTeam, possessionTeam)) {
            rt.currentPossessionTeam = possessionTeam;
            rt.possessionTicks = 0;
            rt.possessionStartTick = rt.tick;
            rt.possessionStartX = playerPos.getX();
            rt.possessionStartY = playerPos.getY();
        } else {
            rt.possessionTicks = Math.max(0, rt.tick - rt.possessionStartTick);
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
        rt.ballTransitMode = "CONTROLLED";
        updatePossessionPhase(rt);
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
        updatePossessionPhase(rt);
    }

    private void resolveLooseBall(MatchRuntime rt) {
        if (rt.ballInTransit) {
            return;
        }
        rt.possessionPhase = MatchRuntime.PossessionPhase.TRANSITION;
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
        if (outfield.isEmpty() || rt.ball == null) {
            return null;
        }

        Player intended = rt.pendingReceiverId != null ? findPlayerById(rt, rt.pendingReceiverId) : null;
        Player zoneFirstTarget = findZonePriorityLooseBallTarget(rt, outfield, intended);
        if (zoneFirstTarget != null) {
            return zoneFirstTarget;
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
        double rawX = startX + (rt.ballTransitTargetX - startX) * progress;
        double rawY = startY + (rt.ballTransitTargetY - startY) * progress;
        rt.ball.setX(rawX);
        rt.ball.setY(rawY);

        if (isOutsidePlayableBounds(rawX, rawY)) {
            handleBallOutOfBounds(rt, match, minute);
            return;
        }

        if (isLockedCrossTransit(rt) && rt.ballTransitTicks >= rt.ballTransitMaxTicks) {
            rt.ball.setX(rt.ballTransitTargetX);
            rt.ball.setY(rt.ballTransitTargetY);
            resolveCrossArrival(rt, match, minute);
            return;
        }

        PlayerPositionDTO ballPosDTO = new PlayerPositionDTO(-1, "", rt.ball.getX(), rt.ball.getY(), 0, 0);
        if (!isLockedCrossTransit(rt) && rt.ballTransitCanBeIntercepted) {
            Player interceptor = findTransitInterceptor(rt, ballPosDTO);
            if (interceptor != null) {
                PlayerPositionDTO intPos = getPlayerPosition(rt, interceptor);
                if (intPos != null) {
                    // Ball must be CLOSE to interceptor's position to be intercepted
                    double dist = distanceBetween(intPos, ballPosDTO);
                    if (dist <= 0.8) { // Tighter pickup
                        if (shouldDeflectTransit(rt, interceptor)) {
                            startRandomDeflection(rt, interceptor, intPos.getX(), intPos.getY(), 4.0, 13.5);
                            return;
                        }
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
        if (!isLockedCrossTransit(rt) && intended != null) {
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
        movePosition(pos, targetX, 50.0, resolveActionMovementStep(player, step));
    }

    private void movePlayerTowardsBall(MatchRuntime rt, Player player, double step) {
        PlayerPositionDTO pos = getPlayerPosition(rt, player);
        if (pos == null || rt.ball == null) {
            return;
        }
        movePosition(pos, rt.ball.getX(), rt.ball.getY(), resolveActionMovementStep(player, step));
    }

    private void holdPendingReceiverPosition(MatchRuntime rt, PlayerPositionDTO pos, Player player) {
        boolean inPossession = Objects.equals(pos.getTeam(), rt.lastTouchTeam);
        double[] tacticalTarget = resolveTacticalTarget(rt, pos, player, inPossession);
        if (tacticalTarget == null) {
            return;
        }

        PlayerPositionDTO targetPos = new PlayerPositionDTO(-1, pos.getTeam(), tacticalTarget[0], tacticalTarget[1], 0, 0);
        if (distanceBetween(pos, targetPos) > 6.0) {
            movePosition(pos, tacticalTarget[0], tacticalTarget[1], resolveActionMovementStep(player, 2.4));
        }
    }

    private boolean isCloseContactDefender(MatchRuntime rt, PlayerPositionDTO attackerPos, Player defender) {
        PlayerPositionDTO defenderPos = getPlayerPosition(rt, defender);
        return distanceBetween(attackerPos, defenderPos) <= OVERLAP_DUEL_DISTANCE + 1.1;
    }

    private Player findImmediateBallPressureDefender(MatchRuntime rt, Player attacker) {
        PlayerPositionDTO attackerPos = getPlayerPosition(rt, attacker);
        if (attackerPos == null) {
            return null;
        }

        String attackingTeam = getTeam(attacker, rt);
        List<Player> defenders = "HOME".equals(attackingTeam) ? rt.awayPlayers : rt.homePlayers;
        return defenders.stream()
                .filter(player -> player.getPosition() != Position.GK)
                .filter(player -> distanceBetween(attackerPos, getPlayerPosition(rt, player)) <= OVERLAP_DUEL_DISTANCE)
                .sorted(Comparator
                        .comparingInt((Player player) -> defensivePressurePriority(player.getPosition()))
                        .thenComparingDouble(player -> distanceBetween(attackerPos, getPlayerPosition(rt, player))))
                .findFirst()
                .orElse(null);
    }

    private void updateSupportingMovement(MatchRuntime rt) {
        if (isLockedCrossTransit(rt)) {
            return;
        }
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
            if (pos.getOffsideTicksRemaining() > 0 && pos.getRetreatTicksRemaining() <= 0) {
                return;
            }
            if (rt.ballInTransit && Objects.equals(rt.pendingReceiverId, pos.getId())) {
                holdPendingReceiverPosition(rt, pos, player);
                return;
            }
            applyRoleMovement(rt, pos, player);
        });
        spreadSameTeamPlayers(rt);
        enforceGoalkeeperArea(rt);
    }

    private boolean isLockedCrossTransit(MatchRuntime rt) {
        return rt != null && rt.ballInTransit && "CROSS".equals(rt.ballTransitMode);
    }

    private void resolveCrossArrival(MatchRuntime rt, Match match, int minute) {
        double targetX = rt.ballTransitTargetX;
        double targetY = rt.ballTransitTargetY;
        String attackingTeam = rt.pendingPassTeam != null ? rt.pendingPassTeam : rt.lastTouchTeam;
        if (attackingTeam == null) {
            settleLooseTransitAtTarget(rt, targetX, targetY);
            return;
        }

        String defendingTeam = oppositeTeam(attackingTeam);
        Player attacker = selectCrossContestant(rt, attackingTeam, targetX, targetY, true);
        Player defender = selectCrossContestant(rt, defendingTeam, targetX, targetY, false);

        if (attacker != null && defender != null) {
            DuelResolver.DuelResult result = duelResolver.resolveTackleDuel(attacker, defender);
            eventGenerator.createDuelEvent(rt, match, minute, attacker, defender, result);
            if (result.isWon()) {
                clearPendingPassContext(rt);
                placePlayerAt(rt, attacker, targetX, targetY);
                rt.lastTouchTeam = attackingTeam;
                setCurrentCarrier(rt, attacker, "duel");
                applyDuelFreeze(rt, defender, 4);
                handleShot(rt, match, minute, attacker,
                        new AIDecisionMaker.Decision(AIDecisionMaker.ActionType.SHOT, null), attackingTeam);
            } else {
                applyDuelFreeze(rt, attacker, 4);
                startClearanceTransit(rt, defender, defendingTeam, targetX, targetY);
            }
            return;
        }

        if (attacker != null) {
            clearPendingPassContext(rt);
            placePlayerAt(rt, attacker, targetX, targetY);
            rt.lastTouchTeam = attackingTeam;
            setCurrentCarrier(rt, attacker, "duel");
            handleShot(rt, match, minute, attacker,
                    new AIDecisionMaker.Decision(AIDecisionMaker.ActionType.SHOT, null), attackingTeam);
            return;
        }

        if (defender != null) {
            startClearanceTransit(rt, defender, defendingTeam, targetX, targetY);
            return;
        }

        settleLooseTransitAtTarget(rt, targetX, targetY);
    }

    private Player selectCrossContestant(MatchRuntime rt,
                                         String team,
                                         double targetX,
                                         double targetY,
                                         boolean attacking) {
        PlayerPositionDTO target = new PlayerPositionDTO(-1, team, targetX, targetY, 0, 0);
        return ("HOME".equals(team) ? rt.homePlayers : rt.awayPlayers).stream()
                .filter(Objects::nonNull)
                .filter(player -> player.getId() != null)
                .filter(player -> player.getPosition() != Position.GK)
                .filter(player -> {
                    PlayerPositionDTO pos = getPlayerPosition(rt, player);
                    return pos != null && distanceBetween(pos, target) <= 18.0;
                })
                .min(Comparator
                        .comparingDouble((Player player) -> distanceBetween(getPlayerPosition(rt, player), target))
                        .thenComparingInt(player -> attacking
                                ? cornerAttackPriority(player.getPosition())
                                : cornerDefensePriority(player.getPosition())))
                .orElse(null);
    }

    private void placePlayerAt(MatchRuntime rt, Player player, double x, double y) {
        PlayerPositionDTO pos = getPlayerPosition(rt, player);
        if (pos == null) {
            return;
        }
        pos.setX(x);
        pos.setY(y);
        rt.ball = new BallPositionDTO(x, y);
    }

    private void startClearanceTransit(MatchRuntime rt,
                                       Player defender,
                                       String defendingTeam,
                                       double startX,
                                       double startY) {
        if (defender == null || defender.getId() == null) {
            settleLooseTransitAtTarget(rt, startX, startY);
            return;
        }

        placePlayerAt(rt, defender, startX, startY);
        clearPendingPassContext(rt);
        rt.lastTouchTeam = defendingTeam;

        double travel = 18.0 + random.nextDouble() * 12.0;
        double targetX = "HOME".equals(defendingTeam)
                ? clamp(startX + travel, MIN_X + 2.0, MAX_X - 2.0)
                : clamp(startX - travel, MIN_X + 2.0, MAX_X - 2.0);
        double targetY = clamp(startY + (random.nextDouble() - 0.5) * 28.0, MIN_Y + 2.0, MAX_Y - 2.0);

        beginBallTransit(
                rt,
                startX,
                startY,
                targetX,
                targetY,
                null,
                Math.toIntExact(defender.getId()),
                null,
                defendingTeam,
                "CLEARANCE",
                false
        );
        clearRecentControlHints(rt);
    }

    private void settleLooseTransitAtTarget(MatchRuntime rt, double targetX, double targetY) {
        clearPendingPassContext(rt);
        rt.currentCarrier = null;
        rt.ball = new BallPositionDTO(targetX, targetY);
        rt.ballInTransit = false;
        rt.ballTransitCanBeIntercepted = false;
        rt.ballTransitTicks = 0;
        rt.ballTransitMaxTicks = 0;
        rt.ballTransitMode = "CONTROLLED";
    }

    private void clearPendingPassContext(MatchRuntime rt) {
        rt.pendingReceiverId = null;
        rt.pendingPasserId = null;
        rt.pendingPassTeam = null;
    }

    private void rememberAssistChain(MatchRuntime rt, Player passer, Player receiver, String team) {
        if (passer == null || receiver == null || passer.getId() == null || receiver.getId() == null) {
            return;
        }
        rt.recentAssistPasserId = Math.toIntExact(passer.getId());
        rt.recentAssistReceiverId = Math.toIntExact(receiver.getId());
        rt.recentAssistTeam = team;
        rt.recentAssistTick = rt.tick;
    }

    private void clearAssistChain(MatchRuntime rt) {
        rt.recentAssistPasserId = null;
        rt.recentAssistReceiverId = null;
        rt.recentAssistTeam = null;
        rt.recentAssistTick = -100;
    }

    private void updateAssistChainOnControl(MatchRuntime rt, Player player, String controlSource) {
        if (player == null || player.getId() == null || rt.recentAssistTeam == null) {
            return;
        }

        String controlledTeam = getTeam(player, rt);
        if (!Objects.equals(controlledTeam, rt.recentAssistTeam)) {
            clearAssistChain(rt);
            return;
        }

        if ("restart".equals(controlSource)) {
            clearAssistChain(rt);
            return;
        }

        if (rt.recentAssistReceiverId != null && Objects.equals(rt.recentAssistReceiverId, Math.toIntExact(player.getId()))) {
            rt.recentAssistTick = rt.tick;
        }
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
        boolean inPossession = Objects.equals(resolvePossessionTeam(rt), team);
        boolean upperLane = pos.getY() < 50.0;
        double targetX;
        double targetY;

        double[] tacticalTarget = resolveTacticalTarget(rt, pos, player, inPossession);
        if (tacticalTarget != null) {
            double[] compactTarget = applyTeamCompactnessToTarget(rt, pos, player, tacticalTarget, inPossession);
            compactTarget = applySlotRoleBias(rt, pos, player, compactTarget, inPossession);
            double[] overrideTarget = resolveMovementOverrideTarget(rt, pos, player, compactTarget, inPossession);
            double[] movementTarget = overrideTarget != null ? overrideTarget : compactTarget;
            if (player.getPosition() == Position.GK) {
                movePosition(
                        pos,
                        movementTarget[0],
                        movementTarget[1],
                        SUPPORT_STEP * 0.24
                );
                return;
            }
            targetX = movementTarget[0];
            targetY = movementTarget[1];
            if (player.getPosition() == Position.DEF) {
                targetX = applyDefensiveLineDiscipline(rt, pos, player, targetX, inPossession, home);
            }
            if (player.getPosition() == Position.ATT || player.getPosition() == Position.WNG) {
                targetX = applyOffsideTolerance(rt, pos, targetX, home);
            }
            movePosition(pos, targetX, targetY, resolveMovementStep(pos, player, overrideTarget != null));
            if (player.getPosition() == Position.ATT || player.getPosition() == Position.WNG) {
                keepAttackerOnside(rt, pos);
            }
            return;
        }

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
                targetX = applyDefensiveLineDiscipline(rt, pos, player, targetX, inPossession, home);
            }
            case WNG -> {
                double wingY = upperLane ? 8.0 : 92.0;
                double baseX = home ? (inPossession ? 75.0 : 55.0) : (inPossession ? 31.0 : 47.0);
                double advance = inPossession ? (home ? 20.0 : -12.0) : (home ? -5.0 : 4.0);
                
                targetX = baseX + (rt.ball.getX() - baseX) * (inPossession ? 0.40 : 0.25) + advance;
                targetY = wingY + (rt.ball.getY() - wingY) * (inPossession ? 0.15 : 0.18);
                targetX = applyOffsideTolerance(rt, pos, targetX, home);
            }
            case ATT -> {
                double laneY = upperLane ? 40.0 : 60.0;
                double baseX = home ? (inPossession ? 85.0 : 65.0) : (inPossession ? 23.0 : 39.0);
                double offensivePush = inPossession ? (home ? 14.0 : -9.0) : 0;
                
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

        movePosition(pos, targetX, targetY, resolveMovementStep(pos, player, false));
        if (player.getPosition() == Position.ATT || player.getPosition() == Position.WNG) {
            keepAttackerOnside(rt, pos);
        }
    }

    private void enforceGoalkeeperArea(MatchRuntime rt) {
        Stream.concat(rt.homePlayers.stream(), rt.awayPlayers.stream())
                .filter(Objects::nonNull)
                .filter(player -> player.getPosition() == Position.GK)
                .forEach(goalkeeper -> clampGoalkeeperToArea(rt, goalkeeper));
    }

    private void clampGoalkeeperToArea(MatchRuntime rt, Player goalkeeper) {
        PlayerPositionDTO pos = getPlayerPosition(rt, goalkeeper);
        if (pos == null) {
            return;
        }

        boolean home = "HOME".equals(pos.getTeam());
        double minX = home ? GOALKEEPER_HARD_AREA_MIN_X_HOME : GOALKEEPER_HARD_AREA_MIN_X_AWAY;
        double maxX = home ? GOALKEEPER_HARD_AREA_MAX_X_HOME : GOALKEEPER_HARD_AREA_MAX_X_AWAY;
        pos.setX(clamp(pos.getX(), minX, maxX));
        pos.setY(clamp(pos.getY(), GOALKEEPER_HARD_AREA_MIN_Y, GOALKEEPER_HARD_AREA_MAX_Y));

        if (rt.currentCarrier != null && goalkeeper.getId() != null && rt.currentCarrier.getId() == goalkeeper.getId()) {
            rt.currentCarrier.setX(pos.getX());
            rt.currentCarrier.setY(pos.getY());
            if (rt.ball != null && !rt.ballInTransit) {
                rt.ball.setX(pos.getX());
                rt.ball.setY(pos.getY());
            }
        }
    }

    private double resolveMovementStep(PlayerPositionDTO pos, Player player, boolean overrideApplied) {
        double baseStep = overrideApplied ? SUPPORT_STEP + 0.4 : SUPPORT_STEP;
        baseStep *= resolveMovementSkillFactor(player);
        if ((player.getPosition() == Position.ATT || player.getPosition() == Position.WNG)
                && pos.getRetreatTicksRemaining() > 0) {
            return Math.max(baseStep, (SUPPORT_STEP + 2.1 + (pos.getRetreatTicksRemaining() * 0.95)) * resolveMovementSkillFactor(player));
        }
        return baseStep;
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
                    keepAttackerOnside(rt, a);
                }
                if (second.getPosition() == Position.ATT || second.getPosition() == Position.WNG) {
                    b.setX(clamp(b.getX() + ("HOME".equals(b.getTeam()) ? directionalPush : -directionalPush), MIN_X, MAX_X));
                    keepAttackerOnside(rt, b);
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
        rt.ballTransitStartX = rt.ball.getX();
        rt.ballTransitStartY = rt.ball.getY();

        String transitMode = classifyPassTransitMode(passerPos, receiverPos, team);
        double scatterScale = switch (transitMode) {
            case "CROSS" -> 3.2;
            case "LOFTED_PASS" -> 1.9;
            default -> 1.0;
        };
        double passScatter = scatter * resolvePassScatterMultiplier(passer, transitMode);
        double rawTargetX = receiverPos.getX() + (random.nextDouble() - 0.5) * passScatter * scatterScale;
        double rawTargetY = receiverPos.getY() + (random.nextDouble() - 0.5) * passScatter * ("CROSS".equals(transitMode) ? 5.0 : scatterScale * 1.4);
        double targetX = boundTransitTarget(rawTargetX, MIN_X, MAX_X, transitMode);
        double targetY = boundTransitTarget(rawTargetY, MIN_Y, MAX_Y, transitMode);

        beginBallTransit(
                rt,
                rt.ball.getX(),
                rt.ball.getY(),
                targetX,
                targetY,
                Math.toIntExact(receiver.getId()),
                Math.toIntExact(passer.getId()),
                team,
                team,
                transitMode,
                true
        );
        tuneBallTransitForPasser(rt, passer, passerPos, receiverPos, transitMode);
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

        beginBallTransit(
                rt,
                startX,
                startY,
                intendedX,
                intendedY,
                pendingReceiverId,
                pendingPasserId,
                null,
                recoveringTeam,
                "RESTART",
                false
        );
        clearRecentControlHints(rt);
    }

    private Player setupCornerRestart(MatchRuntime rt,
                                      Match match,
                                      int minute,
                                      String restartTeam,
                                      boolean upperSide) {
        clearAssistChain(rt);
        resetPositionsForRestart(rt, restartTeam, "CORNER");
        Player cornerTaker = resolveSetPieceTaker(
                rt,
                restartTeam,
                upperSide ? resolveCornerLeftTakerSlot(rt, restartTeam) : resolveCornerRightTakerSlot(rt, restartTeam),
                selectWideRestartPlayer(rt, restartTeam, upperSide)
        );
        String cornerPattern = chooseCornerPattern(rt, restartTeam, cornerTaker, upperSide);
        double cornerX = "HOME".equals(restartTeam) ? 98.5 : 1.5;
        double cornerY = upperSide ? 6.0 : 94.0;
        rt.ball = new BallPositionDTO(cornerX, cornerY);
        prepareCornerActors(rt, restartTeam, upperSide, cornerTaker, cornerX, cornerY, cornerPattern);
        positionCornerTakerForApproach(rt, cornerTaker, restartTeam, upperSide, cornerX, cornerY);
        eventGenerator.createCornerEvent(rt, match, minute, restartTeam, cornerTaker);
        if (cornerTaker != null) {
            setCurrentCarrier(rt, cornerTaker, "restart");
            PlayerPositionDTO takerPos = getPlayerPosition(rt, cornerTaker);
            recordProgressiveStoppagePause(
                    rt,
                    MatchRuntime.StoppageType.CORNER,
                    CORNER_TAKER_APPROACH_TICKS,
                    () -> updateCornerTakerApproach(rt, takerPos, cornerX, cornerY)
            );
            settleCornerTakerAtSpot(rt, takerPos, cornerX, cornerY);
        }
        recordStoppagePause(rt, MatchRuntime.StoppageType.CORNER, CORNER_PRE_CROSS_PAUSE_TICKS);
        deliverCorner(rt, cornerTaker, restartTeam, upperSide, cornerPattern);
        return cornerTaker;
    }

    private void positionCornerTakerForApproach(MatchRuntime rt,
                                                Player cornerTaker,
                                                String restartTeam,
                                                boolean upperSide,
                                                double cornerX,
                                                double cornerY) {
        PlayerPositionDTO takerPos = cornerTaker != null ? getPlayerPosition(rt, cornerTaker) : null;
        if (takerPos == null) {
            return;
        }

        double approachX = clamp(cornerX + ("HOME".equals(restartTeam) ? -4.8 : 4.8), MIN_X + 1.0, MAX_X - 1.0);
        double approachY = clamp(cornerY + (upperSide ? 7.0 : -7.0), MIN_Y + 1.0, MAX_Y - 1.0);
        takerPos.setX(approachX);
        takerPos.setY(approachY);
        rt.ball = new BallPositionDTO(approachX, approachY);
    }

    private void updateCornerTakerApproach(MatchRuntime rt,
                                           PlayerPositionDTO takerPos,
                                           double cornerX,
                                           double cornerY) {
        if (takerPos == null || rt.ball == null) {
            return;
        }

        movePosition(takerPos, cornerX, cornerY, CORNER_TAKER_APPROACH_STEP);
        rt.ball.setX(takerPos.getX());
        rt.ball.setY(takerPos.getY());
        if (rt.currentCarrier != null) {
            rt.currentCarrier.setX(takerPos.getX());
            rt.currentCarrier.setY(takerPos.getY());
        }
    }

    private void settleCornerTakerAtSpot(MatchRuntime rt,
                                         PlayerPositionDTO takerPos,
                                         double cornerX,
                                         double cornerY) {
        if (takerPos == null) {
            return;
        }

        takerPos.setX(cornerX);
        takerPos.setY(cornerY);
        rt.ball = new BallPositionDTO(cornerX, cornerY);
        if (rt.currentCarrier != null) {
            rt.currentCarrier.setX(cornerX);
            rt.currentCarrier.setY(cornerY);
        }
    }

    private void prepareCornerActors(MatchRuntime rt,
                                     String restartTeam,
                                     boolean upperSide,
                                     Player cornerTaker,
                                     double cornerX,
                                     double cornerY,
                                     String cornerPattern) {
        PlayerPositionDTO takerPos = cornerTaker != null ? getPlayerPosition(rt, cornerTaker) : null;
        if (takerPos != null) {
            takerPos.setX(cornerX);
            takerPos.setY(cornerY);
        }

        boolean attacksRight = "HOME".equals(restartTeam);
        double spotX = attacksRight ? PENALTY_SPOT_X : 100.0 - PENALTY_SPOT_X;
        double[] attackX = resolveCornerAttackX(attacksRight, spotX, cornerPattern, restartTeam, rt);
        double[] defendX = resolveCornerDefenseX(attacksRight, spotX, cornerPattern);
        double[] attackY = resolveCornerAttackY(upperSide, cornerPattern);
        double[] defendY = resolveCornerDefenseY(upperSide, cornerPattern);

        List<Player> attackers = ("HOME".equals(restartTeam) ? rt.homePlayers : rt.awayPlayers).stream()
                .filter(Objects::nonNull)
                .filter(player -> player.getId() != null)
                .filter(player -> player.getPosition() != Position.GK)
                .filter(player -> !samePlayer(player, cornerTaker))
                .sorted(Comparator.comparingInt(player -> cornerAttackPriority(player.getPosition())))
                .toList();
        for (int i = 0; i < Math.min(attackers.size(), attackX.length); i++) {
            PlayerPositionDTO pos = getPlayerPosition(rt, attackers.get(i));
            if (pos != null) {
                pos.setX(attackX[i]);
                pos.setY(attackY[i]);
            }
        }

        List<Player> defenders = ("HOME".equals(restartTeam) ? rt.awayPlayers : rt.homePlayers).stream()
                .filter(Objects::nonNull)
                .filter(player -> player.getId() != null)
                .filter(player -> player.getPosition() != Position.GK)
                .sorted(Comparator.comparingInt(player -> cornerDefensePriority(player.getPosition())))
                .toList();
        for (int i = 0; i < Math.min(defenders.size(), defendX.length); i++) {
            PlayerPositionDTO pos = getPlayerPosition(rt, defenders.get(i));
            if (pos != null) {
                pos.setX(defendX[i]);
                pos.setY(defendY[i]);
            }
        }

        if ("SHORT".equals(cornerPattern)) {
            Player shortReceiver = selectShortCornerReceiver(rt, restartTeam, cornerTaker, upperSide);
            PlayerPositionDTO shortPos = shortReceiver != null ? getPlayerPosition(rt, shortReceiver) : null;
            if (shortPos != null) {
                shortPos.setX(clamp(cornerX + (attacksRight ? -5.5 : 5.5), MIN_X + 1.0, MAX_X - 1.0));
                shortPos.setY(clamp(cornerY + (upperSide ? 8.5 : -8.5), MIN_Y + 1.0, MAX_Y - 1.0));
            }
        }
    }

    private void deliverCorner(MatchRuntime rt, Player cornerTaker, String restartTeam, boolean upperSide, String cornerPattern) {
        PlayerPositionDTO takerPos = cornerTaker != null ? getPlayerPosition(rt, cornerTaker) : null;
        if (takerPos == null || cornerTaker.getId() == null) {
            return;
        }

        double startX = rt.ball != null ? rt.ball.getX() : takerPos.getX();
        double startY = rt.ball != null ? rt.ball.getY() : takerPos.getY();
        if ("SHORT".equals(cornerPattern)) {
            Player shortOption = selectShortCornerReceiver(rt, restartTeam, cornerTaker, upperSide);
            if (shortOption != null) {
                startPassTransit(rt, cornerTaker, shortOption, restartTeam, Math.max(0.7, resolvePassScatter(cornerTaker, shortOption) * 0.68));
                clearRecentControlHints(rt);
                rt.lastTouchTeam = restartTeam;
                return;
            }
        }

        double[] deliveryTarget = resolveCornerDeliveryTarget(rt, restartTeam, upperSide, cornerTaker, cornerPattern);
        double targetX = deliveryTarget[0];
        double targetY = deliveryTarget[1];

        beginBallTransit(
                rt,
                startX,
                startY,
                targetX,
                targetY,
                null,
                Math.toIntExact(cornerTaker.getId()),
                restartTeam,
                restartTeam,
                "CROSS",
                true
        );
        clearRecentControlHints(rt);
    }

    private String chooseCornerPattern(MatchRuntime rt, String restartTeam, Player cornerTaker, boolean upperSide) {
        double roll = random.nextDouble();
        double deliveryQuality = resolveSetPieceDeliveryQuality(cornerTaker);
        String style = normalizeStyleForSetPieces("HOME".equals(restartTeam) ? rt.homeStyle : rt.awayStyle);

        double shortChance = switch (style) {
            case "POSSESSION" -> 0.20;
            case "ATTACKING" -> 0.10;
            case "DEFENSIVE" -> 0.08;
            default -> 0.12;
        };
        shortChance += deliveryQuality < 0.55 ? 0.06 : 0.0;
        double nearChance = "ATTACKING".equals(style) ? 0.34 : 0.28;
        double farChance = "POSSESSION".equals(style) ? 0.22 : 0.30;

        if (roll < shortChance) {
            return "SHORT";
        }
        if (roll < shortChance + nearChance) {
            return "NEAR_POST";
        }
        if (roll < shortChance + nearChance + farChance) {
            return "FAR_POST";
        }
        return "EDGE";
    }

    private double[] resolveCornerAttackX(boolean attacksRight,
                                          double spotX,
                                          String cornerPattern,
                                          String restartTeam,
                                          MatchRuntime rt) {
        String style = normalizeStyleForSetPieces("HOME".equals(restartTeam) ? rt.homeStyle : rt.awayStyle);
        double stylePush = switch (style) {
            case "ATTACKING" -> 1.4;
            case "POSSESSION" -> -0.2;
            case "DEFENSIVE" -> -0.9;
            default -> 0.4;
        };
        double[] canonical = switch (cornerPattern) {
            case "SHORT" -> new double[]{spotX - 7.0, spotX - 4.8, spotX - 2.8, spotX - 9.0};
            case "NEAR_POST" -> new double[]{spotX - 2.6, spotX - 3.4, spotX - 5.0, spotX - 1.6};
            case "FAR_POST" -> new double[]{spotX - 1.6, spotX - 3.0, spotX - 5.2, spotX - 0.4};
            case "EDGE" -> new double[]{spotX - 7.8, spotX - 5.2, spotX - 2.6, spotX - 9.2};
            default -> new double[]{spotX - 4.0, spotX - 2.0, spotX - 5.5, spotX - 1.0};
        };
        return mirrorCornerXArray(attacksRight, canonical, stylePush);
    }

    private double[] resolveCornerDefenseX(boolean attacksRight, double spotX, String cornerPattern) {
        double[] canonical = switch (cornerPattern) {
            case "SHORT" -> new double[]{spotX - 4.2, spotX - 1.2, spotX - 6.0, spotX + 1.8};
            case "NEAR_POST" -> new double[]{spotX - 0.5, spotX + 1.2, spotX - 2.0, spotX + 2.8};
            case "FAR_POST" -> new double[]{spotX - 0.6, spotX + 1.8, spotX - 2.8, spotX + 3.6};
            case "EDGE" -> new double[]{spotX - 2.4, spotX + 1.0, spotX - 4.0, spotX + 2.4};
            default -> new double[]{spotX - 0.8, spotX + 1.6, spotX - 2.5, spotX + 3.2};
        };
        return mirrorCornerXArray(attacksRight, canonical, 0.0);
    }

    private double[] mirrorCornerXArray(boolean attacksRight, double[] canonical, double stylePush) {
        double[] values = new double[canonical.length];
        for (int i = 0; i < canonical.length; i++) {
            double adjusted = canonical[i] + stylePush;
            values[i] = attacksRight ? adjusted : 100.0 - adjusted;
        }
        return values;
    }

    private double[] resolveCornerAttackY(boolean upperSide, String cornerPattern) {
        return switch (cornerPattern) {
            case "SHORT" -> upperSide
                    ? new double[]{28.0, 41.0, 54.0, 63.0}
                    : new double[]{72.0, 59.0, 46.0, 37.0};
            case "NEAR_POST" -> upperSide
                    ? new double[]{41.0, 45.0, 52.0, 58.0}
                    : new double[]{59.0, 55.0, 48.0, 42.0};
            case "FAR_POST" -> upperSide
                    ? new double[]{48.0, 55.0, 60.0, 65.0}
                    : new double[]{52.0, 45.0, 40.0, 35.0};
            case "EDGE" -> upperSide
                    ? new double[]{36.0, 44.0, 52.0, 61.0}
                    : new double[]{64.0, 56.0, 48.0, 39.0};
            default -> upperSide
                    ? new double[]{40.0, 47.0, 54.0, 61.0}
                    : new double[]{60.0, 53.0, 46.0, 39.0};
        };
    }

    private double[] resolveCornerDefenseY(boolean upperSide, String cornerPattern) {
        return switch (cornerPattern) {
            case "SHORT" -> upperSide
                    ? new double[]{33.0, 43.0, 54.0, 64.0}
                    : new double[]{67.0, 57.0, 46.0, 36.0};
            case "NEAR_POST" -> upperSide
                    ? new double[]{40.0, 46.0, 53.0, 60.0}
                    : new double[]{60.0, 54.0, 47.0, 40.0};
            case "FAR_POST" -> upperSide
                    ? new double[]{47.0, 54.0, 60.0, 66.0}
                    : new double[]{53.0, 46.0, 40.0, 34.0};
            case "EDGE" -> upperSide
                    ? new double[]{39.0, 47.0, 55.0, 63.0}
                    : new double[]{61.0, 53.0, 45.0, 37.0};
            default -> upperSide
                    ? new double[]{42.0, 49.0, 56.0, 63.0}
                    : new double[]{58.0, 51.0, 44.0, 37.0};
        };
    }

    private Player selectShortCornerReceiver(MatchRuntime rt, String restartTeam, Player cornerTaker, boolean upperSide) {
        List<Player> teammates = "HOME".equals(restartTeam) ? rt.homePlayers : rt.awayPlayers;
        return teammates.stream()
                .filter(player -> player != null && player.getId() != null)
                .filter(player -> !samePlayer(player, cornerTaker))
                .filter(player -> player.getPosition() != Position.GK)
                .filter(player -> {
                    PlayerPositionDTO pos = getPlayerPosition(rt, player);
                    if (pos == null) {
                        return false;
                    }
                    boolean sameFlank = upperSide ? pos.getY() <= 40.0 : pos.getY() >= 60.0;
                    boolean nearCornerHalfSpace = "HOME".equals(restartTeam)
                            ? pos.getX() >= 78.0
                            : pos.getX() <= 22.0;
                    return sameFlank && nearCornerHalfSpace;
                })
                .min(Comparator.comparingDouble(player -> {
                    PlayerPositionDTO pos = getPlayerPosition(rt, player);
                    return pos == null ? 100.0 : distanceBetween(getPlayerPosition(rt, cornerTaker), pos);
                }))
                .orElse(null);
    }

    private double[] resolveCornerDeliveryTarget(MatchRuntime rt,
                                                 String restartTeam,
                                                 boolean upperSide,
                                                 Player cornerTaker,
                                                 String cornerPattern) {
        boolean attacksRight = "HOME".equals(restartTeam);
        double deliveryQuality = resolveSetPieceDeliveryQuality(cornerTaker);
        double canonicalX;
        double canonicalY;

        switch (cornerPattern) {
            case "NEAR_POST" -> {
                canonicalX = 90.5;
                canonicalY = upperSide ? 41.5 : 58.5;
            }
            case "FAR_POST" -> {
                canonicalX = 92.0;
                canonicalY = upperSide ? 61.0 : 39.0;
            }
            case "EDGE" -> {
                canonicalX = 79.5;
                canonicalY = upperSide ? 44.0 : 56.0;
            }
            default -> {
                canonicalX = 88.5;
                canonicalY = upperSide ? 47.0 : 53.0;
            }
        }

        double scatterX = switch (cornerPattern) {
            case "NEAR_POST" -> 2.6;
            case "FAR_POST" -> 3.1;
            case "EDGE" -> 2.2;
            default -> 3.2;
        };
        double scatterY = switch (cornerPattern) {
            case "NEAR_POST" -> 4.2;
            case "FAR_POST" -> 5.4;
            case "EDGE" -> 3.0;
            default -> 5.0;
        };
        scatterX = Math.max(1.0, scatterX - deliveryQuality * 1.0);
        scatterY = Math.max(1.8, scatterY - deliveryQuality * 1.4);

        double targetX = attacksRight ? canonicalX : 100.0 - canonicalX;
        double targetY = canonicalY;
        targetX = clamp(targetX + (random.nextDouble() - 0.5) * scatterX, MIN_X + 2.0, MAX_X - 2.0);
        targetY = clamp(targetY + (random.nextDouble() - 0.5) * scatterY, PENALTY_AREA_MIN_Y + 2.0, PENALTY_AREA_MAX_Y - 2.0);
        return new double[]{targetX, targetY};
    }

    private String normalizeStyleForSetPieces(String style) {
        String normalized = style == null ? "BALANCED" : style.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "ATTACKING", "POSSESSION", "DEFENSIVE", "BALANCED" -> normalized;
            default -> "BALANCED";
        };
    }

    private void executeFreeKickRestart(MatchRuntime rt,
                                        Match match,
                                        int minute,
                                        Player taker,
                                        PlayerPositionDTO foulSpot,
                                        String attackingTeam,
                                        boolean direct,
                                        boolean upperSide) {
        if (taker == null || taker.getId() == null || foulSpot == null) {
            return;
        }
        PlayerPositionDTO takerPos = getPlayerPosition(rt, taker);
        if (takerPos == null) {
            return;
        }

        takerPos.setX(foulSpot.getX());
        takerPos.setY(foulSpot.getY());
        rt.ball = new BallPositionDTO(foulSpot.getX(), foulSpot.getY());
        setCurrentCarrier(rt, taker, "restart");
        recordStoppagePause(rt, MatchRuntime.StoppageType.FREE_KICK, RESTART_PAUSE_TICKS);

        double goalDistance = estimateDistanceToGoal(rt, taker, attackingTeam);
        boolean directShot = shouldTakeDirectFreeKick(rt, taker, attackingTeam, foulSpot, direct, goalDistance);
        if (directShot) {
            handleShot(rt, match, minute, taker, new AIDecisionMaker.Decision(AIDecisionMaker.ActionType.SHOT, null), attackingTeam);
            return;
        }

        Player target = selectFreeKickTarget(rt, attackingTeam, taker, upperSide);
        if (target != null) {
            startPassTransit(rt, taker, target, attackingTeam, Math.max(0.8, resolvePassScatter(taker, target) * 0.78));
            clearRecentControlHints(rt);
            rt.lastTouchTeam = attackingTeam;
            return;
        }

        releaseBall(rt, taker, attackingTeam, Math.toIntExact(taker.getId()), null, 1.0);
    }

    private Player selectFreeKickTarget(MatchRuntime rt, String attackingTeam, Player taker, boolean upperSide) {
        List<Player> candidates = ("HOME".equals(attackingTeam) ? rt.homePlayers : rt.awayPlayers).stream()
                .filter(player -> player != null && player.getId() != null)
                .filter(player -> !samePlayer(player, taker))
                .filter(player -> player.getPosition() != Position.GK)
                .sorted(Comparator.comparingDouble(player -> {
                    PlayerPositionDTO pos = getPlayerPosition(rt, player);
                    if (pos == null) {
                        return 100.0;
                    }
                    double centralBonus = Math.abs(pos.getY() - 50.0) * 0.18;
                    double attackingBonus = "HOME".equals(attackingTeam) ? (100.0 - pos.getX()) * 0.08 : pos.getX() * 0.08;
                    double laneBias = upperSide ? Math.max(0.0, pos.getY() - 50.0) * 0.03 : Math.max(0.0, 50.0 - pos.getY()) * 0.03;
                    return centralBonus + attackingBonus + laneBias;
                }))
                .toList();
        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    private boolean shouldTakeDirectFreeKick(MatchRuntime rt,
                                             Player taker,
                                             String attackingTeam,
                                             PlayerPositionDTO foulSpot,
                                             boolean direct,
                                             double goalDistance) {
        if (!direct || taker == null || foulSpot == null) {
            return false;
        }
        double centrality = Math.abs(foulSpot.getY() - 50.0);
        double technique = skillExact(taker, SkillName.TECHNIQUE);
        double striker = skillExact(taker, SkillName.STRIKER);
        double takerQuality = ((technique * 0.55) + (striker * 0.45)) / 20.0;
        String style = normalizeStyleForSetPieces("HOME".equals(attackingTeam) ? rt.homeStyle : rt.awayStyle);
        double styleBias = switch (style) {
            case "ATTACKING" -> 0.08;
            case "POSSESSION" -> -0.02;
            case "DEFENSIVE" -> -0.05;
            default -> 0.02;
        };
        if (goalDistance <= 20.0 && centrality <= 16.0) {
            return true;
        }
        double chance = 0.0;
        if (goalDistance <= 23.5) {
            chance = 0.28 + (takerQuality * 0.24) + styleBias;
        } else if (goalDistance <= 27.5 && centrality <= 10.0) {
            chance = 0.10 + (takerQuality * 0.20) + styleBias;
        }
        return chance > 0.0 && random.nextDouble() < clamp(chance, 0.04, 0.62);
    }

    private void clearRecentControlHints(MatchRuntime rt) {
        rt.lastPassReceiverId = null;
        rt.lastPassReceiveTick = -100;
        rt.lastPassReceiveTeam = null;
        rt.lastDuelWinnerId = null;
        rt.lastDuelWinTick = -100;
        rt.lastDuelWinTeam = null;
        rt.lastRecoveryPlayerId = null;
        rt.lastRecoveryTick = -100;
        rt.lastRecoveryTeam = null;
    }

    private void recordGoalSnapshotBeforeRestart(MatchRuntime rt,
                                                 Player scorer,
                                                 Player goalkeeper,
                                                 String scoringTeam) {
        PlayerPositionDTO scorerPos = scorer != null ? getPlayerPosition(rt, scorer) : null;
        if (scorerPos != null) {
            double targetX = "HOME".equals(scoringTeam) ? 97.6 : 2.4;
            scorerPos.setX(clamp((scorerPos.getX() * 0.42) + (targetX * 0.58), MIN_X + 1.0, MAX_X - 1.0));
        }

        PlayerPositionDTO goalkeeperPos = goalkeeper != null ? getPlayerPosition(rt, goalkeeper) : null;
        if (goalkeeperPos != null) {
            double keeperX = "HOME".equals(scoringTeam) ? 92.8 : 7.2;
            goalkeeperPos.setX(clamp((goalkeeperPos.getX() * 0.58) + (keeperX * 0.42), MIN_X + 1.0, MAX_X - 1.0));
        }

        rt.ball = new BallPositionDTO("HOME".equals(scoringTeam) ? 99.0 : 1.0, 50.0);
        rt.ballInTransit = false;
        rt.pendingReceiverId = null;
        rt.currentCarrier = null;
        syncBallState(rt);
        rt.recordTick();
        rt.tick++;
    }

    private int cornerAttackPriority(Position position) {
        return switch (position) {
            case ATT -> 0;
            case DEF -> 1;
            case MID -> 2;
            case WNG -> 3;
            default -> 4;
        };
    }

    private int cornerDefensePriority(Position position) {
        return switch (position) {
            case DEF -> 0;
            case MID -> 1;
            case ATT, WNG -> 2;
            default -> 3;
        };
    }

    private Player findZonePriorityLooseBallTarget(MatchRuntime rt, List<Player> outfield, Player intended) {
        int[] ballBands = resolveSpatialBands(rt.ball.getX(), rt.ball.getY());
        PlayerPositionDTO ballPos = new PlayerPositionDTO(-1, "", rt.ball.getX(), rt.ball.getY(), 0, 0);
        for (int radius = 0; radius <= 4; radius++) {
            int finalRadius = radius;
            List<Player> ringCandidates = outfield.stream()
                    .filter(player -> {
                        PlayerPositionDTO pos = getPlayerPosition(rt, player);
                        return pos != null && zoneDistance(resolveSpatialBands(pos.getX(), pos.getY()), ballBands) == finalRadius;
                    })
                    .sorted(Comparator.comparingDouble(player -> distanceBetween(getPlayerPosition(rt, player), ballPos)))
                    .toList();
            if (ringCandidates.isEmpty()) {
                continue;
            }
            if (intended != null && ringCandidates.stream().anyMatch(player -> Objects.equals(player.getId(), intended.getId()))) {
                PlayerPositionDTO intendedPos = getPlayerPosition(rt, intended);
                if (distanceBetween(intendedPos, ballPos) <= PENDING_RECEIVER_LOCK_DISTANCE + RECEIVER_PRIORITY_MARGIN) {
                    return intended;
                }
            }
            return ringCandidates.getFirst();
        }
        return null;
    }

    private Player findDefensivePressureThreat(MatchRuntime rt, PlayerPositionDTO pos, Player player, double[] tacticalTarget) {
        double reactionWeight = defensiveReactionWeight(player.getPosition());
        if (reactionWeight <= 0.0) {
            return null;
        }

        String defenderTeam = pos.getTeam();
        List<Player> opponents = "HOME".equals(defenderTeam) ? rt.awayPlayers : rt.homePlayers;
        int[] ownBands = resolveSpatialBands(tacticalTarget[0], tacticalTarget[1]);
        Player bestThreat = null;
        double bestScore = defensiveReactionThreshold(player.getPosition());

        for (Player opponent : opponents) {
            if (opponent == null || opponent.getPosition() == Position.GK) {
                continue;
            }
            PlayerPositionDTO opponentPos = getPlayerPosition(rt, opponent);
            if (opponentPos == null) {
                continue;
            }

            int zoneGap = zoneDistance(ownBands, resolveSpatialBands(opponentPos.getX(), opponentPos.getY()));
            boolean carrierThreat = rt.currentCarrier != null && opponent.getId() != null
                    && rt.currentCarrier.getId() == Math.toIntExact(opponent.getId());
            boolean nearGoalCarrierThreat = carrierThreat && isThreatNearOwnGoal(opponentPos, defenderTeam);
            int maxZoneGap = nearGoalCarrierThreat ? 2 : 1;
            if (zoneGap > maxZoneGap) {
                continue;
            }

            int nearbyMarkers = countNearbyDefenders(
                    rt,
                    defenderTeam,
                    opponentPos,
                    pos.getId(),
                    nearGoalCarrierThreat ? DEFENSIVE_MARK_RADIUS + 2.5 : DEFENSIVE_MARK_RADIUS
            );
            if (nearbyMarkers > 0 && !carrierThreat) {
                continue;
            }

            double distancePenalty = Math.min(nearGoalCarrierThreat ? 30.0 : 24.0, distanceBetween(pos, opponentPos))
                    * (nearGoalCarrierThreat ? 0.014 : 0.018);
            double score = reactionWeight
                    + (carrierThreat ? (nearGoalCarrierThreat ? 0.92 : 0.58) : 0.18)
                    + (nearbyMarkers == 0 ? 0.20 : 0.0)
                    + (nearGoalCarrierThreat ? 0.22 : 0.0)
                    - zoneGap * (nearGoalCarrierThreat ? 0.08 : 0.16)
                    - distancePenalty;

            if (nearGoalCarrierThreat && player.getPosition() == Position.MID) {
                score += 0.10;
            }

            if (score > bestScore) {
                bestScore = score;
                bestThreat = opponent;
            }
        }

        return bestThreat;
    }

    private double[] resolveMovementOverrideTarget(MatchRuntime rt,
                                                  PlayerPositionDTO pos,
                                                  Player player,
                                                  double[] tacticalTarget,
                                                  boolean inPossession) {
        if (rt.ball == null) {
            return null;
        }

        if (rt.currentCarrier == null && !rt.ballInTransit) {
            int[] targetBands = resolveSpatialBands(tacticalTarget[0], tacticalTarget[1]);
            int[] ballBands = resolveSpatialBands(rt.ball.getX(), rt.ball.getY());
            if (zoneDistance(targetBands, ballBands) <= 1
                    || isPointNearMovementRoute(pos, tacticalTarget, rt.ball.getX(), rt.ball.getY(), 8.5, 22.0)) {
                return new double[]{rt.ball.getX(), rt.ball.getY()};
            }
        }

        if (!inPossession) {
            double[] defensiveAssignment = resolveDefensiveAssignmentTarget(rt, pos, player, tacticalTarget);
            if (defensiveAssignment != null) {
                return defensiveAssignment;
            }
        }

        return null;
    }

    private double[] resolveDefensiveAssignmentTarget(MatchRuntime rt,
                                                      PlayerPositionDTO pos,
                                                      Player player,
                                                      double[] tacticalTarget) {
        if (rt.currentCarrier != null && shouldPressCarrier(rt, pos, player, tacticalTarget)) {
            return new double[]{rt.ball.getX(), rt.ball.getY()};
        }

        Player runnerThreat = findRunnerThreat(rt, pos, player, tacticalTarget);
        if (runnerThreat != null) {
            PlayerPositionDTO runnerPos = getPlayerPosition(rt, runnerThreat);
            if (runnerPos != null) {
                return new double[]{runnerPos.getX(), runnerPos.getY()};
            }
        }

        Player laneThreat = findPassingLaneThreat(rt, pos, player, tacticalTarget);
        if (laneThreat != null) {
            PlayerPositionDTO threatPos = getPlayerPosition(rt, laneThreat);
            if (threatPos != null) {
                return new double[]{
                        (tacticalTarget[0] + threatPos.getX()) / 2.0,
                        (tacticalTarget[1] + threatPos.getY()) / 2.0
                };
            }
        }

        Player pressureThreat = findDefensivePressureThreat(rt, pos, player, tacticalTarget);
        if (pressureThreat != null) {
            PlayerPositionDTO threatPos = getPlayerPosition(rt, pressureThreat);
            if (threatPos != null && isThreatRelevantToMovementRoute(pos, tacticalTarget, threatPos)) {
                return new double[]{threatPos.getX(), threatPos.getY()};
            }
        }

        return null;
    }

    private boolean shouldPressCarrier(MatchRuntime rt,
                                       PlayerPositionDTO pos,
                                       Player player,
                                       double[] tacticalTarget) {
        if (rt.currentCarrier == null || rt.ball == null || player.getPosition() == Position.GK) {
            return false;
        }
        if (Objects.equals(rt.currentCarrier.getTeam(), pos.getTeam())) {
            return false;
        }

        int[] targetBands = resolveSpatialBands(tacticalTarget[0], tacticalTarget[1]);
        int[] ballBands = resolveSpatialBands(rt.ball.getX(), rt.ball.getY());
        int zoneGap = zoneDistance(targetBands, ballBands);
        if (zoneGap > 1) {
            return false;
        }

        double distanceToBall = Math.hypot(pos.getX() - rt.ball.getX(), pos.getY() - rt.ball.getY());
        double pressThreshold = switch (player.getPosition()) {
            case DEF -> 16.0;
            case MID -> 14.0;
            case WNG -> 11.5;
            case ATT -> 9.5;
            default -> 0.0;
        };
        return distanceToBall <= pressThreshold;
    }

    private Player findRunnerThreat(MatchRuntime rt,
                                    PlayerPositionDTO pos,
                                    Player player,
                                    double[] tacticalTarget) {
        String defenderTeam = pos.getTeam();
        boolean homeDefender = "HOME".equals(defenderTeam);
        return ("HOME".equals(defenderTeam) ? rt.awayPlayers : rt.homePlayers).stream()
                .filter(opponent -> opponent != null && opponent.getId() != null && opponent.getPosition() != Position.GK)
                .filter(opponent -> {
                    PlayerPositionDTO opponentPos = getPlayerPosition(rt, opponent);
                    if (opponentPos == null) {
                        return false;
                    }
                    double forwardThreat = homeDefender
                            ? opponentPos.getX() - tacticalTarget[0]
                            : tacticalTarget[0] - opponentPos.getX();
                    return forwardThreat >= (player.getPosition() == Position.DEF ? 2.0 : 4.0)
                            && Math.abs(opponentPos.getY() - tacticalTarget[1]) <= 14.0
                            && isThreatRelevantToMovementRoute(pos, tacticalTarget, opponentPos);
                })
                .min(Comparator.comparingDouble(opponent -> {
                    PlayerPositionDTO opponentPos = getPlayerPosition(rt, opponent);
                    double routePenalty = opponentPos == null ? 100.0 : distanceBetween(pos, opponentPos);
                    return routePenalty;
                }))
                .orElse(null);
    }

    private Player findPassingLaneThreat(MatchRuntime rt,
                                         PlayerPositionDTO pos,
                                         Player player,
                                         double[] tacticalTarget) {
        if (rt.currentCarrier == null) {
            return null;
        }
        PlayerPositionDTO carrierPos = getPlayerPosition(rt, findPlayerById(rt, rt.currentCarrier.getId()));
        if (carrierPos == null || Objects.equals(rt.currentCarrier.getTeam(), pos.getTeam())) {
            return null;
        }
        return ("HOME".equals(pos.getTeam()) ? rt.awayPlayers : rt.homePlayers).stream()
                .filter(opponent -> opponent != null && opponent.getId() != null && opponent.getPosition() != Position.GK)
                .filter(opponent -> {
                    PlayerPositionDTO opponentPos = getPlayerPosition(rt, opponent);
                    if (opponentPos == null) {
                        return false;
                    }
                    if (distanceBetween(pos, opponentPos) > 18.0) {
                        return false;
                    }
                    return distancePointToSegment(
                            opponentPos.getX(),
                            opponentPos.getY(),
                            carrierPos.getX(),
                            carrierPos.getY(),
                            tacticalTarget[0],
                            tacticalTarget[1]
                    ) <= 7.5;
                })
                .min(Comparator.comparingDouble(opponent -> {
                    PlayerPositionDTO opponentPos = getPlayerPosition(rt, opponent);
                    return opponentPos == null ? 100.0 : distanceBetween(pos, opponentPos);
                }))
                .orElse(null);
    }

    private double[] applyTeamCompactnessToTarget(MatchRuntime rt,
                                                  PlayerPositionDTO pos,
                                                  Player player,
                                                  double[] tacticalTarget,
                                                  boolean inPossession) {
        if (player == null || player.getPosition() == Position.GK) {
            return tacticalTarget;
        }
        List<Player> teammates = "HOME".equals(pos.getTeam()) ? rt.homePlayers : rt.awayPlayers;
        double[] teamCenter = resolveTeamOutfieldCenter(rt, teammates);
        if (teamCenter == null) {
            return tacticalTarget;
        }

        double xBlend;
        double yBlend;
        if (inPossession) {
            xBlend = switch (player.getPosition()) {
                case DEF -> 0.10;
                case MID -> 0.07;
                case WNG, ATT -> 0.04;
                default -> 0.0;
            };
            yBlend = 0.08;
        } else {
            xBlend = switch (player.getPosition()) {
                case DEF -> 0.08;
                case MID -> 0.12;
                case WNG -> 0.10;
                case ATT -> 0.06;
                default -> 0.0;
            };
            yBlend = 0.12;
        }

        double compactX = tacticalTarget[0] + (teamCenter[0] - tacticalTarget[0]) * xBlend;
        double compactY = tacticalTarget[1] + (teamCenter[1] - tacticalTarget[1]) * yBlend;
        double maxXShift = inPossession ? 3.0 : 4.0;
        double maxYShift = inPossession ? 2.8 : 3.6;
        return new double[]{
                clamp(compactX, tacticalTarget[0] - maxXShift, tacticalTarget[0] + maxXShift),
                clamp(compactY, tacticalTarget[1] - maxYShift, tacticalTarget[1] + maxYShift)
        };
    }

    private double[] applySlotRoleBias(MatchRuntime rt,
                                       PlayerPositionDTO pos,
                                       Player player,
                                       double[] target,
                                       boolean inPossession) {
        if (player == null || player.getId() == null || target == null || player.getPosition() == Position.GK) {
            return target;
        }
        String slotKey = rt.playerSlotKeys.get(Math.toIntExact(player.getId()));
        if (slotKey == null || slotKey.isBlank()) {
            return target;
        }

        double targetX = target[0];
        double targetY = target[1];
        boolean home = "HOME".equals(pos.getTeam());
        double forwardSign = home ? 1.0 : -1.0;

        switch (slotRoleFamily(slotKey)) {
            case FULLBACK -> {
                if (inPossession) {
                    targetX += 1.6 * forwardSign;
                    targetY += slotKey.endsWith("L") ? -1.2 : 1.2;
                } else {
                    targetX -= 0.8 * forwardSign;
                }
            }
            case CENTER_BACK -> targetX -= 0.6 * forwardSign;
            case DM -> targetX -= 0.9 * forwardSign;
            case HALFSPACE_MID -> {
                targetX += inPossession ? 0.9 * forwardSign : 0.1;
                targetY += slotKey.endsWith("L") ? -1.0 : 1.0;
            }
            case AM -> {
                targetX += inPossession ? 1.4 * forwardSign : 0.2;
                if (slotKey.endsWith("L")) {
                    targetY -= 1.4;
                } else if (slotKey.endsWith("R")) {
                    targetY += 1.4;
                }
            }
            case WIDEMID, WINGER -> {
                targetY += slotKey.endsWith("L") ? -1.6 : 1.6;
                if (inPossession) {
                    targetX += 1.2 * forwardSign;
                }
            }
            case SPLIT_STRIKER -> {
                targetX += inPossession ? 1.1 * forwardSign : 0.0;
                targetY += slotKey.endsWith("L") ? -1.5 : 1.5;
            }
            case CENTRAL_STRIKER -> targetX += inPossession ? 1.6 * forwardSign : 0.0;
            case GENERIC -> {
            }
        }

        return new double[]{
                clamp(targetX, MIN_X, MAX_X),
                clamp(targetY, MIN_Y, MAX_Y)
        };
    }

    private SlotRoleFamily slotRoleFamily(String slotKey) {
        if (slotKey == null || slotKey.isBlank()) {
            return SlotRoleFamily.GENERIC;
        }
        if ("DL".equals(slotKey) || "DR".equals(slotKey)) {
            return SlotRoleFamily.FULLBACK;
        }
        if ("DCL".equals(slotKey) || "DCR".equals(slotKey) || "DC".equals(slotKey)) {
            return SlotRoleFamily.CENTER_BACK;
        }
        if ("DM".equals(slotKey) || "DML".equals(slotKey) || "DMR".equals(slotKey)) {
            return SlotRoleFamily.DM;
        }
        if ("CML".equals(slotKey) || "CMR".equals(slotKey)) {
            return SlotRoleFamily.HALFSPACE_MID;
        }
        if ("AML".equals(slotKey) || "AMR".equals(slotKey) || "AMC".equals(slotKey)) {
            return SlotRoleFamily.AM;
        }
        if ("ML".equals(slotKey) || "MR".equals(slotKey)) {
            return SlotRoleFamily.WIDEMID;
        }
        if ("WL".equals(slotKey) || "WR".equals(slotKey)) {
            return SlotRoleFamily.WINGER;
        }
        if ("STL".equals(slotKey) || "STR".equals(slotKey)) {
            return SlotRoleFamily.SPLIT_STRIKER;
        }
        if ("ST".equals(slotKey)) {
            return SlotRoleFamily.CENTRAL_STRIKER;
        }
        return SlotRoleFamily.GENERIC;
    }

    private enum SlotRoleFamily {
        FULLBACK,
        CENTER_BACK,
        DM,
        HALFSPACE_MID,
        AM,
        WIDEMID,
        WINGER,
        CENTRAL_STRIKER,
        SPLIT_STRIKER,
        GENERIC
    }

    private double[] resolveTeamOutfieldCenter(MatchRuntime rt, List<Player> teamPlayers) {
        if (teamPlayers == null || teamPlayers.isEmpty()) {
            return null;
        }
        double sumX = 0.0;
        double sumY = 0.0;
        int count = 0;
        for (Player teammate : teamPlayers) {
            if (teammate == null || teammate.getPosition() == Position.GK) {
                continue;
            }
            PlayerPositionDTO teammatePos = getPlayerPosition(rt, teammate);
            if (teammatePos == null) {
                continue;
            }
            sumX += teammatePos.getX();
            sumY += teammatePos.getY();
            count++;
        }
        if (count == 0) {
            return null;
        }
        return new double[]{sumX / count, sumY / count};
    }

    private int countNearbyDefenders(MatchRuntime rt, String defenderTeam, PlayerPositionDTO opponentPos, int excludePlayerId) {
        return countNearbyDefenders(rt, defenderTeam, opponentPos, excludePlayerId, DEFENSIVE_MARK_RADIUS);
    }

    private int countNearbyDefenders(MatchRuntime rt,
                                     String defenderTeam,
                                     PlayerPositionDTO opponentPos,
                                     int excludePlayerId,
                                     double radius) {
        List<Player> defenders = "HOME".equals(defenderTeam) ? rt.homePlayers : rt.awayPlayers;
        return (int) defenders.stream()
                .filter(player -> player.getPosition() != Position.GK)
                .filter(player -> player.getId() != null && Math.toIntExact(player.getId()) != excludePlayerId)
                .map(player -> getPlayerPosition(rt, player))
                .filter(Objects::nonNull)
                .filter(defPos -> distanceBetween(defPos, opponentPos) <= radius)
                .count();
    }

    private boolean isThreatNearOwnGoal(PlayerPositionDTO opponentPos, String defenderTeam) {
        double distanceToGoal = "HOME".equals(defenderTeam) ? opponentPos.getX() : 100.0 - opponentPos.getX();
        return distanceToGoal <= DANGER_PRESS_DISTANCE_TO_GOAL;
    }

    private double defensiveReactionWeight(Position position) {
        return switch (position) {
            case DEF -> 0.78;
            case MID -> 0.58;
            case WNG -> 0.16;
            case ATT -> 0.08;
            default -> 0.0;
        };
    }

    private double defensiveReactionThreshold(Position position) {
        return switch (position) {
            case DEF -> 0.50;
            case MID -> 0.58;
            case WNG -> 0.92;
            case ATT -> 1.06;
            default -> Double.MAX_VALUE;
        };
    }

    private double applyDefensiveLineDiscipline(MatchRuntime rt,
                                                PlayerPositionDTO pos,
                                                Player player,
                                                double targetX,
                                                boolean inPossession,
                                                boolean homeTeam) {
        if (player.getPosition() != Position.DEF || inPossession || rt.ball == null) {
            return targetX;
        }

        double disciplinedGap = DEFENSIVE_LINE_GAP * TACTICAL_DISCIPLINE;
        double minLine = homeTeam ? 18.0 : 58.0;
        double maxLine = homeTeam ? 42.0 : 82.0;
        double disciplinedLine = homeTeam
                ? clamp(rt.ball.getX() - disciplinedGap, minLine, maxLine)
                : clamp(rt.ball.getX() + disciplinedGap, minLine, maxLine);

        Player strikerThreat = findCenterBackThreat(rt, pos, pos.getTeam());
        if (strikerThreat != null) {
            PlayerPositionDTO threatPos = getPlayerPosition(rt, strikerThreat);
            if (threatPos != null) {
                double coverLine = homeTeam
                        ? clamp(threatPos.getX() - (DEFENSIVE_COVER_GAP * TACTICAL_DISCIPLINE), minLine, maxLine)
                        : clamp(threatPos.getX() + (DEFENSIVE_COVER_GAP * TACTICAL_DISCIPLINE), minLine, maxLine);
                disciplinedLine = homeTeam
                        ? Math.min(disciplinedLine, coverLine)
                        : Math.max(disciplinedLine, coverLine);
            }
        }

        return homeTeam ? Math.max(targetX, disciplinedLine) : Math.min(targetX, disciplinedLine);
    }

    private int defensivePressurePriority(Position position) {
        return switch (position) {
            case DEF -> 0;
            case MID -> 1;
            case WNG -> 2;
            case ATT -> 3;
            default -> 4;
        };
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

    private boolean isPenaltyFoulLocation(PlayerPositionDTO carrier, boolean attacksRight) {
        if (!isInPenaltyBox(carrier, attacksRight)) {
            return false;
        }
        double canonicalX = attacksRight ? carrier.getX() : 100.0 - carrier.getX();
        double centralGap = Math.abs(carrier.getY() - 50.0);
        return canonicalX >= 86.5 && centralGap <= 20.0;
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

        if (rt.possessionPhase == MatchRuntime.PossessionPhase.BUILD_UP) {
            return false;
        }

        double goalDistance = estimateDistanceToGoal(rt, shooter, ballTeam);
        if (goalDistance > SHOT_TRIGGER_DISTANCE) {
            return false;
        }
        if (rt.possessionPhase == MatchRuntime.PossessionPhase.PROGRESSION && goalDistance > 17.5) {
            return false;
        }
        List<Player> nearbyDefenders = getNearbyDefenders(rt, shooter, ballTeam);
        if (goalDistance > 18.5 && nearbyDefenders.size() > 1) {
            return false;
        }
        if (!hasShotPermission(rt, shooter, ballTeam, goalDistance, nearbyDefenders)) {
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

        return receivedShotPass || wonDuelHigh || recoveredHigh
                || hasCloseRangeCarryShotWindow(rt, shooter, ballTeam, goalDistance, nearbyDefenders);
    }

    private boolean hasShotPermission(MatchRuntime rt,
                                      Player shooter,
                                      String ballTeam,
                                      double goalDistance,
                                      List<Player> nearbyDefenders) {
        PlayerPositionDTO pos = getPlayerPosition(rt, shooter);
        if (pos == null) {
            return false;
        }

        double centralGap = Math.abs(pos.getY() - 50.0);
        int supportCount = countNearbyAttackSupport(rt, shooter, ballTeam);
        boolean centralLane = centralGap <= 16.0;
        boolean closeRange = goalDistance <= 14.0;
        boolean boxChaos = rt.possessionPhase == MatchRuntime.PossessionPhase.BOX_CHAOS;
        boolean cleanWindow = nearbyDefenders.isEmpty() || (nearbyDefenders.size() == 1 && closeRange);
        boolean recentAttackTrigger = wasRecentAttackTrigger(rt, shooter, ballTeam);

        if (closeRange && (centralLane || cleanWindow || recentAttackTrigger)) {
            return true;
        }
        if (boxChaos && goalDistance <= 18.0) {
            return true;
        }
        if (goalDistance <= 17.5 && centralLane && nearbyDefenders.size() <= 1) {
            return true;
        }

        if (goalDistance > 22.0 && !centralLane) {
            return false;
        }
        if (goalDistance > 19.0 && nearbyDefenders.size() >= 2 && supportCount == 0) {
            return false;
        }
        if (!centralLane && goalDistance > 16.5 && supportCount == 0) {
            return false;
        }
        if (rt.possessionPhase == MatchRuntime.PossessionPhase.PROGRESSION
                && goalDistance > 15.5
                && !recentAttackTrigger
                && supportCount == 0) {
            return false;
        }

        return goalDistance <= 20.5 || recentAttackTrigger || supportCount > 0;
    }

    private boolean wasRecentAttackTrigger(MatchRuntime rt, Player shooter, String ballTeam) {
        if (shooter == null || shooter.getId() == null) {
            return false;
        }
        int shooterId = Math.toIntExact(shooter.getId());
        return (Objects.equals(rt.lastPassReceiverId, shooterId)
                && Objects.equals(rt.lastPassReceiveTeam, ballTeam)
                && (rt.tick - rt.lastPassReceiveTick) <= SHOT_WINDOW_TICKS)
                || (Objects.equals(rt.lastDuelWinnerId, shooterId)
                && Objects.equals(rt.lastDuelWinTeam, ballTeam)
                && (rt.tick - rt.lastDuelWinTick) <= SHOT_WINDOW_TICKS)
                || (Objects.equals(rt.lastRecoveryPlayerId, shooterId)
                && Objects.equals(rt.lastRecoveryTeam, ballTeam)
                && (rt.tick - rt.lastRecoveryTick) <= SHOT_WINDOW_TICKS);
    }

    private int countNearbyAttackSupport(MatchRuntime rt, Player shooter, String ballTeam) {
        PlayerPositionDTO shooterPos = getPlayerPosition(rt, shooter);
        if (shooterPos == null) {
            return 0;
        }
        List<Player> teammates = "HOME".equals(ballTeam) ? rt.homePlayers : rt.awayPlayers;
        return (int) teammates.stream()
                .filter(teammate -> teammate != null && teammate.getId() != null)
                .filter(teammate -> !Objects.equals(teammate.getId(), shooter.getId()))
                .filter(teammate -> teammate.getPosition() != Position.GK)
                .map(teammate -> getPlayerPosition(rt, teammate))
                .filter(Objects::nonNull)
                .filter(teammatePos -> distanceBetween(teammatePos, shooterPos) <= 15.0)
                .filter(teammatePos -> "HOME".equals(ballTeam)
                        ? teammatePos.getX() >= shooterPos.getX() - 4.0
                        : teammatePos.getX() <= shooterPos.getX() + 4.0)
                .count();
    }

    private boolean hasCloseRangeCarryShotWindow(MatchRuntime rt,
                                                 Player shooter,
                                                 String ballTeam,
                                                 double goalDistance,
                                                 List<Player> nearbyDefenders) {
        if (shooter == null || shooter.getPosition() == Position.GK) {
            return false;
        }
        if (goalDistance <= 17.5) {
            return true;
        }
        if (goalDistance <= 21.5) {
            return nearbyDefenders.size() <= 1;
        }
        return goalDistance <= SHOT_TRIGGER_DISTANCE
                && nearbyDefenders.isEmpty()
                && isCentralShootingLane(rt, shooter, ballTeam);
    }

    private void updatePossessionPhase(MatchRuntime rt) {
        if (rt == null) {
            return;
        }
        if (rt.ballInTransit || rt.currentCarrier == null) {
            rt.possessionPhase = MatchRuntime.PossessionPhase.TRANSITION;
            return;
        }

        Player carrier = findPlayerById(rt, rt.currentCarrier.getId());
        PlayerPositionDTO carrierPos = carrier != null ? getPlayerPosition(rt, carrier) : rt.currentCarrier;
        if (carrierPos == null) {
            rt.possessionPhase = MatchRuntime.PossessionPhase.TRANSITION;
            return;
        }

        String team = carrierPos.getTeam();
        if (!Objects.equals(rt.currentPossessionTeam, team)) {
            rt.currentPossessionTeam = team;
            rt.possessionStartTick = rt.tick;
            rt.possessionStartX = carrierPos.getX();
            rt.possessionStartY = carrierPos.getY();
            rt.possessionTicks = 0;
        } else {
            rt.possessionTicks = Math.max(0, rt.tick - rt.possessionStartTick);
        }

        double canonicalX = "HOME".equals(team) ? carrierPos.getX() : 100.0 - carrierPos.getX();
        double canonicalStartX = "HOME".equals(team) ? rt.possessionStartX : 100.0 - rt.possessionStartX;
        double progressGain = canonicalX - canonicalStartX;
        double widthGap = Math.abs(carrierPos.getY() - 50.0);
        boolean inBoxCorridor = canonicalX >= 84.0 && widthGap <= 24.0;

        if (inBoxCorridor || (canonicalX >= 78.0 && rt.possessionTicks >= 7)) {
            rt.possessionPhase = MatchRuntime.PossessionPhase.BOX_CHAOS;
        } else if (canonicalX >= 66.0 || progressGain >= 20.0) {
            rt.possessionPhase = MatchRuntime.PossessionPhase.FINAL_THIRD;
        } else if (canonicalX >= 38.0 || progressGain >= 8.0 || rt.possessionTicks >= 6) {
            rt.possessionPhase = MatchRuntime.PossessionPhase.PROGRESSION;
        } else {
            rt.possessionPhase = MatchRuntime.PossessionPhase.BUILD_UP;
        }
    }

    private boolean isCentralShootingLane(MatchRuntime rt, Player shooter, String ballTeam) {
        PlayerPositionDTO pos = getPlayerPosition(rt, shooter);
        if (pos == null || !isDangerousAttackingPosition(rt, shooter, ballTeam)) {
            return false;
        }
        return Math.abs(pos.getY() - 50.0) <= 18.0;
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
        double reviewChance = goal != null ? VAR_GOAL_REVIEW_CHANCE : VAR_PENALTY_REVIEW_CHANCE;
        if (random.nextDouble() >= reviewChance) {
            return;
        }

        rt.activeStoppage = MatchRuntime.StoppageType.VAR_REVIEW;
        rt.stoppageTicks = Math.max(rt.stoppageTicks, penalty != null ? 5 : 4);

        VARReviewEvent var = new VARReviewEvent();
        var.setMinute(minute);
        var.setTick(goal != null && goal.getTick() > 0
                ? goal.getTick()
                : (penalty != null && penalty.getTick() > 0 ? penalty.getTick() : rt.tick));
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
        double forwardProgress = homeAttack
                ? receiverPos.getX() - passerPos.getX()
                : passerPos.getX() - receiverPos.getX();
        double lateralGap = Math.abs(receiverPos.getY() - passerPos.getY());
        if (!isLikelyOffsideTargetLane(forwardProgress, lateralGap)) {
            return false;
        }

        boolean aheadOfBall = forwardProgress > 1.0;
        boolean inOppositionHalf = homeAttack ? receiverPos.getX() > 50.0 : receiverPos.getX() < 50.0;
        boolean beyondLine = homeAttack
                ? receiverPos.getX() > offsideLine + 1.0
                : receiverPos.getX() < offsideLine - 1.0;

        return aheadOfBall && inOppositionHalf && beyondLine;
    }

    private boolean isLikelyOffsideTargetLane(double forwardProgress, double lateralGap) {
        if (forwardProgress <= 2.4) {
            return false;
        }
        if (lateralGap <= 18.0) {
            return true;
        }
        if (lateralGap >= 30.0 && forwardProgress <= 12.0) {
            return false;
        }
        return (lateralGap / Math.max(1.0, forwardProgress)) <= 2.4;
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
        String possessionTeam = resolvePossessionTeam(rt);
        if (!Objects.equals(possessionTeam, pos.getTeam())) {
            rt.offsideStreak.remove(pos.getId());
            pos.setOffsideTicksRemaining(0);
            pos.setRetreatTicksRemaining(0);
            return targetX;
        }

        double line = calculateOffsideLine(rt, pos.getTeam());
        double tolerance = 0.75;
        double safeLine = homeTeam ? line - ONSIDE_BUFFER : line + ONSIDE_BUFFER;
        boolean isOffside = homeTeam ? pos.getX() > line + tolerance : pos.getX() < line - tolerance;
        if (!isOffside) {
            rt.offsideStreak.remove(pos.getId());
            pos.setOffsideTicksRemaining(0);
            pos.setRetreatTicksRemaining(0);
            return homeTeam ? Math.min(targetX, safeLine) : Math.max(targetX, safeLine);
        }

        int streak = rt.offsideStreak.getOrDefault(pos.getId(), 0) + 1;
        rt.offsideStreak.put(pos.getId(), streak);
        pos.setOffsideTicksRemaining(streak);

        if (streak >= OFFSIDE_RETREAT_TRIGGER_STREAK) {
            if (pos.getRetreatTicksRemaining() < MAX_RETREAT_TICKS) {
                pos.setRetreatTicksRemaining(Math.min(MAX_RETREAT_TICKS, pos.getRetreatTicksRemaining() + 2));
            }
            double progress = pos.getRetreatTicksRemaining() / (double) MAX_RETREAT_TICKS;
            double effectiveForce = RETREAT_FORCE + DEEP_RETREAT_FORCE * progress;
            double retreatLine = homeTeam ? (line - effectiveForce) : (line + effectiveForce);
            targetX = homeTeam ? Math.min(targetX, retreatLine) : Math.max(targetX, retreatLine);
        } else {
            pos.setRetreatTicksRemaining(Math.max(pos.getRetreatTicksRemaining(), 2));
            double immediateRetreatLine = homeTeam ? (line - 15.5) : (line + 15.5);
            targetX = homeTeam ? Math.min(targetX, immediateRetreatLine) : Math.max(targetX, immediateRetreatLine);
        }

        return targetX;
    }

    private String resolvePossessionTeam(MatchRuntime rt) {
        if (rt.currentCarrier != null && rt.currentCarrier.getTeam() != null) {
            return rt.currentCarrier.getTeam();
        }
        if (rt.pendingPassTeam != null) {
            return rt.pendingPassTeam;
        }
        return rt.lastTouchTeam;
    }

    private void keepAttackerOnside(MatchRuntime rt, PlayerPositionDTO pos) {
        if (pos == null || !Objects.equals(resolvePossessionTeam(rt), pos.getTeam())) {
            return;
        }

        double line = calculateOffsideLine(rt, pos.getTeam());
        double retreatPadding = pos.getRetreatTicksRemaining() > 0 || pos.getOffsideTicksRemaining() > 0
                ? Math.max(ONSIDE_BUFFER, 8.0 + (pos.getRetreatTicksRemaining() * 1.5))
                : ONSIDE_BUFFER;
        double safeX = "HOME".equals(pos.getTeam()) ? line - retreatPadding : line + retreatPadding;
        double clamped = "HOME".equals(pos.getTeam()) ? Math.min(pos.getX(), safeX) : Math.max(pos.getX(), safeX);
        pos.setX(clamp(clamped, MIN_X, MAX_X));
    }

    private Player resolveAssistant(MatchRuntime rt, Player scorer, String scoringTeam) {
        if (rt.recentAssistPasserId == null || !Objects.equals(rt.recentAssistTeam, scoringTeam)) {
            return null;
        }
        if (rt.tick - rt.recentAssistTick > 12) {
            return null;
        }

        Player assistant = findPlayerById(rt, rt.recentAssistPasserId);
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
     * Resets players to restart targets without synthetic replay ticks.
     * The restart team snaps into shape while the non-restarting side blends on ordinary restarts
     * so transitions look less teleport-like.
     */
    private void resetPositionsForRestart(MatchRuntime rt, String teamInPossession) {
        resetPositionsForRestart(rt, teamInPossession, "STANDARD");
    }

    private void resetPositionsForRestart(MatchRuntime rt, String teamInPossession, String restartType) {
        Map<Integer, double[]> targetMap = buildRestartTargetMap(rt, teamInPossession, restartType);
        rt.players.forEach(pos -> {
            double[] target = targetMap.get(pos.getId());
            if (target == null) {
                return;
            }
            if (shouldBlendRestartPosition(restartType, teamInPossession, pos)) {
                double blendRatio = "KICKOFF".equals(restartType) ? KICKOFF_RESTART_BLEND_RATIO : RESTART_BLEND_RATIO;
                pos.setX(blendRestartCoordinate(pos.getX(), target[0], blendRatio, RESTART_BLEND_MAX_X_SHIFT));
                pos.setY(blendRestartCoordinate(pos.getY(), target[1], blendRatio, RESTART_BLEND_MAX_Y_SHIFT));
            } else {
                pos.setX(target[0]);
                pos.setY(target[1]);
            }
            pos.setOffsideTicksRemaining(0);
            pos.setRetreatTicksRemaining(0);
        });

        rt.currentCarrier = null;
        rt.ballInTransit = false;
        rt.ballTransitCanBeIntercepted = false;
        rt.ballTransitTicks = 0;
        rt.ballTransitMaxTicks = 0;
        rt.ballTransitMode = "CONTROLLED";
        log.debug("Applied immediate restart positioning for {}", restartType);
    }

    private boolean shouldBlendRestartPosition(String restartType, String teamInPossession, PlayerPositionDTO pos) {
        if (pos == null || teamInPossession == null) {
            return false;
        }
        if ("GOAL_KICK".equals(restartType) || "CORNER".equals(restartType)) {
            return false;
        }
        return !Objects.equals(teamInPossession, pos.getTeam());
    }

    private double blendRestartCoordinate(double current, double target, double blendRatio, double maxShift) {
        double delta = target - current;
        if (Math.abs(delta) <= 2.5) {
            return target;
        }
        double blended = current + clamp(delta * blendRatio, -maxShift, maxShift);
        return Math.abs(target - blended) <= 1.5 ? target : blended;
    }

    private Player selectRestartPlayer(MatchRuntime rt, String team) {
        return selectRestartPlayer(rt, team, "STANDARD");
    }

    private Player selectRestartPlayer(MatchRuntime rt, String team, String restartType) {
        List<Player> players = "HOME".equals(team) ? rt.homePlayers : rt.awayPlayers;
        List<Player> fieldPlayers = players.stream()
                .filter(player -> player.getPosition() != Position.GK)
                .toList();
        if (fieldPlayers.isEmpty()) {
            return players.isEmpty() ? null : players.getFirst();
        }

        List<Position> preferenceOrder = switch (restartType) {
            case "KICKOFF" -> List.of(Position.MID, Position.ATT, Position.WNG, Position.DEF);
            case "DEFENSIVE_RESTART" -> List.of(Position.DEF, Position.MID, Position.WNG, Position.ATT);
            default -> List.of(Position.DEF, Position.MID, Position.WNG, Position.ATT);
        };

        for (Position preferredPosition : preferenceOrder) {
            List<Player> candidates = fieldPlayers.stream()
                    .filter(player -> player.getPosition() == preferredPosition)
                    .toList();
            if (!candidates.isEmpty()) {
                return candidates.get(random.nextInt(candidates.size()));
            }
        }

        return fieldPlayers.getFirst();
    }

    private Player selectGoalKickTaker(MatchRuntime rt, String team, double preferredY) {
        List<Player> players = "HOME".equals(team) ? rt.homePlayers : rt.awayPlayers;

        Player defender = players.stream()
                .filter(player -> player.getPosition() == Position.DEF)
                .min(Comparator.comparingDouble(player -> {
                    PlayerPositionDTO pos = getPlayerPosition(rt, player);
                    double laneDistance = pos != null ? Math.abs(pos.getY() - preferredY) : 100.0;
                    double depthPenalty = pos != null
                            ? Math.abs(pos.getX() - ("HOME".equals(team) ? 18.0 : 82.0)) * 0.2
                            : 8.0;
                    return laneDistance + depthPenalty;
                }))
                .orElse(null);
        if (defender != null) {
            return defender;
        }

        return players.stream()
                .filter(player -> player.getPosition() != Position.GK)
                .findFirst()
                .orElse(null);
    }

    private Player setupGoalKickRestart(MatchRuntime rt,
                                        Match match,
                                        int minute,
                                        String restartTeam,
                                        boolean homeGoalSide,
                                        boolean upperSide) {
        clearAssistChain(rt);
        double goalKickX = homeGoalSide ? 8.8 : 91.2;
        double goalKickY = upperSide ? 38.0 : 62.0;
        Player restartPlayer = selectGoalKickTaker(rt, restartTeam, goalKickY);

        positionGoalKickActors(rt, restartTeam, restartPlayer, goalKickX, goalKickY);
        eventGenerator.createGoalKickEvent(rt, match, minute, restartTeam, restartPlayer);
        recordStoppagePause(rt, MatchRuntime.StoppageType.GOAL_KICK, RESTART_PAUSE_TICKS);

        if (restartPlayer != null && restartPlayer.getId() != null) {
            releaseBall(rt, restartPlayer, restartTeam, Math.toIntExact(restartPlayer.getId()), null, 5.0);
        }
        return restartPlayer;
    }

    private void positionGoalKickActors(MatchRuntime rt,
                                        String restartTeam,
                                        Player restartPlayer,
                                        double ballX,
                                        double ballY) {
        rt.ball = new BallPositionDTO(ballX, ballY);

        if (restartPlayer != null) {
            PlayerPositionDTO takerPos = getPlayerPosition(rt, restartPlayer);
            if (takerPos != null) {
                takerPos.setX(ballX);
                takerPos.setY(ballY);
            }
        }

        Player goalkeeper = getGoalkeeper(rt, restartTeam);
        if (goalkeeper != null && !samePlayer(goalkeeper, restartPlayer)) {
            PlayerPositionDTO goalkeeperPos = getPlayerPosition(rt, goalkeeper);
            if (goalkeeperPos != null) {
                goalkeeperPos.setX("HOME".equals(restartTeam) ? 8.5 : 91.5);
                goalkeeperPos.setY(50.0);
            }
        }
    }

    private boolean samePlayer(Player first, Player second) {
        if (first == null || second == null) {
            return false;
        }
        return Objects.equals(first.getId(), second.getId());
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

    private Player resolveSetPieceTaker(MatchRuntime rt, String team, String slotKey, Player fallback) {
        Player assigned = findPlayerBySlot(rt, team, slotKey);
        return assigned != null ? assigned : fallback;
    }

    private Player findPlayerBySlot(MatchRuntime rt, String team, String slotKey) {
        if (slotKey == null || slotKey.isBlank()) {
            return null;
        }
        List<Player> players = "HOME".equals(team) ? rt.homePlayers : rt.awayPlayers;
        return players.stream()
                .filter(player -> player != null && player.getId() != null)
                .filter(player -> Objects.equals(slotKey, rt.playerSlotKeys.get(Math.toIntExact(player.getId()))))
                .findFirst()
                .orElse(null);
    }

    private String resolvePenaltyTakerSlot(MatchRuntime rt, String team) {
        TacticsSetPieceDTO dto = "HOME".equals(team) ? rt.homeSetPieces : rt.awaySetPieces;
        return dto != null ? dto.getPenaltyTakerSlot() : null;
    }

    private String resolveFreeKickLeftTakerSlot(MatchRuntime rt, String team) {
        TacticsSetPieceDTO dto = "HOME".equals(team) ? rt.homeSetPieces : rt.awaySetPieces;
        return dto != null ? dto.getFreeKickLeftTakerSlot() : null;
    }

    private String resolveFreeKickRightTakerSlot(MatchRuntime rt, String team) {
        TacticsSetPieceDTO dto = "HOME".equals(team) ? rt.homeSetPieces : rt.awaySetPieces;
        return dto != null ? dto.getFreeKickRightTakerSlot() : null;
    }

    private String resolveCornerLeftTakerSlot(MatchRuntime rt, String team) {
        TacticsSetPieceDTO dto = "HOME".equals(team) ? rt.homeSetPieces : rt.awaySetPieces;
        return dto != null ? dto.getCornerLeftTakerSlot() : null;
    }

    private String resolveCornerRightTakerSlot(MatchRuntime rt, String team) {
        TacticsSetPieceDTO dto = "HOME".equals(team) ? rt.homeSetPieces : rt.awaySetPieces;
        return dto != null ? dto.getCornerRightTakerSlot() : null;
    }

    private double resolveSetPieceDeliveryQuality(Player taker) {
        if (taker == null) {
            return 0.0;
        }
        double passing = skillExact(taker, SkillName.PASSING);
        double technique = skillExact(taker, SkillName.TECHNIQUE);
        double playmaker = skillExact(taker, SkillName.PLAYMAKER);
        return clamp(((passing * 0.45) + (technique * 0.30) + (playmaker * 0.25)) / 20.0, 0.35, 1.0);
    }

    private String oppositeTeam(String team) {
        return "HOME".equals(team) ? "AWAY" : "HOME";
    }

    private void initializeTacticalProfiles(MatchRuntime rt, Match match) {
        String homeFormation = match.getHomeLineup() != null ? match.getHomeLineup().getFormation() : null;
        String awayFormation = match.getAwayLineup() != null ? match.getAwayLineup().getFormation() : null;
        rt.homeStyle = match.getHomeLineup() != null && match.getHomeLineup().getStyle() != null
                ? match.getHomeLineup().getStyle()
                : "BALANCED";
        rt.awayStyle = match.getAwayLineup() != null && match.getAwayLineup().getStyle() != null
                ? match.getAwayLineup().getStyle()
                : "BALANCED";

        rt.homeSlots = new ArrayList<>(teamTacticsService.getSlotDefinitions(homeFormation));
        rt.awaySlots = new ArrayList<>(teamTacticsService.getSlotDefinitions(awayFormation));
        rt.homeTacticalTargets = match.getHomeTeam() != null && match.getHomeTeam().getId() != null
                ? new HashMap<>(teamTacticsService.getRuntimeRuleMap(match.getHomeTeam().getId(), homeFormation))
                : new HashMap<>();
        rt.awayTacticalTargets = match.getAwayTeam() != null && match.getAwayTeam().getId() != null
                ? new HashMap<>(teamTacticsService.getRuntimeRuleMap(match.getAwayTeam().getId(), awayFormation))
                : new HashMap<>();
        rt.homeSetPieces = match.getHomeTeam() != null && match.getHomeTeam().getId() != null
                ? teamTacticsService.getRuntimeSetPieces(match.getHomeTeam().getId(), homeFormation)
                : new TacticsSetPieceDTO();
        rt.awaySetPieces = match.getAwayTeam() != null && match.getAwayTeam().getId() != null
                ? teamTacticsService.getRuntimeSetPieces(match.getAwayTeam().getId(), awayFormation)
                : new TacticsSetPieceDTO();

        mapPlayerSlots(rt, match.getHomeLineup() != null ? match.getHomeLineup().getOrderedStartingPlayers() : rt.homePlayers, rt.homeSlots);
        mapPlayerSlots(rt, match.getAwayLineup() != null ? match.getAwayLineup().getOrderedStartingPlayers() : rt.awayPlayers, rt.awaySlots);
    }

    private void mapPlayerSlots(MatchRuntime rt, List<Player> orderedPlayers, List<TacticsSlotDTO> slots) {
        if (orderedPlayers == null || slots == null) {
            return;
        }
        int limit = Math.min(orderedPlayers.size(), slots.size());
        for (int i = 0; i < limit; i++) {
            Player player = orderedPlayers.get(i);
            if (player != null && player.getId() != null && slots.get(i) != null) {
                rt.playerSlotKeys.put(Math.toIntExact(player.getId()), slots.get(i).getSlotKey());
            }
        }
    }

    private void placeTeamInFormation(List<PlayerPositionDTO> targetPositions,
                                      List<Player> orderedPlayers,
                                      String team,
                                      List<TacticsSlotDTO> slots,
                                      double[] fallbackFormation) {
        if (orderedPlayers == null || orderedPlayers.isEmpty()) {
            return;
        }

        if (slots != null && !slots.isEmpty()) {
            int limit = Math.min(orderedPlayers.size(), slots.size());
            for (int i = 0; i < limit; i++) {
                Player player = orderedPlayers.get(i);
                if (player == null || player.getId() == null) {
                    continue;
                }
                double[] anchor = anchorCellToPitch(slots.get(i).getAnchorCellKey(), team);
                targetPositions.add(new PlayerPositionDTO(
                        Math.toIntExact(player.getId()),
                        team,
                        anchor[0] + (random.nextDouble() - 0.5) * 2,
                        anchor[1] + (random.nextDouble() - 0.5) * 2,
                        0, 0
                ));
            }
            return;
        }

        List<Player> sorted = orderedPlayers.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(p -> positionPriority(p.getPosition(), team)))
                .toList();
        int limit = Math.min(sorted.size(), fallbackFormation.length / 2);
        for (int i = 0; i < limit; i++) {
            Player player = sorted.get(i);
            targetPositions.add(new PlayerPositionDTO(
                    Math.toIntExact(player.getId()),
                    team,
                    fallbackFormation[i * 2] + (random.nextDouble() - 0.5) * 2,
                    fallbackFormation[i * 2 + 1] + (random.nextDouble() - 0.5) * 2,
                    0, 0
            ));
        }
    }

    private void reassignTacticalSlot(MatchRuntime rt, Player playerOut, Player playerIn) {
        if (playerOut == null || playerOut.getId() == null || playerIn == null || playerIn.getId() == null) {
            return;
        }
        String slotKey = rt.playerSlotKeys.remove(Math.toIntExact(playerOut.getId()));
        if (slotKey != null) {
            rt.playerSlotKeys.put(Math.toIntExact(playerIn.getId()), slotKey);
        }
    }

    private double[] resolveTacticalTarget(MatchRuntime rt, PlayerPositionDTO pos, Player player, boolean inPossession) {
        String team = pos.getTeam();
        String slotKey = rt.playerSlotKeys.get(pos.getId());
        if (slotKey == null) {
            return null;
        }
        Map<String, String> targetMap = "HOME".equals(team) ? rt.homeTacticalTargets : rt.awayTacticalTargets;
        if (targetMap == null || targetMap.isEmpty()) {
            return null;
        }
        String possessionContext = inPossession ? FormationSlotCatalog.WE_HAVE_BALL : FormationSlotCatalog.OPPONENT_HAS_BALL;
        String ballStateKey = resolveBallStateKey(rt, team);
        String targetCellKey = targetMap.get(slotKey + "|" + ballStateKey + "|" + possessionContext);
        if (targetCellKey == null) {
            return null;
        }

        double[] target = tacticalCellToPitch(targetCellKey, team);
        if (player.getPosition() == Position.GK) {
            double goalX = "HOME".equals(team) ? 8.0 : 92.0;
            double tacticalX = goalX + (target[0] - goalX) * 0.08;
            double trackedX = goalX + (rt.ball.getX() - goalX) * 0.03;
            double tacticalY = 50.0 + (target[1] - 50.0) * 0.08;
            double trackedY = 50.0 + (rt.ball.getY() - 50.0) * 0.07;
            double minX = "HOME".equals(team) ? goalX - 0.5 : goalX - GOALKEEPER_TACTICAL_MAX_X_DRIFT;
            double maxX = "HOME".equals(team) ? goalX + GOALKEEPER_TACTICAL_MAX_X_DRIFT : goalX + 0.5;
            target[0] = clamp((tacticalX * 0.62) + (trackedX * 0.38), minX, maxX);
            target[1] = clamp((tacticalY * 0.54) + (trackedY * 0.46),
                    50.0 - GOALKEEPER_TACTICAL_MAX_Y_DRIFT,
                    50.0 + GOALKEEPER_TACTICAL_MAX_Y_DRIFT);
        }
        return target;
    }

    private boolean isGoalkeeperProtectingGoal(MatchRuntime rt, Player goalkeeper, String defendingTeam) {
        if (goalkeeper == null) {
            return false;
        }

        PlayerPositionDTO goalkeeperPos = getPlayerPosition(rt, goalkeeper);
        if (goalkeeperPos == null) {
            return false;
        }

        double goalX = "HOME".equals(defendingTeam) ? 8.0 : 92.0;
        double xGap = Math.abs(goalkeeperPos.getX() - goalX);
        double yGap = Math.abs(goalkeeperPos.getY() - 50.0);
        return xGap <= GOALKEEPER_SHOT_COVERAGE_X && yGap <= GOALKEEPER_SHOT_COVERAGE_Y;
    }

    private String resolveBallStateKey(MatchRuntime rt, String team) {
        BallPositionDTO ball = rt.ball != null ? rt.ball : new BallPositionDTO(50, 50);
        double canonicalX = "HOME".equals(team) ? ball.getX() : 100.0 - ball.getX();
        double canonicalY = "HOME".equals(team) ? ball.getY() : 100.0 - ball.getY();

        if (canonicalX >= 88.0 && canonicalY <= 18.0) return "ATTACK_LEFT_CORNER";
        if (canonicalX >= 88.0 && canonicalY >= 82.0) return "ATTACK_RIGHT_CORNER";
        if (canonicalX <= 12.0 && canonicalY <= 18.0) return "DEFEND_LEFT_CORNER";
        if (canonicalX <= 12.0 && canonicalY >= 82.0) return "DEFEND_RIGHT_CORNER";

        int progressBand = Math.max(0, Math.min(4, (int) Math.floor(clamp(canonicalX, 0.0, 99.999) / 20.0)));
        int widthBand = Math.max(0, Math.min(4, (int) Math.floor(clamp(canonicalY, 0.0, 99.999) / 20.0)));
        return "CELL_" + progressBand + "_" + widthBand;
    }

    private Map<Integer, double[]> buildRestartTargetMap(MatchRuntime rt) {
        return buildRestartTargetMap(rt, null, "STANDARD");
    }

    private Map<Integer, double[]> buildRestartTargetMap(MatchRuntime rt, String teamInPossession, String restartType) {
        Map<Integer, double[]> targetMap = new HashMap<>();
        populateFormationTargets(targetMap, rt.homePlayers, "HOME", rt.homeSlots, getHomeFormationPositions());
        populateFormationTargets(targetMap, rt.awayPlayers, "AWAY", rt.awaySlots, getAwayFormationPositions());
        anchorGoalkeeperRestartPosition(targetMap, rt.homePlayers, "HOME");
        anchorGoalkeeperRestartPosition(targetMap, rt.awayPlayers, "AWAY");
        if ("KICKOFF".equals(restartType) && teamInPossession != null) {
            applyKickoffSpacing(targetMap, rt, teamInPossession);
        }
        if ("GOAL_KICK".equals(restartType) && teamInPossession != null) {
            applyGoalKickSpacing(targetMap, rt, teamInPossession);
        }
        return targetMap;
    }

    private void anchorGoalkeeperRestartPosition(Map<Integer, double[]> targetMap, List<Player> players, String team) {
        if (players == null) {
            return;
        }
        players.stream()
                .filter(player -> player != null && player.getPosition() == Position.GK && player.getId() != null)
                .findFirst()
                .ifPresent(goalkeeper -> targetMap.put(
                        Math.toIntExact(goalkeeper.getId()),
                        new double[]{"HOME".equals(team) ? 8.5 : 91.5, 50.0}
                ));
    }

    private void applyKickoffSpacing(Map<Integer, double[]> targetMap, MatchRuntime rt, String restartTeam) {
        List<Player> inPossession = "HOME".equals(restartTeam) ? rt.homePlayers : rt.awayPlayers;
        List<Player> kickoffCandidates = inPossession.stream()
                .filter(player -> player != null && player.getId() != null && player.getPosition() != Position.GK)
                .sorted(Comparator.comparingInt(player -> switch (player.getPosition()) {
                    case MID -> 0;
                    case ATT -> 1;
                    case WNG -> 2;
                    case DEF -> 3;
                    default -> 4;
                }))
                .toList();

        if (!kickoffCandidates.isEmpty()) {
            targetMap.put(Math.toIntExact(kickoffCandidates.getFirst().getId()), new double[]{50.0, 50.0});
        }
        if (kickoffCandidates.size() > 1) {
            double supportX = "HOME".equals(restartTeam) ? 47.0 : 53.0;
            targetMap.put(Math.toIntExact(kickoffCandidates.get(1).getId()), new double[]{supportX, 50.0});
        }
    }

    private void applyGoalKickSpacing(Map<Integer, double[]> targetMap, MatchRuntime rt, String restartTeam) {
        List<Player> inPossession = "HOME".equals(restartTeam) ? rt.homePlayers : rt.awayPlayers;
        List<Player> opponents = "HOME".equals(restartTeam) ? rt.awayPlayers : rt.homePlayers;

        double defenderLaneX = "HOME".equals(restartTeam) ? 16.0 : 84.0;
        double midfielderLaneX = "HOME".equals(restartTeam) ? 22.0 : 78.0;

        List<Player> defenders = inPossession.stream()
                .filter(player -> player != null && player.getId() != null && player.getPosition() == Position.DEF)
                .sorted(Comparator.comparingDouble(player -> {
                    PlayerPositionDTO pos = getPlayerPosition(rt, player);
                    return pos != null ? pos.getY() : 50.0;
                }))
                .toList();
        for (int i = 0; i < Math.min(2, defenders.size()); i++) {
            targetMap.put(Math.toIntExact(defenders.get(i).getId()), new double[]{defenderLaneX, i == 0 ? 36.0 : 64.0});
        }

        List<Player> midfielders = inPossession.stream()
                .filter(player -> player != null && player.getId() != null && player.getPosition() == Position.MID)
                .sorted(Comparator.comparingDouble(player -> {
                    PlayerPositionDTO pos = getPlayerPosition(rt, player);
                    return pos != null ? pos.getY() : 50.0;
                }))
                .toList();
        for (int i = 0; i < Math.min(2, midfielders.size()); i++) {
            targetMap.put(Math.toIntExact(midfielders.get(i).getId()), new double[]{midfielderLaneX, i == 0 ? 42.0 : 58.0});
        }

        for (Player player : opponents) {
            if (player == null || player.getId() == null) {
                continue;
            }
            double[] currentTarget = targetMap.getOrDefault(
                    Math.toIntExact(player.getId()),
                    positionToArray(getPlayerPosition(rt, player))
            );
            if (currentTarget == null) {
                continue;
            }
            if (!isInsidePenaltyArea(currentTarget[0], currentTarget[1], restartTeam)) {
                PlayerPositionDTO currentPos = getPlayerPosition(rt, player);
                if (currentPos == null || !isInsidePenaltyArea(currentPos.getX(), currentPos.getY(), restartTeam)) {
                    continue;
                }
            }

            double exitX = "HOME".equals(restartTeam) ? GOAL_KICK_EXIT_X : 100.0 - GOAL_KICK_EXIT_X;
            double laneOffset = ((Math.toIntExact(player.getId()) % 5) - 2) * 3.5;
            double targetY = clamp(currentTarget[1] + laneOffset, MIN_Y + 1.0, MAX_Y - 1.0);
            targetMap.put(Math.toIntExact(player.getId()), new double[]{exitX, targetY});
        }
    }

    private void beginBallTransit(MatchRuntime rt,
                                  double startX,
                                  double startY,
                                  double targetX,
                                  double targetY,
                                  Integer pendingReceiverId,
                                  Integer pendingPasserId,
                                  String pendingPassTeam,
                                  String lastTouchTeam,
                                  String transitMode,
                                  boolean canBeIntercepted) {
        double distance = Math.hypot(targetX - startX, targetY - startY);
        rt.currentCarrier = null;
        rt.ballInTransit = true;
        rt.ballTransitCanBeIntercepted = canBeIntercepted;
        rt.ballTransitStartX = startX;
        rt.ballTransitStartY = startY;
        rt.ballTransitTargetX = targetX;
        rt.ballTransitTargetY = targetY;
        rt.ballTransitTicks = 0;
        rt.ballTransitMaxTicks = Math.max(2, Math.min(canBeIntercepted ? 6 : 5, (int) Math.round(distance / (canBeIntercepted ? 6.2 : 5.0))));
        rt.pendingReceiverId = pendingReceiverId;
        rt.pendingPasserId = pendingPasserId;
        rt.pendingPassTeam = pendingPassTeam;
        rt.lastTouchTeam = lastTouchTeam;
        rt.ballTransitMode = transitMode;
        rt.ball = new BallPositionDTO(startX, startY);
    }

    private String classifyPassTransitMode(PlayerPositionDTO passerPos, PlayerPositionDTO receiverPos, String team) {
        double distance = distanceBetween(passerPos, receiverPos);
        double progress = "HOME".equals(team)
                ? receiverPos.getX() - passerPos.getX()
                : passerPos.getX() - receiverPos.getX();
        boolean wideDelivery = Math.abs(passerPos.getY() - 50.0) >= 24.0
                && Math.abs(receiverPos.getY() - 50.0) <= 18.0
                && progress >= 8.0;
        if (wideDelivery && distance >= 15.0) {
            return "CROSS";
        }
        if (distance >= 18.0) {
            return "LOFTED_PASS";
        }
        return "GROUND_PASS";
    }

    private double boundTransitTarget(double value, double min, double max, String transitMode) {
        double buffer = switch (transitMode) {
            case "CROSS" -> 6.5;
            case "LOFTED_PASS" -> 3.5;
            case "GROUND_PASS" -> 1.6;
            case "DEFLECTION" -> 7.5;
            default -> 0.0;
        };
        return clamp(value, min - buffer, max + buffer);
    }

    private boolean shouldTriggerCarrierOutOfBounds(MatchRuntime rt, Player ballCarrier, String ballTeam) {
        PlayerPositionDTO carrierPos = getPlayerPosition(rt, ballCarrier);
        if (carrierPos == null) {
            return false;
        }
        boolean nearBoundary = carrierPos.getX() <= MIN_X + 4.0
                || carrierPos.getX() >= MAX_X - 4.0
                || carrierPos.getY() <= MIN_Y + 4.0
                || carrierPos.getY() >= MAX_Y - 4.0;
        if (!nearBoundary) {
            return false;
        }
        double pressureBoost = Math.min(0.18, getNearbyDefenders(rt, ballCarrier, ballTeam).size() * 0.055);
        return random.nextDouble() < 0.09 + pressureBoost;
    }

    private double resolveMovementSkillFactor(Player player) {
        if (player == null || player.getSkills() == null) {
            return 1.0;
        }
        double pace = skillExact(player, SkillName.PACE);
        double stamina = skillExact(player, SkillName.STAMINA);
        double fatigue = Math.max(0.0, player.getCurrentFatigue());
        double paceFactor = 0.82 + (pace / 20.0) * 0.36;
        double staminaFactor = 0.88 + (stamina / 20.0) * 0.18;
        double fatiguePenalty = Math.max(0.72, 1.0 - Math.max(0.0, fatigue - 5.5) * 0.045);
        return clamp(paceFactor * staminaFactor * fatiguePenalty, 0.72, 1.32);
    }

    private double resolveActionMovementStep(Player player, double baseStep) {
        return baseStep * resolveMovementSkillFactor(player);
    }

    private double resolveBallProgressionStep(Player player, double baseStep) {
        if (player == null || player.getSkills() == null) {
            return baseStep;
        }
        double pace = skillExact(player, SkillName.PACE);
        double technique = skillExact(player, SkillName.TECHNIQUE);
        double dribbleFactor = 0.90 + (pace / 20.0) * 0.18 + (technique / 20.0) * 0.12;
        return baseStep * clamp(dribbleFactor, 0.90, 1.22);
    }

    private double resolvePassScatter(Player passer, Player receiver) {
        if (passer == null || passer.getSkills() == null) {
            return 1.3;
        }
        double passing = skillExact(passer, SkillName.PASSING);
        double technique = skillExact(passer, SkillName.TECHNIQUE);
        double playmaker = skillExact(passer, SkillName.PLAYMAKER);
        double receiverControl = receiver != null ? (skillExact(receiver, SkillName.TECHNIQUE) + skillExact(receiver, SkillName.PACE)) / 2.0 : 10.0;
        double accuracy = (passing * 0.50) + (technique * 0.25) + (playmaker * 0.25);
        double controlBonus = receiverControl / 20.0;
        double scatter = 1.85 - (accuracy / 20.0) * 0.95 - controlBonus * 0.22;
        return clamp(scatter, 0.55, 1.65);
    }

    private double resolvePassScatterMultiplier(Player passer, String transitMode) {
        double passing = skillExact(passer, SkillName.PASSING);
        double technique = skillExact(passer, SkillName.TECHNIQUE);
        double quality = ((passing * 0.65) + (technique * 0.35)) / 20.0;
        double base = switch (transitMode) {
            case "CROSS" -> 1.10;
            case "LOFTED_PASS" -> 1.02;
            default -> 0.96;
        };
        return clamp(base - quality * 0.28, 0.62, 1.12);
    }

    private void tuneBallTransitForPasser(MatchRuntime rt,
                                          Player passer,
                                          PlayerPositionDTO passerPos,
                                          PlayerPositionDTO receiverPos,
                                          String transitMode) {
        if (passer == null || passerPos == null || receiverPos == null) {
            return;
        }
        double distance = distanceBetween(passerPos, receiverPos);
        double passing = skillExact(passer, SkillName.PASSING);
        double technique = skillExact(passer, SkillName.TECHNIQUE);
        double pace = skillExact(passer, SkillName.PACE);
        double quality = (passing * 0.55 + technique * 0.30 + pace * 0.15) / 20.0;
        double speedFactor = switch (transitMode) {
            case "GROUND_PASS" -> 1.20;
            case "LOFTED_PASS" -> 1.00;
            case "CROSS" -> 0.92;
            default -> 1.0;
        };
        int tunedTicks = (int) Math.round(distance / (5.2 + quality * 1.8 * speedFactor));
        rt.ballTransitMaxTicks = Math.max(2, Math.min("CROSS".equals(transitMode) ? 6 : 5, tunedTicks));
    }

    private double resolveDribbleEventChance(Player dribbler) {
        double technique = skillExact(dribbler, SkillName.TECHNIQUE);
        double pace = skillExact(dribbler, SkillName.PACE);
        return clamp(0.16 + (technique / 20.0) * 0.10 + (pace / 20.0) * 0.06, 0.18, 0.36);
    }

    private double resolveChanceCreationChance(Player dribbler) {
        double technique = skillExact(dribbler, SkillName.TECHNIQUE);
        double striker = skillExact(dribbler, SkillName.STRIKER);
        return clamp(0.10 + (technique / 20.0) * 0.08 + (striker / 20.0) * 0.04, 0.10, 0.24);
    }

    private boolean isThreatRelevantToMovementRoute(PlayerPositionDTO pos, double[] tacticalTarget, PlayerPositionDTO threatPos) {
        int[] targetBands = resolveSpatialBands(tacticalTarget[0], tacticalTarget[1]);
        int[] threatBands = resolveSpatialBands(threatPos.getX(), threatPos.getY());
        if (zoneDistance(targetBands, threatBands) <= 1) {
            return true;
        }
        return isPointNearMovementRoute(pos, tacticalTarget, threatPos.getX(), threatPos.getY(), 7.5, 20.0);
    }

    private boolean isPointNearMovementRoute(PlayerPositionDTO pos,
                                             double[] tacticalTarget,
                                             double pointX,
                                             double pointY,
                                             double corridorRadius,
                                             double maxRouteDistance) {
        if (pos == null || tacticalTarget == null) {
            return false;
        }
        double routeDistance = Math.hypot(tacticalTarget[0] - pos.getX(), tacticalTarget[1] - pos.getY());
        if (routeDistance > maxRouteDistance) {
            return false;
        }
        double distanceToSegment = distancePointToSegment(pointX, pointY, pos.getX(), pos.getY(), tacticalTarget[0], tacticalTarget[1]);
        return distanceToSegment <= corridorRadius;
    }

    private double distancePointToSegment(double pointX,
                                          double pointY,
                                          double startX,
                                          double startY,
                                          double endX,
                                          double endY) {
        double dx = endX - startX;
        double dy = endY - startY;
        if (Math.abs(dx) < 0.001 && Math.abs(dy) < 0.001) {
            return Math.hypot(pointX - startX, pointY - startY);
        }
        double projection = ((pointX - startX) * dx + (pointY - startY) * dy) / ((dx * dx) + (dy * dy));
        double t = clamp(projection, 0.0, 1.0);
        double closestX = startX + dx * t;
        double closestY = startY + dy * t;
        return Math.hypot(pointX - closestX, pointY - closestY);
    }

    private double skillExact(Player player, SkillName skillName) {
        if (player == null || player.getSkills() == null) {
            return 10.0;
        }
        return player.getSkills().getExact(skillName);
    }

    private boolean shouldTriggerShotDeflection(MatchRuntime rt, Player shooter, Player blocker, boolean openGoal) {
        PlayerPositionDTO shooterPos = getPlayerPosition(rt, shooter);
        PlayerPositionDTO blockerPos = getPlayerPosition(rt, blocker);
        if (shooterPos == null || blockerPos == null) {
            return false;
        }
        double distance = distanceBetween(shooterPos, blockerPos);
        double chance = openGoal ? 0.16 : 0.12;
        if (blocker.getPosition() == Position.DEF) {
            chance += 0.08;
        }
        if (distance <= 3.5) {
            chance += 0.10;
        } else if (distance <= 5.5) {
            chance += 0.05;
        }
        return random.nextDouble() < chance;
    }

    private boolean shouldDeflectTransit(MatchRuntime rt, Player interceptor) {
        double baseChance = switch (rt.ballTransitMode) {
            case "CROSS" -> 0.36;
            case "LOFTED_PASS" -> 0.24;
            case "GROUND_PASS" -> 0.18;
            default -> 0.18;
        };
        if (interceptor.getPosition() == Position.DEF) {
            baseChance += 0.07;
        }
        return random.nextDouble() < baseChance;
    }

    private void startRandomDeflection(MatchRuntime rt,
                                       Player deflector,
                                       double startX,
                                       double startY,
                                       double minTravel,
                                       double maxTravel) {
        double angle = random.nextDouble() * Math.PI * 2.0;
        double travel = minTravel + random.nextDouble() * Math.max(0.1, maxTravel - minTravel);
        double targetX = startX + Math.cos(angle) * travel;
        double targetY = startY + Math.sin(angle) * travel;
        beginBallTransit(
                rt,
                startX,
                startY,
                boundTransitTarget(targetX, MIN_X, MAX_X, "DEFLECTION"),
                boundTransitTarget(targetY, MIN_Y, MAX_Y, "DEFLECTION"),
                null,
                null,
                null,
                getTeam(deflector, rt),
                "DEFLECTION",
                false
        );
    }

    private void startGoalkeeperParryRebound(MatchRuntime rt,
                                             Player goalkeeper,
                                             double startX,
                                             double startY,
                                             String attackingTeam) {
        boolean homeAttack = "HOME".equals(attackingTeam);
        double forwardDirection = homeAttack ? -1.0 : 1.0;
        double lateralDirection = random.nextBoolean() ? 1.0 : -1.0;
        double targetX = startX + forwardDirection * (5.0 + random.nextDouble() * 8.5);
        double targetY = startY + lateralDirection * (5.0 + random.nextDouble() * 14.0);
        beginBallTransit(
                rt,
                startX,
                startY,
                boundTransitTarget(targetX, MIN_X, MAX_X, "DEFLECTION"),
                boundTransitTarget(targetY, MIN_Y, MAX_Y, "DEFLECTION"),
                null,
                goalkeeper != null && goalkeeper.getId() != null ? Math.toIntExact(goalkeeper.getId()) : null,
                getTeam(goalkeeper, rt),
                getTeam(goalkeeper, rt),
                "DEFLECTION",
                false
        );
    }

    private boolean isOutsidePlayableBounds(double x, double y) {
        return x < MIN_X || x > MAX_X || y < MIN_Y || y > MAX_Y;
    }

    private boolean isInsidePenaltyArea(double x, double y, String defendingTeam) {
        if (y < PENALTY_AREA_MIN_Y || y > PENALTY_AREA_MAX_Y) {
            return false;
        }
        return "HOME".equals(defendingTeam) ? x <= 18.0 : x >= 82.0;
    }

    private double[] positionToArray(PlayerPositionDTO position) {
        if (position == null) {
            return null;
        }
        return new double[]{position.getX(), position.getY()};
    }

    private void populateFormationTargets(Map<Integer, double[]> targetMap,
                                          List<Player> orderedPlayers,
                                          String team,
                                          List<TacticsSlotDTO> slots,
                                          double[] fallbackFormation) {
        if (orderedPlayers == null || orderedPlayers.isEmpty()) {
            return;
        }

        if (slots != null && !slots.isEmpty()) {
            int limit = Math.min(orderedPlayers.size(), slots.size());
            for (int i = 0; i < limit; i++) {
                Player player = orderedPlayers.get(i);
                if (player != null && player.getId() != null) {
                    targetMap.put(Math.toIntExact(player.getId()), anchorCellToPitch(slots.get(i).getAnchorCellKey(), team));
                }
            }
            return;
        }

        List<Player> sorted = orderedPlayers.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(p -> positionPriority(p.getPosition(), team)))
                .toList();
        int limit = Math.min(sorted.size(), fallbackFormation.length / 2);
        for (int i = 0; i < limit; i++) {
            targetMap.put(Math.toIntExact(sorted.get(i).getId()), new double[]{fallbackFormation[i * 2], fallbackFormation[i * 2 + 1]});
        }
    }

    private double[] anchorCellToPitch(String cellKey, String team) {
        return pitchCoordinatesFromCell(cellKey, team, true);
    }

    private double[] tacticalCellToPitch(String cellKey, String team) {
        return pitchCoordinatesFromCell(cellKey, team, false);
    }

    private double[] pitchCoordinatesFromCell(String cellKey, String team, boolean anchorScale) {
        int[] bands = parseCellBands(cellKey);
        int progressBand = bands[0];
        int widthBand = bands[1];
        if (!"HOME".equals(team)) {
            progressBand = 4 - progressBand;
            widthBand = 4 - widthBand;
        }
        double[] xCenters = anchorScale ? ANCHOR_X_CENTERS : TARGET_X_CENTERS;
        return new double[]{xCenters[progressBand], BAND_Y_CENTERS[widthBand]};
    }

    private int[] resolveSpatialBands(double x, double y) {
        if (x >= 88.0 && y <= 18.0) {
            return new int[]{4, 0};
        }
        if (x >= 88.0 && y >= 82.0) {
            return new int[]{4, 4};
        }
        if (x <= 12.0 && y <= 18.0) {
            return new int[]{0, 0};
        }
        if (x <= 12.0 && y >= 82.0) {
            return new int[]{0, 4};
        }

        int progressBand = Math.max(0, Math.min(4, (int) Math.floor(clamp(x, 0.0, 99.999) / 20.0)));
        int widthBand = Math.max(0, Math.min(4, (int) Math.floor(clamp(y, 0.0, 99.999) / 20.0)));
        return new int[]{progressBand, widthBand};
    }

    private int zoneDistance(int[] first, int[] second) {
        return Math.max(Math.abs(first[0] - second[0]), Math.abs(first[1] - second[1]));
    }

    private int[] parseCellBands(String cellKey) {
        if (cellKey == null || !cellKey.startsWith("CELL_")) {
            return new int[]{2, 2};
        }
        String[] parts = cellKey.split("_");
        if (parts.length != 3) {
            return new int[]{2, 2};
        }
        try {
            int progressBand = Math.max(0, Math.min(4, Integer.parseInt(parts[1])));
            int widthBand = Math.max(0, Math.min(4, Integer.parseInt(parts[2])));
            return new int[]{progressBand, widthBand};
        } catch (NumberFormatException ex) {
            return new int[]{2, 2};
        }
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
