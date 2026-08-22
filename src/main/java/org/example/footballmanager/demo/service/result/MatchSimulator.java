package org.example.footballmanager.demo.service.result;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.engine.*;
import org.example.footballmanager.demo.service.model.*;
import org.example.footballmanager.demo.service.recording.MatchRecorder;
import org.example.footballmanager.demo.service.tactics.TacticsRules;

import java.util.*;

/**
 * Match Simulator — orchestrator that runs a full 90-minute match.
 *
 * Takes two teams with lineups, runs the simulation tick-by-tick,
 * and produces a complete MatchResult with all stats, goals, and report.
 */
public class MatchSimulator {

    private static final int TICKS_PER_ROUND = 20;
    private static final int MATCH_MINUTES = 90;
    private static final int TICKS_PER_MINUTE = 40;
    private static final int CARRY_STUCK_MAX_TICKS = 20;

    private final long seed;
    private final SimulationRandom random;
    private boolean verbose = false;
    private Map<String, Integer> passExchangeCount = new HashMap<>();

    // VAR review service. Created per-match inside simulate() and referenced by
    // the private helper methods (executeDecision, handleShotArrival, …) for
    // offside / goal / card reviews.
    private VARService varService;

    public MatchSimulator(long seed) {
        this.seed = seed;
        this.random = new SimulationRandom(seed);
    }

    public MatchSimulator() {
        this(System.nanoTime());
    }

    public void setVerbose(boolean verbose) { this.verbose = verbose; }
    public void setDiagnosticCutoffTicks(int ticks) { this.diagnosticCutoffTicks = ticks; }
    private int diagnosticCutoffTicks = Integer.MAX_VALUE;

    /**
     * Run a full match between two teams.
     *
     * @param homePlayers home team starting XI (11 players)
     * @param awayPlayers away team starting XI (11 players)
     * @param homeTeamName home team display name
     * @param awayTeamName away team display name
     * @return complete match result
     */
    public MatchResult simulate(List<Player> homePlayers, List<Player> awayPlayers,
                                 String homeTeamName, String awayTeamName) {
        if (homePlayers.size() != 11 || awayPlayers.size() != 11) {
            throw new IllegalArgumentException("Each team must have exactly 11 players");
        }

        List<Player> allPlayers = new ArrayList<>();
        allPlayers.addAll(homePlayers);
        allPlayers.addAll(awayPlayers);

        Position kickoffPos = new Position(4, 3.5);
        Ball ball = new Ball(kickoffPos, kickoffPos);
        TacticsRules tactics = new TacticsRules();
        MatchRecorder recorder = new MatchRecorder();
        MatchState state = new MatchState(allPlayers, ball, tactics, random.getRandom(), recorder);

        MatchStatsCollector stats = new MatchStatsCollector(homeTeamName, awayTeamName);
        stats.registerPlayers(allPlayers);

        // Wire up engines
        PlayerSelectionEngine selection = new PlayerSelectionEngine(state);
        ExecutionQuality executionQuality = new ExecutionQuality(random.getRandom());
        ActionEngine actionEngine = new ActionEngine(state, selection, executionQuality, recorder);
        DuelEngine duelEngine = new DuelEngine(state, new DuelResolver(random.getRandom()), recorder);
        MovementEngine movementEngine = new MovementEngine(state);
        BallMovementEngine ballMovementEngine = new BallMovementEngine(state);
        TacticalIntentEngine tacticalEngine = new TacticalIntentEngine(state);
        ThreatAssessmentService threatService = new ThreatAssessmentService(state);
        PlayerPerceptionService perceptionService = new PlayerPerceptionService(state);
        FootballRulesService rulesService = new FootballRulesService(state);
        ActionLogService logger = new ActionLogService();
        TransitionService transitionService = new TransitionService(state, recorder);
        FatigueService fatigueService = new FatigueService(state);
        this.varService = new VARService(state, random.getRandom(), recorder);

        PlaymakingDecisionEngine decisionEngine = new PlaymakingDecisionEngine(state, selection,
                threatService, perceptionService, random.getRandom());

        logger.logInfo(state, "MATCH START: " + homeTeamName + " vs " + awayTeamName + " | seed=" + seed, "MATCH");

        // Start match
        state.startMatchSimulation();
        state.setPhase(MatchPhase.KICK_OFF);
        state.setKickoffTeam("HOME");
        state.setKickoffPending(true);

        int totalTicks = 0;
        int maxTicks = MATCH_MINUTES * TICKS_PER_MINUTE + 3 * TICKS_PER_MINUTE;
        int tickHalfTime = 0, tickKickoff = 0, tickCelebration = 0, tickDelay = 0,
                tickCornerHold = 0, tickPassFlight = 0, tickShotFlight = 0,
                tickChase = 0, tickDecision = 0, tickFlightNoAction = 0, tickLoose = 0,
                tickCarryStuck = 0;

        while (totalTicks < maxTicks && !state.isMatchFinished()) {
            // Handle half-time → start second half
            if (state.isHalfTime()) {
                tickHalfTime++;
                state.startSecondHalf();
                totalTicks++;
                state.advanceMatchClock();
                state.advanceSimulationTick();
                continue;
            }

            // Handle kickoff
            if (state.isKickoffPending()) {
                tickKickoff++;
                handleKickoff(state, actionEngine, selection, homePlayers, awayPlayers, stats, logger);
                totalTicks++;
                state.advanceMatchClock();
                state.advanceSimulationTick();
                continue;
            }

            // Handle celebration
            if (state.isCelebrating()) {
                tickCelebration++;
                recorder.captureSnapshot(state);
                state.setCelebrating(false);
                state.setKickoffPending(true);
                totalTicks++;
                state.advanceMatchClock();
                state.advanceSimulationTick();
                continue;
            }

            // Handle action delay
            if (state.getActionDelayTicks() > 0) {
                tickDelay++;
                state.consumeActionDelayTick();
                ballMovementEngine.moveBallTowardCurrentTarget();
                totalTicks++;
                state.advanceMatchClock();
                state.advanceSimulationTick();
                continue;
            }

            // Handle corner hold
            if (state.getCornerHoldTicks() > 0) {
                tickCornerHold++;
                state.consumeCornerHoldTick();
                totalTicks++;
                state.advanceMatchClock();
                state.advanceSimulationTick();
                continue;
            }

            // No active action — need new decision
            if (!state.hasActiveAction()) {
                if (state.getCarrier() != null) {
                    // Ball carrier makes a decision
                    state.beginRound();
                    tacticalEngine.assignTargets();
                    DecisionOption chosen = decisionEngine.decide();
                    DecisionType decision = chosen.getType();

                    logger.logDecision(state, chosen, decisionEngine.getLastScoredOptions(),
                            "DECISION [" + decisionEngine.getLastSelectionReason() + "]");
                    recordActionStats(state, logger, decision, state.getCarrier());

                    if (verbose && totalTicks < diagnosticCutoffTicks) {
                        printDiagnosticDecision(state, chosen, decisionEngine, rulesService);
                    }

                    executeDecision(decision, chosen, state, actionEngine, selection,
                            decisionEngine, stats,
                            logger, rulesService);

                    // Track consecutive carries: increment on CARRY, reset on anything else
                    Player decisionMaker = state.getCarrier();
                    if (decisionMaker != null) {
                        if (decision == DecisionType.CARRY) {
                            decisionMaker.incrementConsecutiveCarries();
                        } else {
                            decisionMaker.resetConsecutiveCarries();
                        }
                    }

                    // Clear the kickoff-action flag after the decision has been processed.
                    state.setKickoffActionPending(false);
                    state.setSetPiecePending(false);

                    // Check for duel (skip for THRU passes — interception handled
                    // at arrival via findPassInterceptor + THRU wait mechanism)
                    boolean isThruPass = state.hasActiveAction()
                            && state.getAction().getPassLength() == PassLength.THRU
                            && state.getAction().isPassInFlight();
                    if (!isThruPass) {
                        duelEngine.update(state.getAction());
                        if (duelEngine.getActiveDuelAttacker() != null) {
                            Player duelAttacker = duelEngine.getActiveDuelAttacker();
                            Player duelDefender = duelEngine.getActiveDuelDefender();
                            DuelType duelType = duelEngine.getActiveDuelType();
                            DuelResolver.DuelResult duelResult =
                                    duelEngine.resolveActiveDuel(state.getAction());
                            duelEngine.closeAfterResolution();
                            recordDuelStats(state, actionEngine, rulesService, stats, logger, selection,
                                    duelResult, duelAttacker, duelDefender, duelType,
                                    state.getAction());
                        }
                    }

                } else if (ball.getTarget() != null) {
                    // Ball in flight — advance it
                    ballMovementEngine.moveBallTowardCurrentTarget();

                    // Skip duel checks during THRU pass flight: the runner is
                    // running onto the ball, and the interception check at
                    // arrival (in handleInFlightArrival) handles any defender
                    // who reaches the landing zone first. corePrinciples §8:
                    // the DuelEngine detects player-vs-player contests, not
                    // air-time interceptions of a running receiver.
                    boolean isThruInFlight = state.hasActiveAction()
                            && state.getAction().getPassLength() == PassLength.THRU
                            && state.getAction().isPassInFlight();
                    if (!isThruInFlight) {
                        // Check for duel during flight
                        duelEngine.update(state.getAction());
                        if (duelEngine.getActiveDuelAttacker() != null) {
                            Player duelAttacker = duelEngine.getActiveDuelAttacker();
                            Player duelDefender = duelEngine.getActiveDuelDefender();
                            DuelType duelType = duelEngine.getActiveDuelType();
                            DuelResolver.DuelResult duelResult =
                                    duelEngine.resolveActiveDuel(state.getAction());
                            duelEngine.closeAfterResolution();
                            recordDuelStats(state, actionEngine, rulesService, stats, logger, selection,
                                    duelResult, duelAttacker, duelDefender, duelType,
                                    state.getAction());
                        }
                    }
                } else {
                    // Loose ball — find chasers
                    Player closestHome = selection.closestHomeTo(ball.getPosition());
                    Player closestAway = selection.closestTeamTo(ball.getPosition(), "AWAY");
                    state.setActiveChasers(closestHome, closestAway);
                    if (closestHome != null) closestHome.setTarget(ball.getPosition());
                    if (closestAway != null) closestAway.setTarget(ball.getPosition());
                    actionEngine.start(ActionType.CHASE, "CHASE: loose ball");
                    state.setActionDelayTicks(0);
                    logger.logInfo(state, "Loose ball — chasers: " +
                            (closestHome != null ? closestHome.getLabel() : "none") +
                            " vs " + (closestAway != null ? closestAway.getLabel() : "none"), "LOOSE_BALL");
                }
            }

            // Refresh chase targets toward moving ball
            if (state.hasActiveAction() && state.getAction().getType() == ActionType.CHASE) {
                for (Player chaser : state.getActiveChasers()) {
                    chaser.setTarget(ball.getPosition());
                }
            }

            // Chase timeout / no-progress guards
            if (state.hasActiveAction() && state.getAction().getType() == ActionType.CHASE) {
                Action chaseAction = state.getAction();
                Position ballPos = state.getBall().getPosition();
                Player leadChaser = chaseAction.getActingPlayer();
                double leadDistance = leadChaser == null ? Double.MAX_VALUE
                        : SimUtils.distance(leadChaser.getPosition(), ballPos);
                chaseAction.recordChaseTick(leadDistance, ActionEngine.CHASE_PROGRESS_EPSILON);

                if (chaseAction.getChaseTicks() >= ActionEngine.CHASE_MAX_TICKS) {
                    actionEngine.resolveChaseTimeout();
                    totalTicks++;
                    state.advanceMatchClock();
                    state.advanceSimulationTick();
                    continue;
                }
                if (chaseAction.getChaseNoProgressTicks() >= ActionEngine.CHASE_NO_PROGRESS_TICKS) {
                    actionEngine.resolveChaseNoProgress();
                    totalTicks++;
                    state.advanceMatchClock();
                    state.advanceSimulationTick();
                    continue;
                }
            }

            // Possession tracking — tick for current carrier's team
            if (state.getCarrier() != null) {
                stats.addPossessionTick(state.getCarrier().getTeam());
            }

            // Check action completion (CHASE routes through duel resolution first)
            if (state.hasActiveAction() && state.getAction().getType() == ActionType.CHASE) {
                resolveChase(state, actionEngine, duelEngine, selection,
                        rulesService, stats, logger);
            } else {
                actionEngine.checkActionCompletion();
            }

            // Handle pass in flight arrival
            if (state.hasActiveAction() && state.getAction().isInFlight()) {
                handleInFlightArrival(state, actionEngine, stats,
                        logger, rulesService);
            }

            // Handle shot arrival
            if (state.hasActiveAction() && state.getAction().isShotInFlight()) {
                handleShotArrival(state, actionEngine, stats,
                        logger, rulesService);
            } else if (state.hasActiveAction() && state.getAction().isPassInFlight()) {
                // Debug: log pass arrival to see if this is being called
                // System.err.println("DEBUG: Pass in flight detected, type=" + state.getAction().getType());
            }

            // CARRY stuck guard — force-complete if carrier can't reach target
            if (state.hasActiveAction() && state.getAction().getType() == ActionType.CARRY) {
                Player carrier = state.getCarrier();
                if (carrier != null && carrier.getTarget() != null) {
                    double distToTarget = SimUtils.distance(carrier.getPosition(), carrier.getTarget());
                    if (distToTarget >= MovementEngine.PLAYER_SPEED * 2) {
                        tickCarryStuck++;
                        if (tickCarryStuck >= CARRY_STUCK_MAX_TICKS) {
                            carrier.setTarget(null);
                            actionEngine.complete("CARRY: stuck timeout — forcing pass/shot");
                            tickCarryStuck = 0;
                        }
                    } else {
                        tickCarryStuck = 0;
                    }
                }
            } else {
                tickCarryStuck = 0;
            }

            // Ball follows carrier during in-possession play (not during PASS/SHOT/CROSS flight)
            boolean ballShouldFollow = state.getCarrier() != null
                    && (!state.hasActiveAction()
                        || state.getAction().getType() == ActionType.CARRY);
            if (ballShouldFollow) {
                ballMovementEngine.followCarrier();
            }

            // Movement
            movementEngine.moveAllTowardTargets();

            // Ball follows carrier after movement (catch-up)
            if (ballShouldFollow) {
                ballMovementEngine.followCarrier();
            }

            // Fatigue
            fatigueService.updateAll();

            // Injury check — fatigue may cause injury → auto-substitute
            checkInjuries(state, selection, actionEngine, stats, logger);

            // Transition
            transitionService.checkTransition();
            transitionService.updatePhase();

            // Duel cooldown
            state.consumeDuelCooldownTick();

            // Capture snapshot for replay
            recorder.captureSnapshot(state);

            totalTicks++;
            state.advanceMatchClock();
            state.advanceSimulationTick();
        }

        // Build result
        int matchMinutes = Math.min(MATCH_MINUTES, totalTicks / TICKS_PER_MINUTE);

        TeamMatchStats homeTeamStats = stats.buildTeamStats(homeTeamName, totalTicks, matchMinutes);
        TeamMatchStats awayTeamStats = stats.buildTeamStats(awayTeamName, totalTicks, matchMinutes);

        List<PlayerMatchStats> homePlayerStatsList = stats.buildPlayerStats(homeTeamName, matchMinutes);
        List<PlayerMatchStats> awayPlayerStatsList = stats.buildPlayerStats(awayTeamName, matchMinutes);

        String finalScore = state.getGoalCount() + " - " + state.getAwayGoalCount();

        // Find man of the match
        String motm = findMotM(homePlayerStatsList, awayPlayerStatsList);

        // Build report
        MatchReport report = buildReport(homeTeamName, awayTeamName,
                state.getGoalCount(), state.getAwayGoalCount(),
                homeTeamStats, awayTeamStats, stats.getGoals(), motm);

        // Build lineups
        List<MatchResult.LineupPlayer> homeLineup = new ArrayList<>();
        List<MatchResult.LineupPlayer> awayLineup = new ArrayList<>();
        for (int i = 0; i < homePlayers.size(); i++) {
            homeLineup.add(MatchResult.LineupPlayer.from(homePlayers.get(i), i + 1));
        }
        for (int i = 0; i < awayPlayers.size(); i++) {
            awayLineup.add(MatchResult.LineupPlayer.from(awayPlayers.get(i), i + 1));
        }

        logger.logInfo(state, "=== MATCH FINISHED === (" + totalTicks + " ticks, "
                + state.getActionCount() + " actions, matchTicks=" + state.getMatchTicks()
                + " halfTime=" + state.isHalfTime() + " finished=" + state.isMatchFinished()
                + " hk=" + tickKickoff + " hc=" + tickCelebration + " hd=" + tickDelay
                + " hch=" + tickCornerHold + " hht=" + tickHalfTime + " hp=" + tickPassFlight
                + " hs=" + tickShotFlight + " hchase=" + tickChase + " hdec=" + tickDecision
                + " hfl=" + tickFlightNoAction + " hloose=" + tickLoose + ")", "MATCH");
        logger.printSummary();

        String matchId = "match-" + seed;

        return new MatchResult(
                homeTeamName, awayTeamName,
                state.getGoalCount(), state.getAwayGoalCount(), finalScore,
                "4-4-2",
                homeLineup, awayLineup,
                homeTeamStats, awayTeamStats,
                homePlayerStatsList, awayPlayerStatsList,
                stats.getGoals(), report, seed, logger.getAllLogs(),
                matchId, recorder.getEvents(), recorder.getSnapshots()
        );
    }

    // --- Private helpers ---

    private void handleKickoff(MatchState state, ActionEngine actionEngine,
                                PlayerSelectionEngine selection,
                                List<Player> homePlayers, List<Player> awayPlayers,
                                MatchStatsCollector stats, ActionLogService logger) {
        String kickoffTeam = state.getKickoffTeam();
        List<Player> teamPlayers = "HOME".equals(kickoffTeam) ? homePlayers : awayPlayers;

        // Prefer an attacker for the kickoff (mirrors swingUIDemo: finds ST/ATT first),
        // falling back to any midfielder or attacker, then any player.
        Player kicker = teamPlayers.stream()
                .filter(p -> p.isAttacker())
                .findFirst()
                .orElse(teamPlayers.stream()
                        .filter(p -> p.roleLine().equals("MID"))
                        .findFirst()
                        .orElse(teamPlayers.get(0)));

        // Place the ball AND the kicker exactly at the center spot.
        // The carrier's position must match the ball position so that
        // PlaymakingDecisionEngine.buildContext() detects the kickoff
        // (row==4 && col==3.5) — matching the reference implementation.
        Position centerSpot = new Position(4, 3.5);
        kicker.setPosition(centerSpot);
        state.getBall().setPosition(centerSpot);
        state.getBall().setTarget(null);
        state.getBall().setCarrier(kicker);
        state.setCarrier(kicker);
        kicker.setLocked(false);
        kicker.setTarget(null);
        state.setKickoffPending(false);

        // Signal to the decision engine that the NEXT decision is a kickoff:
        //   - generateKickoffPass() will be invoked (only backward PASS + CARRY)
        //   - CLEAR / SHOT / CROSS / THRU / CENTER are suppressed
        //   - Offside check is skipped for the kickoff pass
        state.setKickoffActionPending(true);

        // Increment the round counter so that the round-based kickoff fallback
        // in buildContext() also works (matches swingUIDemo: incrementRound at L122).
        state.incrementRound();

        state.setPhase(MatchPhase.OPEN_PLAY);
        state.setStatus("KICK OFF: " + kicker.getLabel() + " at center (4, 3.5)");
        logger.logRestart(state, "KICK OFF by " + kicker.getLabel()
                + " (" + kickoffTeam + ") at center (4, 3.5)", "KICKOFF");
    }

    private void executeDecision(DecisionType decision, DecisionOption chosen, MatchState state,
                                  ActionEngine actionEngine, PlayerSelectionEngine selection,
                                  PlaymakingDecisionEngine decisionEngine,
                                  MatchStatsCollector stats,
                                  ActionLogService logger, FootballRulesService rulesService) {
        Player carrier = state.getCarrier();
        String team = carrier.getTeam();

        switch (decision) {
            case PASS -> {
                // Use the playmaking-scored receiver from DecisionOption
                Player receiver = chosen.getTarget();
                if (receiver == null || receiver == carrier) {
                    // Fallback: use PM layer's best pass option
                    DecisionOption passFallback = decisionEngine.getBestPassFallback();
                    if (passFallback != null && passFallback.getTarget() != null
                            && passFallback.getTarget() != carrier) {
                        receiver = passFallback.getTarget();
                    } else {
                        receiver = null;
                    }
                }
                if (receiver != null) {
                    // Offside only applies to forward passes during normal play.
                    if (!state.isKickoffActionPending()
                            && SimUtils.distance(receiver.getPosition(), carrier.getPosition()) > 2.0
                            && rulesService.isOffside(receiver, carrier.getPosition(), state.getBall().getPosition())) {
                        // VAR check — close offside calls get reviewed
                        Player[] defenders = state.getPlayers().stream()
                                .filter(p -> !p.getTeam().equals(team) && !"GK".equals(p.getRole()))
                                .toArray(Player[]::new);
                        boolean confirmedOffside = varService.checkOffside(receiver, carrier.getPosition(), defenders);
                        varService.logVARDecision("OFFSIDE", receiver.getLabel());

                        if (confirmedOffside) {
                            stats.onOffside(team);
                            receiver.incrementConsecutiveOffside();
                            logger.logInfo(state, "OFFSIDE (VAR " + varService.getLastVARDecision() + "): "
                                    + receiver.getLabel() + " caught offside on pass from " + carrier.getLabel()
                                    + " (consecutive: " + receiver.getConsecutiveOffsideCount() + ")",
                                    "OFFSIDE", carrier);
                            // Indirect free kick for the defending team — give to GK for a proper reset
                            String defendingTeam = "HOME".equals(team) ? "AWAY" : "HOME";
                            Player freeKickTaker = findKeeper(state, defendingTeam);
                            if (freeKickTaker == null) {
                                freeKickTaker = findNearestNonGoalkeeperTo(state,
                                        receiver.getPosition(), defendingTeam);
                            }
                            if (freeKickTaker != null) {
                                actionEngine.giveBallTo(freeKickTaker,
                                        "offside → indirect free kick for " + defendingTeam);
                                state.setSetPiecePending(true);
                            } else {
                                actionEngine.executeClearance();
                            }
                        } else {
                            // VAR overturned — onside, play continues
                            stats.onPassAttempt(team, carrier.getId());
                            receiver.resetConsecutiveOffside();
                            logger.logInfo(state, "VAR OVERTURNED offside: " + receiver.getLabel()
                                    + " ruled ONSIDE — play continues",
                                    "VAR", receiver);
                            actionEngine.executePassTo(receiver);
                        }
                    } else {
                        stats.onPassAttempt(team, carrier.getId());
                        receiver.resetConsecutiveOffside();
                        actionEngine.executePassTo(receiver);
                        // Record pass exchange for limit tracking
                        decisionEngine.recordPassExchange(carrier.getId(), receiver.getId());
                    }
                } else {
                    actionEngine.executeClearance();
                }
            }
            case THRU -> {
                Player runner = chosen.getTarget();
                if (runner != null && runner != carrier) {
                    Position passOrigin = carrier.getPosition();
                    Position ballPos = state.getBall().getPosition();
                    if (!state.isKickoffActionPending()
                            && rulesService.isOffside(runner, passOrigin, ballPos)) {
                        // VAR check for thru ball offside
                        Player[] defenders = state.getPlayers().stream()
                                .filter(p -> !p.getTeam().equals(team) && !"GK".equals(p.getRole()))
                                .toArray(Player[]::new);
                        boolean confirmedOffside = varService.checkOffside(runner, passOrigin, defenders);
                        varService.logVARDecision("OFFSIDE", runner.getLabel());

                        if (confirmedOffside) {
                            stats.onOffside(team);
                            runner.incrementConsecutiveOffside();
                            logger.logInfo(state, "OFFSIDE (VAR " + varService.getLastVARDecision() + "): "
                                    + runner.getLabel() + " caught offside on thru pass from " + carrier.getLabel()
                                    + " (consecutive: " + runner.getConsecutiveOffsideCount() + ")",
                                    "OFFSIDE", carrier);
                            // Indirect free kick — give to GK for a proper reset
                            String defendingTeam = "HOME".equals(team) ? "AWAY" : "HOME";
                            Player freeKickTaker = findKeeper(state, defendingTeam);
                            if (freeKickTaker == null) {
                                freeKickTaker = findNearestNonGoalkeeperTo(state,
                                        runner.getPosition(), defendingTeam);
                            }
                            if (freeKickTaker != null) {
                                actionEngine.giveBallTo(freeKickTaker,
                                        "offside → indirect free kick for " + defendingTeam);
                                state.setSetPiecePending(true);
                            } else {
                                actionEngine.executeClearance();
                            }
                        } else {
                            stats.onPassAttempt(team, carrier.getId());
                            stats.onThruAttempt(team);
                            runner.resetConsecutiveOffside();
                            logger.logInfo(state, "VAR OVERTURNED offside: " + runner.getLabel()
                                    + " ruled ONSIDE — thru pass continues", "VAR", runner);
                            actionEngine.executeThruPass(runner);
                            decisionEngine.recordPassExchange(carrier.getId(), runner.getId());
                        }
                    } else {
                        stats.onPassAttempt(team, carrier.getId());
                        stats.onThruAttempt(team);
                        runner.resetConsecutiveOffside();
                        actionEngine.executeThruPass(runner);
                        decisionEngine.recordPassExchange(carrier.getId(), runner.getId());
                        logger.logInfo(state, "THRU PASS: " + carrier.getLabel()
                                + " -> " + runner.getLabel() + " (on-side)",
                                "ACTION", carrier);
                    }
                } else {
                    // Fall back to PM layer's best PASS receiver (not random)
                    Player passFallback = decisionEngine.getBestPassFallback() != null
                            ? decisionEngine.getBestPassFallback().getTarget() : null;
                    stats.onPassAttempt(team, carrier.getId());
                    actionEngine.executePass(passFallback);
                }
            }
            case CARRY -> {
                double dir = "HOME".equals(carrier.getTeam()) ? 1 : -1;
                Position dest = new Position(
                        SimUtils.clamp(carrier.getPosition().getRow() + dir * 2, 1, 7),
                        SimUtils.clamp(carrier.getPosition().getColumn() + (state.getRandom().nextDouble() * 2 - 1), 1, 6));
                actionEngine.executeCarry();
            }
            case SHOT -> actionEngine.executeShot();
            case CROSS -> {
                stats.onPassAttempt(team, carrier.getId());
                actionEngine.executeCross();
            }
            case CENTER -> {
                stats.onPassAttempt(team, carrier.getId());
                actionEngine.executeCenter();
            }
            case CLEAR -> actionEngine.executeClearance();
            default -> actionEngine.executeClearance();
        }

        // Log the executed action
        Action executedAction = state.getAction();
        if (executedAction != null) {
            Player target = executedAction.getTargetPlayer();
            logger.logActionExecution(state, executedAction, "STARTED", carrier, target, "ACTION");
        }
    }

    private void handleInFlightArrival(MatchState state, ActionEngine actionEngine,
                                         MatchStatsCollector stats,
                                         ActionLogService logger, FootballRulesService rulesService) {
        Action action = state.getAction();
        if (action == null || !action.isInFlight()) return;

        Ball ball = state.getBall();
        Position target = ball.getTarget();
        if (target == null) return;

        BallMovementEngine.moveBallToward(ball, target, BallMovementEngine.BALL_SPEED);

        if (SimUtils.distance(ball.getPosition(), target) <= BallMovementEngine.PICKUP_DISTANCE) {
            ball.setPosition(target);

            // For THRU passes where the receiver is running onto the ball,
            // keep the ball stationary at the arrival position and let the
            // receiver close the distance over subsequent ticks. The ball
            // speed (2.0 cells/tick) is 8x the runner speed (0.25), so the
            // runner needs extra time to reach the flight target.
            // corePrinciples §8 (Movement): runner moves toward tactical target;
            // the orchestrator coordinates flight + arrival timing.
            if (action.getPassLength() == PassLength.THRU
                    && action.isGoodExecution()
                    && action.getTargetPlayer() != null) {
                Player receiver = action.getTargetPlayer();
                double distToReceiver = SimUtils.distance(receiver.getPosition(), ball.getPosition());
                if (distToReceiver > ExecutionQuality.THRU_SUCCESS_THRESHOLD) {
                    if (state.getThruBallArrivalTick() < 0) {
                        state.setThruBallArrivalTick(state.getSimulationTick());
                        // Mark the receiver as an active chaser so MovementEngine moves
                        // it at full chaser speed (PLAYER_SPEED * 3 = 0.75 cells/tick)
                        // without the per-round pace cap. The ball arrives at the flight
                        // target almost instantly (BALL_SPEED 2.0 vs runner 0.25); the
                        // receiver must run onto the ball in stride — corePrinciples §8
                        // (Movement): runner moves toward tactical target; the
                        // orchestrator coordinates flight + arrival timing.
                        state.addActiveChaser(receiver);
                    }
                    long elapsed = state.getSimulationTick() - state.getThruBallArrivalTick();
                    if (elapsed <= 30) {
                        // Refresh round budget every 10 ticks so non-receiver players
                        // keep their tactical movement during the wait window.
                        if (elapsed > 0 && elapsed % 10 == 0) {
                            state.beginRound();
                        }
                        // Ball hovers at flightTarget; receiver runs onto it
                        return;
                    }
                    // Timeout — remove chaser status and fall through to normal handling
                    state.removeActiveChaser(receiver);
                    state.setThruBallArrivalTick(-1);
                } else {
                    // Receiver arrived within range
                    state.removeActiveChaser(receiver);
                    state.setThruBallArrivalTick(-1);
                }
            }

            ball.setTarget(null);

            // Check ball out-of-bounds
            String lastTouchTeam = action.getActingPlayer() != null
                    ? action.getActingPlayer().getTeam() : "HOME";
            FootballRulesService.RestartType restart =
                    rulesService.determineRestart(ball.getPosition(), lastTouchTeam);
            if (restart != FootballRulesService.RestartType.NONE) {
                if (action.getTargetPlayer() != null) action.getTargetPlayer().setLocked(false);
                if (restart == FootballRulesService.RestartType.CORNER) stats.onCornerFromPass();
                stats.onPassOutOfBounds();
                actionEngine.complete("BALL OUT: " + restart);
                handleBallOutOfBounds(state, stats, logger, restart, ball.getPosition(), lastTouchTeam);
                return;
            }

            if (action.isPassInFlight() || action.isCrossInFlight()) {
                Player receiver = action.getTargetPlayer();

                // Deflection check: ball hits a nearby defender's body/foot.
                // Different from interception — defender doesn't gain possession,
                // ball just changes direction (becomes loose or goes out).
                Player deflector = findPassDeflector(action, ball.getPosition(), state);
                if (deflector != null) {
                    // Deflection — ball becomes loose or goes out of play
                    if (receiver != null) receiver.setLocked(false);
                    stats.onLooseBall();
                    ball.setCarrier(null);
                    ball.setTarget(null);
                    state.setCarrier(null);
                    actionEngine.complete("DEFLECTED by " + deflector.getLabel());
                    logger.logActionOutcome(state, action,
                            "DEFLECTED by " + deflector.getLabel() + " — ball loose",
                            action.getActingPlayer(), deflector, "OUTCOME");
                    // Ball becomes loose — triggers chase recovery
                    return;
                }

                // Interception check: can a nearby defender reach the ball first?
                // (DuelEngine handles player-vs-player; interception of passes is a
                //  positional contest resolved here at the orchestration layer.)
                Player interceptor = findPassInterceptor(action, ball.getPosition(), state);
                if (interceptor != null && (receiver == null
                        || SimUtils.distance(interceptor.getPosition(), ball.getPosition())
                            < SimUtils.distance(receiver.getPosition(), ball.getPosition()))) {
                    // Interception!
                    if (receiver != null) receiver.setLocked(false);
                    stats.onInterception(interceptor.getId());
                    stats.onPassInterception();
                    state.getBall().setCarrier(interceptor);
                    state.setCarrier(interceptor);
                    interceptor.setTarget(null);
                    logger.logActionOutcome(state, action,
                            "INTERCEPTED by " + interceptor.getLabel(),
                            action.getActingPlayer(), interceptor, "OUTCOME");
                    actionEngine.complete("PASS -> INTERCEPTED by " + interceptor.getLabel());
                } else if (receiver != null) {
                    // Delegate pass receipt to ActionEngine (Fix #7) — ActionEngine.pickupPass()
                    // handles the receiver-in-range / receiver-out-of-range cases,
                    // including position snapping and stats.
                    boolean completed = actionEngine.pickupPass();
                    if (completed) {
                        stats.onPassCompleted(action.getActingPlayer().getTeam(),
                                action.getActingPlayer().getId(), receiver.getId());
                        if (action.getPassLength() == PassLength.THRU) {
                            stats.onThruCompleted(action.getActingPlayer().getTeam());
                        }
                        logger.logActionOutcome(state, action,
                                "PASS_RECEIVED by " + receiver.getLabel(),
                                action.getActingPlayer(), receiver, "OUTCOME");
                    } else {
                        stats.onLooseBall();
                        logger.logActionOutcome(state, action,
                                "LOOSE_BALL (no receiver in range)",
                                action.getActingPlayer(), null, "OUTCOME");
                    }
                } else {
                    // No receiver — ball becomes loose.
                    // For clearances (isClearance) the ball is intentionally played
                    // into space, so it is NOT counted as a loose-ball pass failure.
                    // Only true failed forward passes count (corePrinciples §32:
                    // statistics derive from authoritative events).
                    if (!action.isClearance()) {
                        stats.onLooseBall();
                    }
                    actionEngine.passFailed();
                    logger.logActionOutcome(state, action,
                            action.isClearance() ? "CLEARANCE -> LOOSE" : "LOOSE_BALL",
                            action.getActingPlayer(), null, "OUTCOME");
                }
            }
        }
    }

    private void handleShotArrival(MatchState state, ActionEngine actionEngine,
                                    MatchStatsCollector stats,
                                    ActionLogService logger, FootballRulesService rulesService) {
        Action action = state.getAction();
        if (action == null || !action.isShotInFlight()) return;

        Ball ball = state.getBall();
        // Only resolve when the ball actually reaches its intended shot target
        Position shotTarget = action.getActualTarget();
        if (shotTarget == null
                || SimUtils.distance(ball.getPosition(), shotTarget) > BallMovementEngine.PICKUP_DISTANCE) {
            return;
        }

        Player shooter = action.getActingPlayer();
        String shooterTeam = shooter.getTeam();
        Position goal = ActionEngine.goalPositionFor(shooterTeam);
        double distToGoal = SimUtils.distance(shotTarget, goal);

        boolean onTarget = distToGoal < 0.5;
        stats.onShot(shooterTeam, shooter.getId(), onTarget);
        logger.logActionExecution(state, action,
                "SHOT_ARRIVAL distToGoal=" + String.format("%.2f", distToGoal) + " onTarget=" + onTarget,
                shooter, null, "SHOT");

        if (distToGoal < ExecutionQuality.SHOT_GOAL_THRESHOLD) {
            // Check for goalkeeper save
            String keeperTeam = "HOME".equals(shooterTeam) ? "AWAY" : "HOME";
            Player keeper = findKeeper(state, keeperTeam);
            if (keeper != null) {
                double saveChance = keeper.getSkills().keeper() / 20.0 * 0.85;
                // Reduce save chance if keeper is far from ball
                double keeperDist = SimUtils.distance(keeper.getPosition(), ball.getPosition());
                if (keeperDist > 2.0) {
                    saveChance *= Math.max(0.1, 1.0 - (keeperDist - 2.0) * 0.15);
                }
                if (state.getRandom().nextDouble() < saveChance) {
                    actionEngine.shotSaved(keeper);
                    logger.logActionOutcome(state, action,
                            "SAVE by " + keeper.getLabel()
                                    + " (keeper skill=" + String.format("%.0f", keeper.getSkills().keeper()) + ")",
                            keeper, shooter, "OUTCOME");

                    // Handle save rebound immediately (corner or loose ball)
                    if (action.getSaveType() == Action.SaveType.CORNER_REBOUND) {
                        String defendingTeam = "HOME".equals(shooterTeam) ? "AWAY" : "HOME";
                        stats.onCorner(defendingTeam);
                        handleBallOutOfBounds(state, stats, logger, FootballRulesService.RestartType.CORNER,
                                ball.getPosition(), defendingTeam);
                    } else {
                        // Field rebound - ball becomes loose, trigger chase immediately
                        stats.onLooseBall();
                        logger.logActionOutcome(state, action, "SAVE -> FIELD REBOUND (loose ball)",
                                shooter, null, "OUTCOME");
                        // Force ball to be loose so chase triggers in next tick
                        state.getBall().setCarrier(null);
                        state.setCarrier(null);
                    }
                    return;
                }
            }

            // GOAL!
            String assistId = stats.getLastPasserId();
            String assistName = null;
            if (assistId != null && stats.getLastPasserTeam().equals(shooterTeam)) {
                for (Player p : state.getPlayers()) {
                    if (p.getId().equals(assistId)) { assistName = p.getLabel(); break; }
                }
            }

            // VAR check for goal — reviews fouls in buildup, offside, etc.
            boolean goalConfirmed = varService.checkGoal(shooterTeam, goal);
            varService.logVARDecision("GOAL", shooter.getLabel() + " scored");

            if (!goalConfirmed) {
                // VAR overturned the goal
                logger.logInfo(state, "VAR OVERTURNED GOAL: " + shooter.getLabel()
                        + " — goal disallowed (" + varService.getLastVARDecision() + ")",
                        "VAR", shooter);
                actionEngine.shotMissed();
                return;
            }

            stats.onGoal(shooterTeam, shooter.getId(), shooter.getLabel(),
                    assistId, assistName,
                    state.matchMinute(),
                    "HOME".equals(shooterTeam) ? state.getGoalCount() + 1 : state.getGoalCount(),
                    "HOME".equals(shooterTeam) ? state.getAwayGoalCount() : state.getAwayGoalCount() + 1);

            // goalScored() increments score, sets celebrating + kickoffTeam
            actionEngine.goalScored();
            logger.logGoal(state, shooterTeam, shooter.getId(), shooter.getLabel(), assistName, "GOAL");
            logger.logActionOutcome(state, action,
                    "GOAL! " + state.getGoalCount() + "-" + state.getAwayGoalCount(),
                    shooter, null, "OUTCOME");
        } else {
            // Shot missed — but check if a defender deflected it near the goal
            // In real football, blocked shots near the goal often go out for corners
            String defendingTeam = "HOME".equals(shooterTeam) ? "AWAY" : "HOME";
            double distToGoalForCorner = SimUtils.distance(shotTarget, goal);
            boolean nearGoal = distToGoalForCorner < 2.0;
            boolean defenderDeflected = false;
            if (nearGoal) {
                for (Player d : state.getPlayers()) {
                    if (!defendingTeam.equals(d.getTeam())) continue;
                    if ("GK".equals(d.getRole())) continue;
                    if (SimUtils.distance(d.getPosition(), shotTarget) < 1.5) {
                        defenderDeflected = true;
                        break;
                    }
                }
            }
            // Blocked shot near goal → high chance of corner (30%)
            if (defenderDeflected && nearGoal && state.getRandom().nextInt(10) < 4) {
                actionEngine.shotMissed();
                handleBallOutOfBounds(state, stats, logger, FootballRulesService.RestartType.CORNER,
                        ball.getPosition(), defendingTeam);
                return;
            }

            actionEngine.shotMissed();
            logger.logActionOutcome(state, action,
                    "MISS (distToGoal=" + String.format("%.2f", distToGoal) + ")",
                    shooter, null, "OUTCOME");

            // Ball may be out of bounds after a miss
            FootballRulesService.RestartType restart =
                    rulesService.determineRestart(ball.getPosition(), shooterTeam);
            if (restart != FootballRulesService.RestartType.NONE) {
                handleBallOutOfBounds(state, stats, logger, restart, ball.getPosition(),
                        "HOME".equals(shooterTeam) ? "AWAY" : "HOME");
            }
        }
    }

    /**
     * Handle ball leaving the pitch: corner, goal kick, or throw-in.
     * The team that did NOT last touch the ball receives the restart.
     */
    private void handleBallOutOfBounds(MatchState state, MatchStatsCollector stats,
                                        ActionLogService logger,
                                        FootballRulesService.RestartType restart,
                                        Position ballPos, String lastTouchTeam) {
        String defendingTeam = "HOME".equals(lastTouchTeam) ? "AWAY" : "HOME";
        // For goal kicks, the restart team is lastTouchTeam (team whose goal
        // the ball went out near). For corners/throw-ins, it's defendingTeam
        // (team that didn't touch the ball last).
        String restartTeam = (restart == FootballRulesService.RestartType.GOAL_KICK)
                ? lastTouchTeam : defendingTeam;
        state.setKickoffTeam(restartTeam);
        state.setCelebrating(false);
        // Clear any lingering action / chasers so we get a clean restart
        state.setAction(null);
        state.getBall().setTarget(null);
        state.getBall().setCarrier(null);
        state.setCarrier(null);
        state.clearActiveChasers();

        // Release any locked receivers — the action that went out is abandoned
        // But do NOT unlock sent-off (red card) or injured players
        for (Player p : state.getPlayers()) {
            if (p.isLocked() && !"GK".equals(p.getRole()) && !p.isSentOff() && !p.isInjured()) {
                p.setLocked(false);
            }
        }

        // NOTE: We do NOT set kickoffPending=true here.  That flag is reserved for
        // true kickoffs (start of match, after a goal, second half).  For
        // corners / goal-kicks / throw-ins the ball is placed at the correct
        // restart position and handed to the appropriate player, then a brief
        // hold delay lets other players reposition before the next decision.
        // This mirrors the swingUIDemo ScheduleRestart logic (SimEngine L599).

        switch (restart) {
            case CORNER -> {
                stats.onCorner(defendingTeam);
                boolean rightCorner = ballPos.getColumn() < 1;
                Position cornerPos;
                if (ballPos.getRow() > 7) {
                    cornerPos = new Position(6.5, rightCorner ? 0.5 : 6.5);
                } else {
                    cornerPos = new Position(1.5, rightCorner ? 0.5 : 6.5);
                }
                state.getBall().setPosition(cornerPos);
                Player cornerTaker = findNearestNonGoalkeeperTo(state, cornerPos, defendingTeam);
                giveBallToRestartTaker(state, cornerTaker, cornerPos);
                logger.logCorner(state, defendingTeam, rightCorner, "CORNER");
                logger.logRestart(state, "CORNER for " + defendingTeam + " at " + cornerPos
                        + " (ball was at " + ballPos + ")", "CORNER");
                break;
            }
            case GOAL_KICK -> {
                stats.onGoalKick(restartTeam);
                Position gkPos = "HOME".equals(restartTeam)
                        ? new Position(1, 3.5) : new Position(7, 3.5);
                state.getBall().setPosition(gkPos);
                Player keeper = findKeeper(state, restartTeam);
                giveBallToRestartTaker(state, keeper, gkPos);
                logger.logGoalKick(state, restartTeam, "GOAL_KICK");
                logger.logRestart(state, "GOAL KICK for " + restartTeam + " at " + gkPos, "GOAL_KICK");
                break;
            }
            case THROW_IN -> {
                stats.onThrowIn(defendingTeam);
                double row = SimUtils.clamp(ballPos.getRow(), 1, 7);
                double col = ballPos.getColumn() < 1 ? 1.0 : 6.0;
                Position throwInPos = new Position(row, col);
                state.getBall().setPosition(throwInPos);
                Player throwInTaker = findNearestNonGoalkeeperTo(state, throwInPos, defendingTeam);
                giveBallToRestartTaker(state, throwInTaker, throwInPos);
                logger.logThrowIn(state, defendingTeam, "THROW_IN");
                logger.logRestart(state, "THROW-IN for " + defendingTeam + " at " + throwInPos
                        + " (ball was at " + ballPos + ")", "THROW_IN");
                break;
            }
            default -> {
                // Fallback: loose ball at the out-of-bounds position
                state.getBall().setPosition(ballPos);
                // Give to nearest player from the defending team so play can resume
                Player nearest = findNearestNonGoalkeeperTo(state, ballPos, defendingTeam);
                if (nearest != null) {
                    giveBallToRestartTaker(state, nearest, ballPos);
                }
                logger.logRestart(state, "LOOSE BALL for " + defendingTeam + " at " + ballPos, "RESTART");
            }
        }

        // Brief hold so players can reposition before the restart taker decides
        state.setActionDelayTicks(5); // Reduced from 20 to 5 ticks (0.125 seconds)
        state.setStatus("RESTART: " + restart + " for " + defendingTeam);
    }

    /**
     * Give the ball to a restart taker at a specific position.
     * Used for corners, goal kicks, and throw-ins — unlike handleKickoff,
     * this does NOT go through the center-circle kickoff path.
     */
    private void giveBallToRestartTaker(MatchState state, Player taker, Position pos) {
        if (taker == null) return;
        taker.setPosition(pos);
        taker.setTarget(null);
        taker.setLocked(false);
        state.getBall().setCarrier(taker);
        state.setCarrier(taker);
        state.setSetPiecePending(true);
    }

    /**
     * Find the nearest non-goalkeeper player from the given team to a position.
     */
    private Player findNearestNonGoalkeeperTo(MatchState state, Position pos, String team) {
        Player nearest = null;
        double minDist = Double.MAX_VALUE;
        for (Player p : state.getPlayers()) {
            if (!team.equals(p.getTeam())) continue;
            if ("GK".equals(p.getRole())) continue;
            if (p.isSentOff() || p.isInjured() || p.isSubstituted()) continue;
            double dist = SimUtils.distance(p.getPosition(), pos);
            if (dist < minDist) {
                minDist = dist;
                nearest = p;
            }
        }
        return nearest;
    }

    /**
     * Find the goalkeeper for the given team side.
     * @param teamSide "HOME" or "AWAY" (NOT team display name)
     */
    private Player findKeeper(MatchState state, String teamSide) {
        return state.getPlayers().stream()
                .filter(p -> teamSide.equals(p.getTeam()) && "GK".equals(p.getRole()))
                .findFirst().orElse(null);
    }

    private void resolveChase(MatchState state, ActionEngine actionEngine, DuelEngine duelEngine,
                              PlayerSelectionEngine selection, FootballRulesService rulesService,
                              MatchStatsCollector stats, ActionLogService logger) {
        Position ballPos = state.getBall().getPosition();
        duelEngine.update(state.getAction());
        Player attacker = duelEngine.getActiveDuelAttacker();
        if (attacker != null) {
            Player defender = duelEngine.getActiveDuelDefender();
            DuelType type = duelEngine.getActiveDuelType();
            logger.logInfo(state, "CHASE: " + attacker.getLabel() + " vs " + defender.getLabel()
                    + " converged at (" + String.format("%.2f", ballPos.getRow()) + ","
                    + String.format("%.2f", ballPos.getColumn()) + ")", "CHASE");
            DuelResolver.DuelResult result = duelEngine.resolveActiveDuel(state.getAction());
            duelEngine.closeAfterResolution();
            recordDuelStats(state, actionEngine, rulesService, stats, logger, selection,
                    result, attacker, defender, type, state.getAction());
            if (result != null && result.winner() != null) {
                logger.logChaseWinner(state, result.winner(), "CHASE");
            }
            return;
        }
        // No duel formed — fall back to normal completion: who reached the ball first
        actionEngine.checkActionCompletion();
    }

    private void recordActionStats(MatchState state, ActionLogService logger,
                                    DecisionType decision, Player carrier) {
        // Per-action counters (pass attempts, shot count) are recorded directly in
        // executeDecision / ActionEngine; this is a trace hook for decision logging.
        if (carrier == null) {
            logger.logInfo(state, "No carrier — decision had no actor", "DECISION");
        }
    }

    private void recordDuelStats(MatchState state, ActionEngine actionEngine,
                                  FootballRulesService rulesService,
                                  MatchStatsCollector stats,
                                  ActionLogService logger,
                                  PlayerSelectionEngine selection,
                                  DuelResolver.DuelResult result,
                                  Player attacker, Player defender,
                                  DuelType duelType, Action action) {
        if (result == null) return;

        logger.logDuel(state, result, attacker, defender, duelType, "DUEL");

        // Apply duel cooldown to loser (prevents same defender dueling every tick)
        if (result.outcome() == DuelOutcome.DEFENDER_WINS) {
            state.blockAfterDuel(attacker);
        } else {
            state.blockAfterDuel(defender);
        }

        // Track defensive actions when a defender contests
        if (duelType == DuelType.DRIBBLE || duelType == DuelType.RECEIVE_PASS) {
            stats.onTackle(defender.getId());
        }

        String defendingTeam = defender.getTeam();

        if (result.outcome() == DuelOutcome.DEFENDER_WINS) {
            // Shot duels: GK save is not a foul — skip foul check
            if (duelType == DuelType.SHOT) {
                logger.logInfo(state, "Shot saved by " + defender.getLabel()
                        + " — clean save", "DUEL", defender);
                actionEngine.shotSaved(defender);
                return;
            }
            // Defender won the challenge — evaluate for foul
            boolean foul = rulesService.isFoul(defender, attacker);
            if (foul) {
                stats.onFoul(defendingTeam, defender.getId());
                // Check for existing yellow BEFORE determining card
                int existingYellows = state.getYellowCardCount(defender.getId());
                FootballRulesService.CardType card =
                        rulesService.determineCard(defender, existingYellows >= 1);

                // Check if foul is in the penalty box → penalty kick
                // Penalty box: ~16.5m ≈ 1 row deep (rows 6-7 for HOME, rows 1-2 for AWAY)
                // Use the position closer to goal line
                boolean homeAttacking = "HOME".equals(attacker.getTeam());
                Position defenderPos = defender.getPosition();
                Position attackerPos = attacker.getPosition();
                Position foulPos = homeAttacking
                        ? (defenderPos.getRow() >= attackerPos.getRow() ? defenderPos : attackerPos)
                        : (defenderPos.getRow() <= attackerPos.getRow() ? defenderPos : attackerPos);
                boolean inPenaltyBox = homeAttacking
                        ? (foulPos.getRow() >= 6 && foulPos.getColumn() >= 1 && foulPos.getColumn() <= 6)
                        : (foulPos.getRow() <= 2 && foulPos.getColumn() >= 1 && foulPos.getColumn() <= 6);

                if (card == FootballRulesService.CardType.RED) {
                    // VAR check for red card
                    boolean redConfirmed = varService.checkRedCard(defender, existingYellows >= 1);
                    varService.logVARDecision("RED_CARD", defender.getLabel());

                    if (redConfirmed) {
                        if (existingYellows >= 1) {
                            state.incrementYellowCards(defender.getId());
                        }
                        stats.onRedCard(defendingTeam, defender.getId());
                        defender.setSentOff(true);
                        defender.setLocked(true);
                        logger.logFoulWithCard(state, defendingTeam, defender.getId(),
                                defender.getLabel(), "RED (VAR " + varService.getLastVARDecision() + ")",
                                existingYellows + 1, "FOUL");
                    } else {
                        // VAR overturned red → downgrade to yellow
                        state.incrementYellowCards(defender.getId());
                        stats.onYellowCard(defendingTeam, defender.getId());
                        logger.logFoulWithCard(state, defendingTeam, defender.getId(),
                                defender.getLabel(), "YELLOW (VAR overturned red)",
                                existingYellows + 1, "FOUL");
                    }
                    if (inPenaltyBox) {
                        boolean penaltyConfirmed = varService.checkPenalty(foulPos, homeAttacking, true);
                        varService.logVARDecision("PENALTY", attacker.getLabel());
                        if (penaltyConfirmed) {
                            logger.logInfo(state, "PENALTY awarded (foul in box, red card, VAR " + varService.getLastVARDecision() + ")", "PENALTY", attacker);
                            executePenaltyFromFoul(state, actionEngine, stats, logger, selection, attacker, defender);
                        } else {
                            logger.logInfo(state, "VAR OVERTURNED penalty — free kick outside box", "VAR", attacker);
                            actionEngine.giveBallTo(attacker, "foul → free kick to " + attacker.getTeam());
                            state.setSetPiecePending(true);
                        }
                    } else {
                        actionEngine.giveBallTo(attacker,
                                "foul → free kick to " + attacker.getTeam());
                        state.setSetPiecePending(true);
                    }
                } else if (card == FootballRulesService.CardType.YELLOW) {
                    // VAR review for yellow card — may upgrade to red or downgrade to none
                    String varResult = varService.checkYellowCard(defender);
                    varService.logVARDecision("YELLOW_CARD", defender.getLabel());
                    if ("UPGRADE_TO_RED".equals(varResult)) {
                        // VAR upgraded yellow → red
                        state.incrementYellowCards(defender.getId());
                        stats.onRedCard(defendingTeam, defender.getId());
                        defender.setSentOff(true);
                        defender.setLocked(true);
                        logger.logFoulWithCard(state, defendingTeam, defender.getId(),
                                defender.getLabel(), "RED (VAR upgraded yellow)", existingYellows + 1, "FOUL");
                    } else if ("DOWNGRADE_TO_NONE".equals(varResult)) {
                        // VAR downgraded yellow → no card, play continues
                        logger.logInfo(state, "VAR DOWNGRADED yellow card — no card given", "VAR", defender);
                        // Still a foul — free kick
                        Ball ball = state.getBall();
                        ball.setCarrier(null);
                        ball.setTarget(null);
                        state.setCarrier(null);
                        actionEngine.complete("FREE_KICK → " + attacker.getTeam());
                        ball.setPosition(defender.getPosition());
                        actionEngine.giveBallTo(attacker, "foul → free kick to " + attacker.getTeam());
                        state.setSetPiecePending(true);
                    } else {
                        // Yellow confirmed
                        state.incrementYellowCards(defender.getId());
                        stats.onYellowCard(defendingTeam, defender.getId());
                        logger.logFoulWithCard(state, defendingTeam, defender.getId(),
                                defender.getLabel(), "YELLOW (VAR confirmed)", existingYellows + 1, "FOUL");
                        if (inPenaltyBox) {
                            boolean penaltyConfirmed = varService.checkPenalty(foulPos, homeAttacking, true);
                            varService.logVARDecision("PENALTY", attacker.getLabel());
                            if (penaltyConfirmed) {
                                logger.logInfo(state, "PENALTY awarded (foul in box, yellow card, VAR " + varService.getLastVARDecision() + ")", "PENALTY", attacker);
                                executePenaltyFromFoul(state, actionEngine, stats, logger, selection, attacker, defender);
                            } else {
                                logger.logInfo(state, "VAR OVERTURNED penalty — free kick outside box", "VAR", attacker);
                                actionEngine.giveBallTo(attacker, "foul → free kick to " + attacker.getTeam());
                                state.setSetPiecePending(true);
                            }
                        } else {
                            actionEngine.giveBallTo(defender,
                                    "tackle won (yellow card issued)");
                            state.setSetPiecePending(true);
                        }
                    }
                } else {
                    if (inPenaltyBox) {
                        // VAR review for penalty — may overturn borderline decisions
                        boolean penaltyConfirmed = varService.checkPenalty(foulPos, homeAttacking, true);
                        varService.logVARDecision("PENALTY", attacker.getLabel());
                        if (penaltyConfirmed) {
                            logger.logInfo(state, "PENALTY awarded (foul in box, VAR " + varService.getLastVARDecision() + ")", "PENALTY", attacker);
                            executePenaltyFromFoul(state, actionEngine, stats, logger, selection, attacker, defender);
                        } else {
                            // VAR overturned penalty — play continues with free kick outside box
                            logger.logInfo(state, "VAR OVERTURNED penalty — free kick outside box", "VAR", attacker);
                            actionEngine.giveBallTo(attacker, "foul → free kick to " + attacker.getTeam());
                            state.setSetPiecePending(true);
                        }
                    } else {
                        logger.logFoul(state, defendingTeam, defender.getId(),
                                defender.getLabel(), "Tackle foul (no card) → FREE KICK", "FREE_KICK");
                        Ball ball = state.getBall();
                        ball.setCarrier(null);
                        ball.setTarget(null);
                        state.setCarrier(null);
                        actionEngine.complete("FREE_KICK → " + attacker.getTeam());
                        ball.setPosition(defender.getPosition());
                        actionEngine.giveBallTo(attacker,
                                "foul → free kick to " + attacker.getTeam());
                        state.setSetPiecePending(true);
                    }
                }
            } else {
                // Clean tackle — defender wins and keeps the ball
                actionEngine.giveBallTo(defender, "clean tackle won");
            }
        } else {
            // Attacker won the challenge
            String winningTeam = attacker.getTeam();
            if (duelType == DuelType.SHOT) {
                // Shot contest — ball already in flight toward goal
                logger.logInfo(state, "Shot duel won by " + attacker.getLabel()
                        + " — shot continues toward goal", "DUEL", attacker);
            } else if (duelType == DuelType.DRIBBLE) {
                stats.onInterception(attacker.getId()); // attacker bypassed defender
                actionEngine.giveBallTo(attacker,
                        "dribble won past " + defender.getLabel());
            } else if (duelType == DuelType.RECEIVE_PASS) {
                actionEngine.giveBallTo(attacker, "contested catch won");
            } else if (duelType == DuelType.CHASE_BALL) {
                stats.onInterception(attacker.getId());
                actionEngine.giveBallTo(attacker, "loose ball recovered");
                logger.logChaseWinner(state, attacker, "CHASE");
            } else {
                actionEngine.giveBallTo(attacker, "duel won");
            }
        }
    }

    private String findMotM(List<PlayerMatchStats> home, List<PlayerMatchStats> away) {
        String bestName = "";
        double bestRating = -1;
        for (PlayerMatchStats p : home) {
            if (p.rating() > bestRating) {
                bestRating = p.rating();
                bestName = p.playerName();
            }
        }
        for (PlayerMatchStats p : away) {
            if (p.rating() > bestRating) {
                bestRating = p.rating();
                bestName = p.playerName();
            }
        }
        return bestName;
    }

    /**
     * Find a defender who can intercept a pass in flight.
     * AIR passes are harder to intercept — need to be closer and have aerial skill.
     * GROUND passes are easier to intercept — standard interception radius.
     */
    private Player findPassInterceptor(Action action, Position ballPos, MatchState state) {
        if (action.getActingPlayer() == null) return null;
        String passingTeam = action.getActingPlayer().getTeam();
        PassHeight passHeight = action.getPassHeight() != null ? action.getPassHeight() : PassHeight.GROUND;

        // AIR passes: interceptor must be closer (0.6 cells) — ball is in the air
        // GROUND passes: normal radius (1.0 cells)
        double interceptRadius = passHeight == PassHeight.AIR ? 0.6 : 1.0;

        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (Player p : state.getPlayers()) {
            if (p.getTeam().equals(passingTeam)) continue;
            if ("GK".equals(p.getRole())) continue;
            if (p.isLocked()) continue;
            double dist = SimUtils.distance(p.getPosition(), ballPos);
            if (dist < interceptRadius && dist < bestDist) {
                double skill = p.getSkills().defender() + p.getSkills().technique();
                // AIR passes: need aerial skill (technique matters more)
                // GROUND passes: standard interception
                double interceptChance;
                if (passHeight == PassHeight.AIR) {
                    interceptChance = skill / 40.0 * 0.15; // max ~7.5% — harder
                } else {
                    interceptChance = skill / 40.0 * 0.30; // max ~15% — easier
                }
                if (state.getRandom().nextDouble() < interceptChance) {
                    best = p;
                    bestDist = dist;
                }
            }
        }
        return best;
    }

    /**
     * Find a defender who deflects a pass in flight.
     * AIR passes deflect when near any defender (ball bounces off body/head).
     * GROUND passes deflect when near a defender (ball hits foot/leg).
     * Deflection ≠ interception: ball becomes loose, defender doesn't gain possession.
     */
    private Player findPassDeflector(Action action, Position ballPos, MatchState state) {
        if (action.getActingPlayer() == null) return null;
        String passingTeam = action.getActingPlayer().getTeam();
        PassHeight passHeight = action.getPassHeight() != null ? action.getPassHeight() : PassHeight.GROUND;

        // Deflection radius: AIR passes deflect from further (ball is higher, more body contact)
        // GROUND passes deflect from closer (ball is on the ground, harder to accidentally touch)
        double deflectionRadius = passHeight == PassHeight.AIR ? 0.7 : 0.4;

        for (Player p : state.getPlayers()) {
            if (p.getTeam().equals(passingTeam)) continue;
            if ("GK".equals(p.getRole())) continue;
            if (p.isLocked() || p.isSentOff() || p.isInjured()) continue;
            double dist = SimUtils.distance(p.getPosition(), ballPos);
            if (dist < deflectionRadius) {
                // Deflection chance: AIR passes deflect more easily (higher trajectory, more body contact)
                // GROUND passes deflect less often (ball is lower, harder to accidentally touch)
                double deflectionChance;
                if (passHeight == PassHeight.AIR) {
                    deflectionChance = 0.18; // 18% — AIR passes deflect more
                } else {
                    deflectionChance = 0.08; // 8% — GROUND passes deflect less
                }
                if (state.getRandom().nextDouble() < deflectionChance) {
                    return p;
                }
            }
        }
        return null;
    }

    private void executePenaltyFromFoul(MatchState state, ActionEngine actionEngine,
                                         MatchStatsCollector stats, ActionLogService logger,
                                         PlayerSelectionEngine selection,
                                         Player attacker, Player fouler) {
        String kickingTeam = attacker.getTeam();
        Player goalkeeper = selection.findGoalkeeper(
                "HOME".equals(kickingTeam) ? "AWAY" : "HOME");
        if (goalkeeper == null) {
            actionEngine.giveBallTo(attacker, "penalty — no GK found");
            return;
        }
        stats.onPenalty(kickingTeam);
        logger.logInfo(state, "PENALTY KICK by " + attacker.getLabel()
                + " against " + goalkeeper.getLabel(), "PENALTY", attacker);
        actionEngine.executePenaltyKick(attacker, goalkeeper);
    }

    private MatchReport buildReport(String homeTeam, String awayTeam,
                                     int homeGoals, int awayGoals,
                                     TeamMatchStats homeStats, TeamMatchStats awayStats,
                                     List<GoalDetail> goals, String motm) {
        StringBuilder summary = new StringBuilder();
        summary.append(homeTeam).append(" ").append(homeGoals)
               .append(" - ").append(awayGoals).append(" ").append(awayTeam).append(". ");

        if (homeGoals > awayGoals) {
            summary.append(homeTeam).append(" wins!");
        } else if (awayGoals > homeGoals) {
            summary.append(awayTeam).append(" wins!");
        } else {
            summary.append("Draw!");
        }

        summary.append(" Possession: ")
               .append(String.format("%.0f%% - %.0f%%", homeStats.possessionPercent(), awayStats.possessionPercent()));
        summary.append(". Shots: ").append(homeStats.shots()).append(" - ").append(awayStats.shots());
        summary.append(". Passes: ").append(homeStats.passesCompleted()).append("/").append(homeStats.passesAttempted())
               .append(" (").append(homeStats.passAccuracy()).append("%) - ")
               .append(awayStats.passesCompleted()).append("/").append(awayStats.passesAttempted())
               .append(" (").append(awayStats.passAccuracy()).append("%)");

        List<String> keyEvents = new ArrayList<>();
        for (GoalDetail g : goals) {
            keyEvents.add(g.description());
        }
        keyEvents.add("Man of the Match: " + motm);

        String headline = homeTeam + " " + homeGoals + " - " + awayGoals + " " + awayTeam;

        return new MatchReport(headline, summary.toString(),
                homeTeam, awayTeam, homeGoals, awayGoals,
                homeStats.possessionPercent(), awayStats.possessionPercent(),
                keyEvents, motm, "");
    }

    /**
     * Check for injuries caused by fatigue. Injured players are replaced by
     * substitutes with the same skills and role.
     */
    private void checkInjuries(MatchState state, PlayerSelectionEngine selection,
                                ActionEngine actionEngine, MatchStatsCollector stats,
                                ActionLogService logger) {
        FatigueService fatigueService = new FatigueService(state);
        for (Player p : List.copyOf(state.getPlayers())) {
            if (p.isSentOff() || p.isInjured() || p.isSubstituted()) continue;
            if (p.isLocked()) continue;
            if (!fatigueService.checkInjuryRisk(p)) continue;

            // Player is injured
            p.setInjured(true);
            p.setLocked(true);
            String team = p.getTeam();
            int yellows = state.getYellowCardCount(p.getId());

            logger.logInfo(state, "INJURY: " + p.getLabel() + " (" + team + ") is injured"
                    + " (fatigue=" + String.format("%.0f", p.getFatigue() * 100) + "%)",
                    "INJURY", p);

            // Create substitute with same skills and role
            String subId = p.getId() + "_SUB_" + state.getSimulationTick();
            String subLabel = p.getLabel() + " (sub)";
            PlayerSkills subSkills = p.getSkills();
            Player substitute = new Player(subId, subLabel, team, p.getRole(),
                    p.getPosition(), p.getAlternativePosition(), subSkills, p.getHeightCm());

            // Add substitute to match
            state.addSubstitute(substitute, p);
            stats.registerPlayer(substitute);

            logger.logInfo(state, "SUBSTITUTION: " + substitute.getLabel()
                    + " replaces " + p.getLabel() + " (" + team + ")",
                    "SUBSTITUTION", substitute);
        }
    }

    // ── Diagnostic output ────────────────────────────────────────────────

    private void printDiagnosticDecision(MatchState state, DecisionOption chosen,
                                          PlaymakingDecisionEngine decisionEngine,
                                          FootballRulesService rulesService) {
        Player carrier = state.getCarrier();
        String team = carrier.getTeam();
        int ticks = state.getMatchTicks();
        int minute = ticks / 40;
        int second = (int) Math.round((ticks % 40) * 60.0 / 40);

        System.out.println("------------------------------------------------------------");
        System.out.printf("#%d [%d:%02d] %s (%s) PM=%d PASS=%d STR=%d TEC=%d FAT=%.0f%% | Ball:(%.1f,%.1f) Phase=%s%n",
                0, minute, second, carrier.getLabel(), team,
                (int) carrier.getSkills().playmaking(),
                (int) carrier.getSkills().passing(),
                (int) carrier.getSkills().striker(),
                (int) carrier.getSkills().technique(),
                carrier.getFatigue() * 100,
                state.getBall().getPosition().getRow(),
                state.getBall().getPosition().getColumn(),
                state.getPhase());

        // Print positions
        System.out.println("  POSITIONS (HOME=UPPER, away=lower, *=carrier):");
        for (int row = 7; row >= 0; row--) {
            StringBuilder sb = new StringBuilder("  ");
            sb.append(String.format("R%d ", row));
            for (int col = 0; col <= 6; col++) {
                String slot = " .    ";
                for (Player p : state.getPlayers()) {
                    if (p.isUnavailable()) continue;
                    if (Math.abs(p.getPosition().getRow() - row) < 0.5
                            && Math.abs(p.getPosition().getColumn() - col) < 0.5) {
                        String n = p.getLabel();
                        if (n.length() > 5) n = n.substring(0, 5);
                        String prefix = p == carrier ? "*" : "";
                        if ("HOME".equals(p.getTeam())) {
                            slot = prefix + String.format("%-5s", n.toUpperCase());
                        } else {
                            slot = prefix + String.format("%-5s", n.toLowerCase());
                        }
                        break;
                    }
                }
                sb.append(slot).append(" ");
            }
            System.out.println(sb);
        }
        System.out.println();

        // Print all options with details
        List<DecisionOption> allOptions = decisionEngine.getLastScoredOptions();
        System.out.println("  OPTIONS:");
        for (DecisionOption opt : allOptions) {
            String targetName = opt.getTarget() != null ? opt.getTarget().getLabel() : "---";
            String marker = opt == chosen ? " <<<" : "";
            System.out.printf("    %-8s %6.2f  %s  [%s]%s%n",
                    opt.getType(), opt.getScore(), targetName, opt.getReason(), marker);

            if (opt.getType() == DecisionType.PASS && opt.getTarget() != null) {
                Player receiver = opt.getTarget();
                double dist = SimUtils.distance(carrier.getPosition(), receiver.getPosition());
                boolean clearLane = diagnosticIsPassingLaneClear(carrier, receiver, state);
                int defAhead = diagnosticCountNonGkDefendersAhead(receiver, state);
                int skill = Math.max(1, Math.min(20, (int) Math.round(carrier.getSkills().passing())));
                double maxDev = (20 - skill) * 0.10 * 0.6;
                boolean offside = rulesService.isOffside(receiver, carrier.getPosition(),
                        state.getBall().getPosition());
                double openSpace = diagnosticOpenSpaceAround(receiver, state);
                System.out.printf("       dist=%.2f | lane=%s | openSpace=%.1f | defendersAhead=%d | offside=%s | passSkill=%d | maxDev=%.2f%n",
                        dist, clearLane ? "CLEAR" : "BLOCKED", openSpace,
                        defAhead, offside ? "YES" : "no", skill, maxDev);
            }

            if (opt.getType() == DecisionType.THRU && opt.getTarget() != null) {
                Player runner = opt.getTarget();
                double dist = SimUtils.distance(carrier.getPosition(), runner.getPosition());
                int defAhead = diagnosticCountNonGkDefendersAhead(runner, state);
                System.out.printf("       dist=%.2f | defendersAhead=%d | pace=%d%n",
                        dist, defAhead, (int) runner.getSkills().pace());
            }

            if (opt.getType() == DecisionType.SHOT) {
                Position goal = ActionEngine.goalPositionFor(carrier.getTeam());
                double distToGoal = SimUtils.distance(carrier.getPosition(), goal);
                int striker = (int) Math.round(carrier.getSkills().striker());
                double maxShotDev = (20 - striker) * 0.22;
                System.out.printf("       distToGoal=%.2f | striker=%d | maxDev=%.2f | goalThresh=0.35%n",
                        distToGoal, striker, maxShotDev);
            }

            if (opt.getType() == DecisionType.CARRY) {
                double openSpace = diagnosticOpenSpaceAround(carrier, state);
                System.out.printf("       openSpace=%.1f | technique=%d | fatigue=%.0f%%%n",
                        openSpace, (int) carrier.getSkills().technique(),
                        carrier.getFatigue() * 100);
            }
        }

        System.out.printf("  >>> CHOSEN: %s (score=%.3f)%n", chosen.getType(), chosen.getScore());
        System.out.println();
    }

    private boolean diagnosticIsPassingLaneClear(Player carrier, Player receiver, MatchState state) {
        Position a = carrier.getPosition();
        Position b = receiver.getPosition();
        for (Player p : state.getPlayers()) {
            if (p == carrier || p == receiver) continue;
            if (!p.getTeam().equals(carrier.getTeam())) {
                double dx = b.getColumn() - a.getColumn();
                double dy = b.getRow() - a.getRow();
                double len = Math.hypot(dx, dy);
                if (len < 1e-9) continue;
                double t = ((p.getPosition().getColumn() - a.getColumn()) * dx
                        + (p.getPosition().getRow() - a.getRow()) * dy) / (len * len);
                t = Math.max(0, Math.min(1, t));
                double projX = a.getColumn() + t * dx;
                double projY = a.getRow() + t * dy;
                double dist = SimUtils.distance(p.getPosition(), new Position(projY, projX));
                if (dist < 0.8) return false;
            }
        }
        return true;
    }

    private int diagnosticCountNonGkDefendersAhead(Player receiver, MatchState state) {
        boolean home = "HOME".equals(receiver.getTeam());
        int count = 0;
        for (Player p : state.getPlayers()) {
            if (p.getTeam().equals(receiver.getTeam())) continue;
            if ("GK".equals(p.getRole())) continue;
            boolean goalSide = home
                    ? p.getPosition().getRow() >= receiver.getPosition().getRow()
                    : p.getPosition().getRow() <= receiver.getPosition().getRow();
            if (goalSide) count++;
        }
        return count;
    }

    private double diagnosticOpenSpaceAround(Player player, MatchState state) {
        double minDist = Double.MAX_VALUE;
        for (Player p : state.getPlayers()) {
            if (p == player) continue;
            double dist = SimUtils.distance(player.getPosition(), p.getPosition());
            if (dist < minDist) minDist = dist;
        }
        if (minDist == Double.MAX_VALUE) return 40;
        return Math.min(40, Math.max(0, (minDist - 0.5) * 10));
    }
}
