package org.example.footballmanager.demo.service.result;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.engine.*;
import org.example.footballmanager.demo.service.model.*;
import org.example.footballmanager.demo.service.recording.MatchRecorder;
import org.example.footballmanager.demo.service.recording.TickObserver;
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

    // A restart taker walks to the ball via the movement engine (no teleport).
    // RESTART_WALK_MAX_TICKS is a long last-resort guard: if after all those ticks
    // the taker is STILL genuinely collision-blocked at the spot, teleport him onto
    // the ball as an absolute deadlock safeguard — set pieces must never freeze.
    private static final int RESTART_WALK_MAX_TICKS = 60;
    private static final double RESTART_WALK_SPEED = 0.4;

    private final long seed;
    private final SimulationRandom random;
    private boolean verbose = false;
    private Map<String, Integer> passExchangeCount = new HashMap<>();

    // VAR review service. Created per-match inside simulate() and referenced by
    // the private helper methods (executeDecision, handleShotArrival, …) for
    // offside / goal / card reviews.
    private VARService varService;

    // Restart manager. Handles kickoff, corners, goal kicks, throw-ins.
    // Extracted from MatchSimulator (Phase 1) to enforce §37 boundaries.
    private RestartManager restartManager;

    // Offside service. Handles offside checks + VAR review + free kick awarding.
    // Extracted from MatchSimulator (Phase 2) to eliminate duplicated blocks.
    private OffsideService offsideService;

    // Discipline service. Handles foul→card→VAR→penalty/free-kick decisions.
    // Extracted from MatchSimulator (Phase 3) to enforce §37 boundaries.
    private DisciplineService disciplineService;

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
        return simulate(homePlayers, awayPlayers, homeTeamName, awayTeamName, null);
    }

    /**
     * Run a full match between two teams, invoking {@code observer.onTick} at the
     * end of every tick (after movement, fatigue, transition, and snapshot capture).
     *
     * @param observer per-tick callback (may be null)
     */
    public MatchResult simulate(List<Player> homePlayers, List<Player> awayPlayers,
                                 String homeTeamName, String awayTeamName,
                                 TickObserver observer) {
        if (homePlayers.size() != 11 || awayPlayers.size() != 11) {
            throw new IllegalArgumentException("Each team must have exactly 11 players");
        }

        List<Player> allPlayers = new ArrayList<>();
        allPlayers.addAll(homePlayers);
        allPlayers.addAll(awayPlayers);

        Position kickoffPos = new Position(4, 3.5);
        Ball ball = new Ball(kickoffPos, kickoffPos);
        TacticsRules tactics = new TacticsRules();
        System.out.println("TACTICS_SOURCE: " + tactics.getSource() + " | ruleCount=" + tactics.getRuleCount());
        // DEBUG: dump loaded rules to a JSON file the user can inspect
        tactics.dumpLoadedRules("/tmp/loaded_tactics.json");
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
        ActionLogService logger = new ActionLogService();
        TacticalIntentEngine tacticalEngine = new TacticalIntentEngine(state, logger);
        ThreatAssessmentService threatService = new ThreatAssessmentService(state);
        PlayerPerceptionService perceptionService = new PlayerPerceptionService(state);
        FootballRulesService rulesService = new FootballRulesService(state);
        TransitionService transitionService = new TransitionService(state, recorder);
        FatigueService fatigueService = new FatigueService(state);
        this.varService = new VARService(state, random.getRandom(), recorder);

        PlaymakingDecisionEngine decisionEngine = new PlaymakingDecisionEngine(state, selection,
                threatService, perceptionService, random.getRandom());

        this.restartManager = new RestartManager(state, selection, logger, homePlayers, awayPlayers);

        this.offsideService = new OffsideService(varService, rulesService, logger, selection, stats, recorder);

        this.disciplineService = new DisciplineService(varService, rulesService, logger, selection, stats);

        logger.logInfo(state, "MATCH START: " + homeTeamName + " vs " + awayTeamName + " | seed=" + seed, "MATCH");

        // Start match
        state.startMatchSimulation();
        state.setPhase(MatchPhase.KICK_OFF);
        state.setKickoffTeam("HOME");
        state.setKickoffPending(true);

        // Pre-match kickoff setup: position players on their own halves, place the
        // ball AND the kicker at the center spot, and capture a tick-0 snapshot.
        // This gives the viewer a clean "KICK OFF" frame (ball at center, 0:00)
        // before the first tick of the main loop fires the backward kickoff pass.
        restartManager.handleKickoffPreMatch(stats);
        recorder.captureSnapshot(state);

        int totalTicks = 0;
        int maxTicks = MATCH_MINUTES * TICKS_PER_MINUTE + 3 * TICKS_PER_MINUTE;
        int tickHalfTime = 0, tickKickoff = 0, tickCelebration = 0, tickDelay = 0,
                tickCornerHold = 0, tickPassFlight = 0, tickShotFlight = 0,
                tickChase = 0, tickDecision = 0, tickFlightNoAction = 0, tickLoose = 0,
                tickCarryStuck = 0, restartWalkTicks = 0;

        while (totalTicks < maxTicks && !state.isMatchFinished()) {
            // Tick-level verbose logging: emit every tick's sim-tick id, match clock
            // and the action currently in progress so stalls / gaps are fully
            // traceable in the app log (enabled via setVerbose(true), gated by
            // diagnosticCutoffTicks). Channel TICK; logged at the START of the tick.
            if (verbose && totalTicks < diagnosticCutoffTicks) {
                Action cur = state.getAction();
                String actionDesc = (cur != null ? cur.getType().name() : "none")
                        + (state.isSetPiecePending() ? " (setPiecePending)"
                            : state.isCelebrating() ? " (celebrating)"
                            : state.isKickoffPending() ? " (kickoffPending)" : "");
                logger.logInfo(state, "TICK tick=" + state.getSimulationTick()
                        + " action=" + actionDesc, "TICK");
            }
            // Handle half-time → start second half
            if (state.isHalfTime()) {
                tickHalfTime++;
                state.startSecondHalf();
                if (observer != null) observer.onTick(state.getSimulationTick(), state);
                totalTicks++;
                state.advanceMatchClock();
                state.advanceSimulationTick();
                continue;
            }

            // Handle kickoff
            if (state.isKickoffPending()) {
                tickKickoff++;
                restartManager.handleKickoff(stats);
                if (observer != null) observer.onTick(state.getSimulationTick(), state);
                totalTicks++;
                state.advanceMatchClock();
                state.advanceSimulationTick();
                continue;
            }

            // Handle celebration — scoring team players run toward the opponent's
            // goal while the clock counts down the brief hold. This mirrors the
            // SwingUI celebration where the scoring side streams forward to mob
            // the net after a goal. (corePrinciples §19: celebrations are visual
            // events, not football actions — no new decisions are made here.)
            if (state.isCelebrating()) {
                tickCelebration++;
                state.consumeCelebrationHoldTick();

                // Log once at the start of the celebration so the gap detector
                // (and the viewer timeline) knows why no football events occur
                // during the hold period.
                if (tickCelebration == 1) {
                    String celeTeam = state.getCelebratingTeam();
                    String scorer = state.getGoalCount() + "-" + state.getAwayGoalCount();
                    logger.logInfo(state, "GOAL CELEBRATION — " + celeTeam
                            + " celebrating (" + scorer + ") for "
                            + state.getCelebrationHoldTicks() + " ticks — scoring team advances toward goal",
                            "GOAL", null);
                }

                // CELEBRATION MOVEMENT: scoring-team outfield players advance
                // goalward at a walking pace. HOME attacks toward row 7, AWAY
                // toward row 1. The ball sits at the goal-exit position (row 8/0,
                // behind the line) — players do NOT need to reach it.
                String celebratingTeam = state.getCelebratingTeam();
                boolean homeScoring = "HOME".equals(celebratingTeam);
                double direction = homeScoring ? 1.0 : -1.0;
                double celebrateSpeed = MovementEngine.PLAYER_SPEED * 0.6; // half-pace walk
                for (Player cp : state.getPlayers()) {
                    if (!celebratingTeam.equals(cp.getTeam()) || "GK".equals(cp.getRole())
                            || cp.isLocked() || cp.isSentOff() || cp.isInjured()) continue;
                    // Skip the scorer who is already at the goal line (the goal
                    // mouth is at row 7 for HOME, row 1 for AWAY).
                    if (homeScoring && cp.getPosition().getRow() >= 6.8) continue;
                    if (!homeScoring && cp.getPosition().getRow() <= 1.2) continue;
                    double newRow = SimUtils.clamp(cp.getPosition().getRow()
                            + direction * celebrateSpeed, 0.5, 7.5);
                    cp.setPosition(new Position(newRow, cp.getPosition().getColumn()));
                    cp.setTarget(null);
                }

                recorder.captureSnapshot(state);
                if (observer != null) observer.onTick(state.getSimulationTick(), state);
                totalTicks++;
                state.advanceMatchClock();
                state.advanceSimulationTick();
                if (state.getCelebrationHoldTicks() <= 0) {
                    state.setCelebrating(false);
                    tickCelebration = 0;
                    state.setKickoffPending(true);
                    logger.logInfo(state, "GOAL CELEBRATION END — " + state.getCelebratingTeam()
                            + " returning to pitch — kickoff pending",
                            "GOAL", null);
                }
                continue;
            }

            // Handle action delay (skip during OOB hold — OOB has its own animation+delay logic)
            if (state.getActionDelayTicks() > 0 && !state.isBallOOBPending()) {
                tickDelay++;
                state.consumeActionDelayTick();
                ballMovementEngine.moveBallTowardCurrentTarget();
                if (observer != null) observer.onTick(state.getSimulationTick(), state);
                totalTicks++;
                state.advanceMatchClock();
                state.advanceSimulationTick();
                continue;
            }

            // Handle OOB: quickly move the ball to the boundary, hold it there for
            // the visible 4-second stoppage, then execute the restart.
            if (state.isBallOOBPending()) {
                Position ballTarget = state.getBall().getTarget();

                // Phase 1: Ball still has a target — animate it to OOB zone
                if (ballTarget != null) {
                    BallMovementEngine.moveBallToward(state.getBall(), ballTarget,
                            BallMovementEngine.BALL_SPEED * 10);
                    if (SimUtils.distance(state.getBall().getPosition(), ballTarget) <= BallMovementEngine.PICKUP_DISTANCE) {
                        state.getBall().setPosition(ballTarget);
                        state.getBall().setTarget(null);
                        // Ball reached OOB — start 4-second hold
                        state.setActionDelayTicks(MatchState.OOB_HOLD_TICKS);
                    }
                    // Log the ball out-of-bounds transit so the gap detector
                    // sees continuous activity during the ball's flight to the
                    // boundary line.
                    logger.logInfo(state, "BALL OUT: in transit to "
                            + state.getOobRestartType() + " at "
                            + String.format("(%.2f,%.2f)", ballTarget.getRow(), ballTarget.getColumn()),
                            "BALL_OUT", null);
                    recorder.captureSnapshot(state);
                    if (observer != null) observer.onTick(state.getSimulationTick(), state);
                    totalTicks++;
                    state.advanceMatchClock();
                    state.advanceSimulationTick();
                    continue;
                }

                // Phase 2: Ball at OOB position, delay in progress — players reposition
                if (state.getActionDelayTicks() > 0) {
                    int ticksRemaining = state.getActionDelayTicks();
                    state.consumeActionDelayTick();
                    // Log the hold so the gap detector sees a legitimate stoppage
                    // rather than a silent freeze. Log on first tick + every 3 ticks.
                    if (ticksRemaining == MatchState.OOB_HOLD_TICKS || ticksRemaining % 3 == 0) {
                        FootballRulesService.RestartType rt = state.getOobRestartType();
                        logger.logInfo(state, "BALL OUT hold — " + rt
                                + " " + ticksRemaining + " ticks remaining — players reorganizing", "BALL_OUT", null);
                    }
                    // A stoppage is not a frozen scene. Start a fresh movement
                    // budget so players can reorganize throughout the hold.
                    state.beginRound();
                    tacticalEngine.assignTargets();
                    movementEngine.moveAllTowardTargets();
                    recorder.captureSnapshot(state);
                    if (observer != null) observer.onTick(state.getSimulationTick(), state);
                    totalTicks++;
                    state.advanceMatchClock();
                    state.advanceSimulationTick();
                    continue;
                }

                // Phase 3: Hold complete — fire restart
                FootballRulesService.RestartType oobRestart = state.getOobRestartType();
                String oobTeam = state.getOobLastTouchTeam();
                Position oobPos = state.getOobRestartPosition();
                // A GOAL is never a valid OOB restart. Goals are awarded only in
                // handleShotArrival after the shot reaches the goal mouth.
                if (oobRestart == FootballRulesService.RestartType.GOAL) {
                    oobRestart = FootballRulesService.RestartType.GOAL_KICK;
                }
                state.clearBallOOBPending();
                logger.logInfo(state, "BALL OUT: " + oobRestart + " at " + oobPos
                        + " — restart sequence begins", "BALL_OUT");
                actionEngine.complete("BALL OUT: " + oobRestart);
                restartManager.handleBallOutOfBounds(stats, oobRestart, oobPos, oobTeam);
                if (observer != null) observer.onTick(state.getSimulationTick(), state);
                totalTicks++;
                state.advanceMatchClock();
                state.advanceSimulationTick();
                continue;
            }

            // Handle corner hold
            if (state.getCornerHoldTicks() > 0) {
                tickCornerHold++;
                state.consumeCornerHoldTick();
                if (observer != null) observer.onTick(state.getSimulationTick(), state);
                totalTicks++;
                state.advanceMatchClock();
                state.advanceSimulationTick();
                continue;
            }

            // No active action — need new decision
            if (!state.hasActiveAction()) {
                // Restart taker approaches the ball instead of being teleported to
                // the line. Do not let the generic loose-ball chase take over.
                if (state.isSetPiecePending() && state.getFreeKickTaker() != null
                        && state.getCarrier() == null) {
                    Player taker = state.getFreeKickTaker();
                    Position ballPos = state.getBall().getPosition();
                    taker.setTarget(ballPos);
                    state.beginRound();
                    // Push opponents back from the ball, move all other players toward
                    // their tactical targets, and walk the taker to the ball with the
                    // movement engine (corePrinciples §11 — no teleport). Teammates are
                    // NOT physical walls to the taker during a dead-ball restart, so we
                    // also nudge the taker straight to the ball so the crowd can never
                    // deadlock the set piece (the old freeze-on-teammate path caused
                    // constant "forced after walk timeout" teleports).
                    movementEngine.enforceRestartPushback();
                    movementEngine.moveAllTowardTargets();
                    Position takerPos = taker.getPosition();
                    double tx = ballPos.getRow() - takerPos.getRow();
                    double ty = ballPos.getColumn() - takerPos.getColumn();
                    double tdist = Math.hypot(tx, ty);
                    if (tdist > 1e-6) {
                        double step = Math.min(RESTART_WALK_SPEED, tdist);
                        Position moved = new Position(takerPos.getRow() + tx / tdist * step,
                                takerPos.getColumn() + ty / tdist * step);
                        // Only opponents (already pushbacked) present any obstacle; the
                        // taker ignores own teammates so it always reaches the spot.
                        boolean blockedByOpponent = false;
                        for (Player op : state.getPlayers()) {
                            if (op == taker || op.getTeam().equals(taker.getTeam())
                                    || op.isSentOff() || op.isInjured()) continue;
                            if (SimUtils.distance(moved, op.getPosition()) < 0.3) {
                                blockedByOpponent = true;
                                break;
                            }
                        }
                        if (!blockedByOpponent) {
                            taker.setPosition(new Position(
                                    SimUtils.clamp(moved.getRow(), 0.5, 7.5),
                                    SimUtils.clamp(moved.getColumn(), 0.5, 6.5)));
                            taker.setTarget(ballPos);
                        }
                    }
                    boolean arrived = SimUtils.distance(taker.getPosition(), ballPos)
                            <= BallMovementEngine.PICKUP_DISTANCE;
                    if (arrived || restartWalkTicks >= RESTART_WALK_MAX_TICKS) {
                        // Give the ball. The engine walks the taker to the ball via
                        // the movement engine (no teleport). If after the long
                        // RESTART_WALK_MAX_TICKS guard the taker is STILL genuinely
                        // collision-blocked at the spot, teleport him onto the ball
                        // as an absolute deadlock safeguard — set pieces must never
                        // freeze the match.
                        taker.setPosition(state.getBall().getPosition());
                        taker.setTarget(null);
                        state.getBall().setCarrier(taker);
                        state.setCarrier(taker);
                        state.setFreeKickTaker(null);
                        // The restart is complete before the taker's first
                        // decision. Normal offside rules must apply to that pass.
                        state.setSetPiecePending(false);
                        // First touch after the restart must NOT be a CARRY — the
                        // taker decides PASS/CENTER/SHOT/CLEAR only. Cleared after
                        // the next decision (see decision block below).
                        state.setRestartFirstTouch(true);
                        restartWalkTicks = 0;
                        logger.logInfo(state, "RESTART TAKEN by " + taker.getLabel()
                                + " at " + state.getBall().getPosition()
                                + (arrived ? "" : " (forced after walk timeout)"),
                                "RESTART", taker);
                        recorder.captureSnapshot(state);
                    } else {
                        restartWalkTicks++;
                        // Log periodically so the gap detector never sees a long
                        // silent stretch during the walk.
                        if (restartWalkTicks == 1 || (restartWalkTicks % 3 == 0)) {
                            logger.logInfo(state, "RESTART WALK — " + taker.getLabel()
                                    + " at (" + String.format("%.2f", taker.getPosition().getRow())
                                    + "," + String.format("%.2f", taker.getPosition().getColumn())
                                    + ") dist=" + String.format("%.2f",
                                    SimUtils.distance(taker.getPosition(), state.getBall().getPosition()))
                                    + " (max " + RESTART_WALK_MAX_TICKS + " ticks)",
                                    "RESTART", taker);
                        }
                        if (state.getFreeKickTaker() != null) {
                            recorder.captureSnapshot(state);
                            if (observer != null) observer.onTick(state.getSimulationTick(), state);
                            totalTicks++;
                            state.advanceMatchClock();
                            state.advanceSimulationTick();
                            continue;
                        }
                    }
                }
                if (state.getCarrier() != null) {
                    // Ball carrier makes a decision
                    state.beginRound();
                    tacticalEngine.assignTargets();
                    DecisionOption chosen = decisionEngine.decide();
                    DecisionType decision = chosen.getType();

                    // Record whether this decision (made by the holder of a held
                    // marginal-offside flag) ATTACKED — a shot or a forward pass.
                    // Per the user rule, marginal offside is only whistled when the
                    // deferred action advanced toward goal; harmless short/sideways
                    // continuations are let go without a whistle.
                    Player reviewDecisionMaker = state.getCarrier();
                    if (reviewDecisionMaker != null && state.isOffsideDeferred()
                            && state.hasPendingVARReview()
                            && reviewDecisionMaker == state.getPendingVARReviewPlayer()) {
                        state.setOffsideDeferredDecisionForward(
                                isAttackingForwardDecision(decision, chosen,
                                        reviewDecisionMaker, state));
                    }

                    logger.logDecision(state, chosen, decisionEngine.getLastScoredOptions(),
                            "DECISION [" + decisionEngine.getLastSelectionReason() + "]");
                    recordActionStats(state, logger, decision, state.getCarrier());

                    if (verbose && totalTicks < diagnosticCutoffTicks) {
                        printDiagnosticDecision(state, chosen, decisionEngine, rulesService);
                    }

                    executeDecision(decision, chosen, state, actionEngine, selection,
                            decisionEngine, stats,
                            logger, rulesService);

                    // Track consecutive carries: reset on non-CARRY decisions.
                    // Increment is handled in ActionEngine.start() to avoid double-counting.
                    Player decisionMaker = state.getCarrier();
                    if (decisionMaker != null) {
                        if (decision != DecisionType.CARRY) {
                            decisionMaker.resetConsecutiveCarries();
                        }
                    }

                    // Clear the kickoff-action flag after the decision has been processed.
                    state.setKickoffActionPending(false);
                    // Only clear setPiecePending AFTER the free kick taker has actually taken the kick
                    // (i.e., when the carrier is no longer the designated free kick taker).
                    if (state.isSetPiecePending() && state.getFreeKickTaker() != null) {
                        Player currentCarrier = state.getCarrier();
                        if (currentCarrier != null && currentCarrier != state.getFreeKickTaker()) {
                            state.setSetPiecePending(false);
                            state.setFreeKickTaker(null);
                        }
                    } else if (state.isSetPiecePending() && state.getFreeKickTaker() == null) {
                        state.setSetPiecePending(false);
                    }

                    // The restart first-touch restriction applies only to the FIRST
                    // decision after a restart. Once that decision is made, clear it
                    // so normal play resumes immediately after.
                    state.setRestartFirstTouch(false);

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
                    Position prevBallPos = ball.getPosition();
                    ballMovementEngine.moveBallTowardCurrentTarget();

                    // Mid-path lane collision: a ground pass/shot whose flight
                    // crosses an opponent's body DEFLECTS (faster ball) or is
                    // INTERCEPTED (slower ball — defender becomes carrier). It
                    // resolves the action mid-flight; complete()/giveBallTo clear
                    // the ball target so the rest of this tick's normal flow
                    // (movement, fatigue, snapshot) proceeds with the new state.
                    resolveMidPathCollision(state, state.getAction(), prevBallPos,
                            actionEngine, stats, logger, rulesService);

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
                    // Loose ball — check OOB first (deflected/missed balls can go past end lines)
                    // For loose balls, derive lastTouchTeam from which team was attacking:
                    // the ball went OOB, so determine restart based on ball position relative to goals
                    String looseLastTouch = state.getLastTouchTeam();
                    if (looseLastTouch == null) looseLastTouch = "HOME"; // fallback
                    FootballRulesService.RestartType looseRestart =
                            rulesService.determineRestart(ball.getPosition(), looseLastTouch, false);
                    if (looseRestart != FootballRulesService.RestartType.NONE) {
                        if (!state.isBallOOBPending()) {
                            state.setBallOOBPending(looseRestart, looseLastTouch, ball.getPosition());
                            state.setActionDelayTicks(MatchState.OOB_HOLD_TICKS);
                            actionEngine.complete("BALL OUT: " + looseRestart + " (holding)");
                        }
                        if (observer != null) observer.onTick(state.getSimulationTick(), state);
                        totalTicks++;
                        state.advanceMatchClock();
                        state.advanceSimulationTick();
                        continue;
                    }
                    // Find chasers (exclude GK — goalkeeper never chases)
                    Player closestHome = selection.closestOutfieldHomeTo(ball.getPosition());
                    Player closestAway = selection.closestOutfieldTeamTo(ball.getPosition(), "AWAY");
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
                    logger.logInfo(state, "CHASE timeout after " + chaseAction.getChaseTicks()
                            + " ticks — ball at (" + String.format("%.2f", ballPos.getRow())
                            + "," + String.format("%.2f", ballPos.getColumn()) + ")",
                            "CHASE", leadChaser);
                    actionEngine.resolveChaseTimeout();
                    if (observer != null) observer.onTick(state.getSimulationTick(), state);
                    totalTicks++;
                    state.advanceMatchClock();
                    state.advanceSimulationTick();
                    continue;
                }
                if (chaseAction.getChaseNoProgressTicks() >= ActionEngine.CHASE_NO_PROGRESS_TICKS) {
                    logger.logInfo(state, "CHASE no-progress timeout after " + chaseAction.getChaseNoProgressTicks()
                            + " ticks — ball at (" + String.format("%.2f", ballPos.getRow())
                            + "," + String.format("%.2f", ballPos.getColumn()) + ")",
                            "CHASE", leadChaser);
                    actionEngine.resolveChaseNoProgress();
                    if (observer != null) observer.onTick(state.getSimulationTick(), state);
                    totalTicks++;
                    state.advanceMatchClock();
                    state.advanceSimulationTick();
                    continue;
                }
                // Periodic CHASE progress log — first tick + every 3 ticks so the
                // gap detector never sees a silent stretch during pursuit.
                if ((chaseAction.getChaseTicks() == 1
                        || chaseAction.getChaseTicks() % 3 == 0) && leadChaser != null) {
                    Position chaseBallPos = state.getBall().getPosition();
                    logger.logInfo(state, "CHASE: " + leadChaser.getLabel()
                            + " pursuing ball at (" + String.format("%.2f", chaseBallPos.getRow())
                            + "," + String.format("%.2f", chaseBallPos.getColumn())
                            + ") dist=" + String.format("%.2f", leadDistance)
                            + " chaseTicks=" + chaseAction.getChaseTicks(),
                            "CHASE", leadChaser);
                } else if (chaseAction.getChaseTicks() == 1 && leadChaser == null) {
                    // CHASE started but acting player is null (no active chasers set).
                    // Log the ball position so the gap detector doesn't see a gap.
                    Position chaseBallPos = state.getBall().getPosition();
                    logger.logInfo(state, "CHASE: (no lead chaser) ball at ("
                            + String.format("%.2f", chaseBallPos.getRow())
                            + "," + String.format("%.2f", chaseBallPos.getColumn()) + ")",
                            "CHASE", null);
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
                Action actionBeforeCompletion = state.getAction();
                actionEngine.checkActionCompletion();
                if (actionBeforeCompletion != null && state.getAction() == null
                        && actionBeforeCompletion.getType() == ActionType.CARRY) {
                    logger.logActionOutcome(state, actionBeforeCompletion,
                            "CARRY_COMPLETED at " + state.getBall().getPosition(),
                            actionBeforeCompletion.getActingPlayer(), null, "OUTCOME");
                }
            }

            // Handle pass in flight arrival
            if (state.hasActiveAction() && state.getAction().isInFlight()) {
                handleInFlightArrival(state, actionEngine, stats,
                        logger, rulesService);
            }

            // A close offside is held live: the pass/attack continues. Once the
            // NEXT action completes we decide — if the move ended in a goal the
            // offside becomes a full VAR review (play keeps flowing during it),
            // otherwise a plain offside is whistled immediately.
            if (state.hasPendingVARReview() && state.isVARReviewActive()) {
                state.consumeVARDelayTick();
            }
            if (state.hasPendingVARReview() && !state.isVARReviewActive() && !state.hasActiveAction()) {
                offsideService.resolvePendingVAROffside(state, actionEngine);
            }

            // CARRY continuity guard — if the carrier was sent off or injured
            // mid-action (e.g. won a duel that resulted in a red card), hand the
            // ball off to the nearest eligible teammate instead of leaving the
            // ball orphaned with an unavailable carrier.
            if (state.hasActiveAction() && state.getAction().getType() == ActionType.CARRY) {
                Player carrier = state.getCarrier();
                if (carrier != null && (carrier.isSentOff() || carrier.isInjured())) {
                    Player teammate = selection.closestTeamTo(state.getBall().getPosition(),
                            carrier.getTeam(), carrier);
                    if (teammate != null) {
                        teammate.setPosition(state.getBall().getPosition());
                        state.getBall().setCarrier(teammate);
                        state.setCarrier(teammate);
                        actionEngine.complete("CARRY: carrier unavailable — ball to "
                                + teammate.getLabel());
                        tickCarryStuck = 0;
                        continue;
                    }
                }
            }

            // --- CARRY per-tick re-decision (user rule) ---
            // While a CARRY is in progress the carrier re-evaluates EVERY tick.
            // If a non-CARRY option now scores better (e.g. a passing lane opened
            // or an empty goal appeared), the current carry is treated as finished
            // and that new action is executed instead. This lets a dribbler react
            // to the defence closing in without locking the carry to its end.
            //
            // Safety guards (no infinite loop / no duel / no bug):
            //  - never re-decide while a duel is resolving or the carrier is
            //    blocked after a duel — those must complete first;
            //  - never re-decide during set pieces / celebration / VAR hold;
            //  - if the carrier still holds the ball and re-picks CARRY, nothing
            //    is restarted — the existing carry simply continues to its normal
            //    completion (ActionEngine CARRY target-reached path);
            //  - aborts are one-way: after executing a non-CARRY action a new
            //    active action exists, so this branch is not re-entered.
            if (state.hasActiveAction() && state.getAction().getType() == ActionType.CARRY) {
                Player c = state.getCarrier();
                boolean canReDecide = c != null
                        && !state.isSetPiecePending()
                        && !state.isCelebrating()
                        && !state.hasPendingVARReview()
                        && duelEngine.getActiveDuelAttacker() == null
                        && !state.isBlockedAfterDuel(c)
                        && state.getBall().getCarrier() == c;
                if (canReDecide) {
                    state.beginRound();
                    tacticalEngine.assignTargets();
                    DecisionOption reconsidered = decisionEngine.decide();
                    DecisionType newDecision = reconsidered.getType();
                    if (newDecision != DecisionType.CARRY) {
                        logger.logDecision(state, reconsidered, decisionEngine.getLastScoredOptions(),
                                "CARRY RE-DECIDED -> " + newDecision
                                        + " [" + decisionEngine.getLastSelectionReason() + "]");
                        actionEngine.complete("CARRY: " + c.getLabel() + " re-decided to " + newDecision);
                        executeDecision(newDecision, reconsidered, state, actionEngine, selection,
                                decisionEngine, stats, logger, rulesService);
                        if (state.hasActiveAction()) {
                            duelEngine.update(state.getAction());
                        }
                        state.setKickoffActionPending(false);
                        if (observer != null) observer.onTick(state.getSimulationTick(), state);
                        totalTicks++;
                        state.advanceMatchClock();
                        state.advanceSimulationTick();
                        continue;
                    }
                }
            }

            // Handle shot arrival
            if (state.hasActiveAction() && state.getAction().isShotInFlight()) {
                // Periodic shot-in-flight progress log every 3 ticks — fire BEFORE
                // handleShotArrival so the log is always emitted while the shot
                // is still in flight (handleShotArrival may resolve it).
                if (totalTicks % 3 == 0) {
                    Position shotBallPos = state.getBall().getPosition();
                    logger.logInfo(state, "SHOT in flight: ball at ("
                            + String.format("%.2f", shotBallPos.getRow())
                            + "," + String.format("%.2f", shotBallPos.getColumn())
                            + ") toward target", "SHOT", state.getCarrier());
                }
                handleShotArrival(state, actionEngine, selection, stats,
                        logger, rulesService);
            }

            // CARRY stuck guard — force-complete if carrier can't reach target
            if (state.hasActiveAction()
                    && state.getAction().getType() == ActionType.CARRY) {
                Player carrier = state.getCarrier();
                if (carrier != null && carrier.getTarget() != null) {
                    double distToTarget = SimUtils.distance(carrier.getPosition(),
                            carrier.getTarget());
                    if (distToTarget >= MovementEngine.PLAYER_SPEED * 2) {
                        tickCarryStuck++;
                    } else {
                        tickCarryStuck = 0;
                    }
                }
                // No hard carry-duration cap — the carrier always moves toward
                // target (MovementEngine guarantees carrier always moves). The
                // stuck guard only fires if the carrier truly cannot progress.
                if (tickCarryStuck >= CARRY_STUCK_MAX_TICKS) {
                    if (carrier != null) carrier.setTarget(null);
                    actionEngine.complete("CARRY: stuck timeout — forcing pass/shot");
                    tickCarryStuck = 0;
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
            // Tactical targets, including threat overrides, are refreshed on every
            // simulation tick so defenders cannot retain stale forward targets.
            tacticalEngine.refreshTargetsIfBallStateChanged();
            movementEngine.moveAllTowardTargets();

            // Periodic CARRY progress log — every 3 ticks while carrying, so the
            // gap detector never sees a 6+ second gap with zero log entries.
            // The carrier IS moving (MovementEngine guarantees it); this is purely
            // a heartbeat log.
            if (state.hasActiveAction()
                    && state.getAction().getType() == ActionType.CARRY
                    && state.getCarrier() != null
                    && state.getMatchTicks() % 3 == 0) {
                Player carryPlayer = state.getCarrier();
                double distToTarget = carryPlayer.getTarget() != null
                        ? SimUtils.distance(carryPlayer.getPosition(), carryPlayer.getTarget())
                        : -1;
                logger.logInfo(state, "CARRY progress: " + carryPlayer.getLabel()
                        + " at (" + String.format("%.2f", carryPlayer.getPosition().getRow())
                        + "," + String.format("%.2f", carryPlayer.getPosition().getColumn())
                        + ")" + (distToTarget >= 0
                        ? " distToTarget=" + String.format("%.2f", distToTarget) : ""),
                        "CARRY", carryPlayer);
            }

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
            if (observer != null) observer.onTick(state.getSimulationTick(), state);

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

    // Does a decision advance toward the opponent goal (a shot or a meaningful
    // forward pass)? Used to decide whether a held marginal-offside flag is
    // whistled: per the user rule, only a truly ATTACKING next action merits the
    // offside call — harmless short/sideways/backward continuations are let go.
    // A forward pass counts only if it gains real ground (advances >= 1 cell
    // into the opponent half), so a nudged-inches forward ball doesn't inflate
    // the offside count.
    private boolean isAttackingForwardDecision(DecisionType decision, DecisionOption chosen,
                                                Player carrier, MatchState state) {
        if (decision == DecisionType.SHOT
                || decision == DecisionType.THRU
                || decision == DecisionType.CROSS) {
            return true;
        }
        if (decision == DecisionType.PASS) {
            Player target = chosen.getTarget();
            if (target == null) return false;
            boolean home = "HOME".equals(carrier.getTeam());
            double carrierRow = carrier.getPosition().getRow();
            double targetRow = target.getPosition().getRow();
            if (!home && targetRow >= carrierRow) return false;
            if (home && targetRow <= carrierRow) return false;
            // Must advance meaningfully into the opponent half to count as an
            // attacking ball (HOME: row >= 5; AWAY: row <= 3), gaining >= 1 cell.
            double gain = Math.abs(targetRow - carrierRow);
            double finalThirdRow = home ? 5.0 : 3.0;
            boolean intoFinalThird = home ? targetRow >= finalThirdRow : targetRow <= finalThirdRow;
            return gain >= 1.0 && intoFinalThird;
        }
        return false;
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
                // Track which outfield teammates are currently in an offside
                // position at this forward-pass moment (threat override retreat).
                offsideService.trackOffsidePositions(state, team, carrier.getPosition());
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
                    OffsideService.OffsideResult offsideResult = offsideService.checkOffside(
                            receiver, carrier.getPosition(), state.getBall().getPosition(),
                            team, state, actionEngine);

                    if (offsideResult.wasChecked()) {
                        if (offsideResult.confirmed()) {
                            // Offside confirmed — free kick already awarded by offsideService
                            logger.logInfo(state, "OFFSIDE (VAR " + varService.getLastVARDecision() + "): "
                                    + receiver.getLabel() + " caught offside on pass from " + carrier.getLabel()
                                    + " (consecutive: " + receiver.getConsecutiveOffsideCount() + ")",
                                    "OFFSIDE", carrier);
                        } else {
                            // Offside held (deferred) — play continues. The decision
                            // (VAR only if this move ends in a goal, otherwise a plain
                            // offside) is made once the next action completes.
                            stats.onPassAttempt(team, carrier.getId());
                            actionEngine.executePassTo(receiver);
                        }
                    } else {
                        stats.onPassAttempt(team, carrier.getId());
                        receiver.resetConsecutiveOffside();
                        actionEngine.executePassTo(receiver);
                        decisionEngine.recordPassExchange(carrier.getId(), receiver.getId());
                    }
                } else {
                    actionEngine.executeClearance();
                    stats.onClearance(team);
                }
            }
            case THRU -> {
                offsideService.trackOffsidePositions(state, team, carrier.getPosition());
                Player runner = chosen.getTarget();
                if (runner != null && runner != carrier) {
                    Position passOrigin = carrier.getPosition();
                    Position ballPos = state.getBall().getPosition();

                    OffsideService.OffsideResult offsideResult = offsideService.checkOffside(
                            runner, passOrigin, ballPos, team, state, actionEngine);

                    if (offsideResult.wasChecked()) {
                        if (offsideResult.confirmed()) {
                            // Offside confirmed — free kick already awarded by offsideService
                            logger.logInfo(state, "OFFSIDE (VAR " + varService.getLastVARDecision() + "): "
                                    + runner.getLabel() + " caught offside on thru pass from " + carrier.getLabel()
                                    + " (consecutive: " + runner.getConsecutiveOffsideCount() + ")",
                                    "OFFSIDE", carrier);
                        } else {
                            // Offside held (deferred) — play continues.
                            stats.onPassAttempt(team, carrier.getId());
                            stats.onThruAttempt(team);
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
                if (chosen != null && chosen.isStraightLineCarry()) {
                    // Open-flank winger run: dribble STRAIGHT up the touchline
                    // (same column) until the last row.
                    actionEngine.executeStraightCarry();
                } else {
                    Position dest = new Position(
                            SimUtils.clamp(carrier.getPosition().getRow() + ("HOME".equals(carrier.getTeam()) ? 1 : -1) * 2, 1, 7),
                            SimUtils.clamp(carrier.getPosition().getColumn() + (state.getRandom().nextDouble() * 2 - 1), 1, 6));
                    actionEngine.executeCarry();
                }
            }
            case SHOT -> {
                boolean shotTaken = actionEngine.executeShot(chosen.isEmptyGoal());
                if (shotTaken) {
                    stats.onShot(team, carrier.getId(), false);
                } else {
                    // Shot blocked by defender — ball becomes loose, trigger chase
                    String defendingTeam = "HOME".equals(team) ? "AWAY" : "HOME";
                    stats.onBlock(defendingTeam);
                    logger.logInfo(state, "SHOT BLOCKED by defender near " + carrier.getLabel(), "BLOCK", carrier);

                    // Blocked shot near the end line often deflects over the goal line → corner
                    Position shotPos = carrier.getPosition();
                    double distToEndLine = "HOME".equals(team)
                            ? 7.5 - shotPos.getRow() : shotPos.getRow() - 0.5;
                    if (distToEndLine <= 1.5 && state.getRandom().nextDouble() < 0.60) {
                        stats.onCornerFromPass();
                        Position cornerPos = new Position("HOME".equals(team) ? 7.5 : 0.5,
                                SimUtils.clamp(shotPos.getColumn(), 1, 6));
                        state.getBall().setCarrier(null);
                        state.getBall().setTarget(cornerPos);
                        state.setBallOOBPending(FootballRulesService.RestartType.CORNER, defendingTeam, cornerPos);
                        actionEngine.complete("BLOCKED SHOT -> CORNER (holding)");
                        return;
                    }
                }
            }
            case CROSS -> {
                stats.onPassAttempt(team, carrier.getId());
                actionEngine.executeCross();
            }
            case CENTER -> {
                offsideService.trackOffsidePositions(state, team, carrier.getPosition());
                stats.onPassAttempt(team, carrier.getId());
                Player receiver = actionEngine.selectCenterTarget();
                if (receiver != null) {
                    OffsideService.OffsideResult offsideResult = offsideService.checkOffside(
                            receiver, carrier.getPosition(), state.getBall().getPosition(),
                            team, state, actionEngine);
                    if (offsideResult.confirmed()) {
                        logger.logInfo(state, "OFFSIDE: " + receiver.getLabel()
                                + " caught offside on CENTER from " + carrier.getLabel(),
                                "OFFSIDE", carrier);
                        return;
                    }
                }
                actionEngine.executeCenter();
            }
            case CLEAR -> { actionEngine.executeClearance(); stats.onClearance(team); }
            default -> { actionEngine.executeClearance(); stats.onClearance(team); }
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

        // The ball has already moved once in the main loop's flight branch
        // (moveBallTowardCurrentTarget + resolveMidPathCollision). This second
        // move covers the remaining distance toward the target. After it, if
        // the ball hasn't arrived yet, we must check for mid-path collisions
        // on THIS segment too — otherwise a defender standing directly on the
        // ball's path during the arrival-handler's movement passes through
        // undetected.
        Position secondMoveStart = ball.getPosition();
        BallMovementEngine.moveBallToward(ball, target, BallMovementEngine.BALL_SPEED);

        // Mid-path lane collision on the second half of this tick's ball movement.
        // Only checks if the ball is still in flight (not yet at the target).
        // THRU passes are excluded — per the existing main-loop design, THRU
        // passes skip mid-path collision/duel checks so the runner can run onto
        // the ball; interception of THRU passes is handled at arrival time via
        // findPassInterceptor only.
        boolean isThruInFlight = state.hasActiveAction()
                && state.getAction().getPassLength() == PassLength.THRU
                && state.getAction().isPassInFlight();
        if (!isThruInFlight
                && SimUtils.distance(ball.getPosition(), target) > BallMovementEngine.PICKUP_DISTANCE
                && state.hasActiveAction() && state.getAction().isInFlight()) {
            if (resolveMidPathCollision(state, state.getAction(), secondMoveStart,
                    actionEngine, stats, logger, rulesService)) {
                return; // Action resolved (deflected or intercepted) — skip arrival checks
            }
        }

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

            // Check ball out-of-bounds FIRST, but for crosses/passes: check deflection
            // BEFORE OOB — a deflected ball near the end line should become a corner,
            // not a goal kick. Deflection must run before OOB detection.
            String lastTouchTeam = action.getActingPlayer() != null
                    ? action.getActingPlayer().getTeam() : "HOME";

            if (action.isPassInFlight() || action.isCrossInFlight()) {
                Player receiver = action.getTargetPlayer();

                // Deflection check: ball hits a nearby defender's body/foot.
                Player deflector = findPassDeflector(action, ball.getPosition(), state);
                if (deflector != null) {
                    applyDeflection(state, action, deflector, ball.getPosition(),
                            actionEngine, stats, logger, rulesService);
                    return;
                }

                // Interception check
                Player interceptor = findPassInterceptor(action, ball.getPosition(), state);
                if (interceptor != null && (receiver == null
                        || SimUtils.distance(interceptor.getPosition(), ball.getPosition())
                            < SimUtils.distance(receiver.getPosition(), ball.getPosition()))) {
                    applyInterception(state, action, interceptor, actionEngine, stats, logger);
                    return;
                }
            }

            // NOW check ball out-of-bounds (after deflection/interception checks)
            FootballRulesService.RestartType restart =
                    rulesService.determineRestart(ball.getPosition(), lastTouchTeam, false);
            if (restart != FootballRulesService.RestartType.NONE) {
                if (action.getTargetPlayer() != null) action.getTargetPlayer().setLocked(false);
                if (restart == FootballRulesService.RestartType.CORNER) stats.onCornerFromPass();
                stats.onPassOutOfBounds();
                // Set OOB hold — ball stays at OOB position for 4 sec before restart
                Position oobPos = ball.getPosition();
                state.setBallOOBPending(restart, lastTouchTeam, oobPos);
                state.setActionDelayTicks(MatchState.OOB_HOLD_TICKS);
                logger.logActionOutcome(state, action, "BALL_OUT: " + restart
                        + " at " + oobPos, action.getActingPlayer(), action.getTargetPlayer(), "OUTCOME");
                actionEngine.complete("BALL OUT: " + restart + " (holding)");
                return;
            }

            // For passes that arrive OOB-free: handle receiver arrival
            if (action.isPassInFlight() || action.isCrossInFlight()) {
                Player receiver = action.getTargetPlayer();

                if (receiver != null) {
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
                                    PlayerSelectionEngine selection,
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
        if (onTarget) {
            stats.markShotOnTarget(shooterTeam, shooter.getId());
        }
        logger.logActionExecution(state, action,
                "SHOT_ARRIVAL distToGoal=" + String.format("%.2f", distToGoal) + " onTarget=" + onTarget,
                shooter, null, "SHOT");

        if (distToGoal < ExecutionQuality.SHOT_GOAL_THRESHOLD) {
            // Check for goalkeeper save
            String keeperTeam = "HOME".equals(shooterTeam) ? "AWAY" : "HOME";
            Player keeper = selection.anyGoalkeeper(keeperTeam);
            if (keeper != null) {
                // Save chance is driven primarily by whether the keeper actually sits
                // in the shot lane (gkInLane): a keeper out of the lane / not in front
                // of the ball is effectively an open-goal finish — the shot must be a
                // goal, NOT a save (that was the bug: an out-of-lane keeper still had a
                // ~37% save chance). Random only tips a near-50/50 look one way; it can
                // never turn a clear open-goal effort into a save.
                double gkInLane = Math.max(0.05, action.getGkInLane());
                double strikerSkill = action.getSkill();
                double keeperDist = SimUtils.distance(keeper.getPosition(), ball.getPosition());

                // Save chance is dominated by TWO things: (a) is the keeper actually
                // in the shot lane (between ball and goal) and (b) how good the
                // keeper is. A well-set keeper in the lane is a heavy favourite to
                // save an on-frame shot (~70%); a keeper out of the lane can't save
                // (open goal — the goal is really decided here, NOT by random).
                double inLane = gkInLane;
                // A keeper in the shot lane is a clear favourite to save an on-frame
                // shot; only a beaten / out-of-lane keeper concedes it (open goal).
                double positionFactor = 0.22 + 0.85 * inLane;
                double keeperFactor = 0.74 + keeper.getSkills().keeper() / 20.0 * 0.40;
                double strikeFactor = 1.0 - strikerSkill / 20.0 * 0.24;
                double reach = Math.max(0.62, 1.0 - Math.max(0.0, keeperDist - 1.2) * 0.32);
                double saveChance = SimUtils.clamp(
                        positionFactor * keeperFactor * strikeFactor * reach, 0.03, 0.92);
                // Save cooldown: a keeper who just saved (within a few ticks, ~2s) is
                // off-balance and cannot make another save — the rebound is open.
                int sinceSave = state.getMatchTicks() - keeper.getLastSaveTick();
                if (sinceSave < GoalkeeperMovementEngine.SAVE_COOLDOWN_TICKS) {
                    saveChance *= 0.15;
                }
                saveChance = SimUtils.clamp(saveChance, 0.03, 0.90);

                if (state.getRandom().nextDouble() < saveChance) {
                    actionEngine.shotSaved(keeper);
                    stats.onSave("HOME".equals(shooterTeam) ? "AWAY" : "HOME");
                    logger.logActionOutcome(state, action,
                            "SAVE by " + keeper.getLabel()
                                    + " (keeper skill=" + String.format("%.0f", keeper.getSkills().keeper()) + ")",
                            keeper, shooter, "OUTCOME");

                    // Handle save rebound immediately (corner or loose ball)
                    if (action.getSaveType() == Action.SaveType.CORNER_REBOUND) {
                        String defendingTeam = "HOME".equals(shooterTeam) ? "AWAY" : "HOME";
                        stats.onCorner(defendingTeam);
                        Position cornerPos = new Position("HOME".equals(shooterTeam) ? 7.5 : 0.5,
                                SimUtils.clamp(ball.getPosition().getColumn(), 1, 6));
                        state.getBall().setTarget(cornerPos);
                        state.setBallOOBPending(FootballRulesService.RestartType.CORNER, defendingTeam, cornerPos);
                        state.setActionDelayTicks(MatchState.OOB_HOLD_TICKS);
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

            // If a marginal offside flag was held on the shooter, this goal is the
            // ONLY trigger for a VAR offside review: was the shooter offside?
            if (state.hasPendingVARReview()) {
                boolean goalStands = offsideService.resolveOffsideVAROnGoal(
                        state, shooter, shooterTeam, actionEngine);
                if (!goalStands) {
                    // VAR CONFIRMED offside — goal disallowed, indirect free kick for
                    // the defending team already awarded by offsideService.
                    return;
                }
            }

            if (!goalConfirmed) {
                // VAR overturned the goal — ball goes OOB, hold, then goal kick
                logger.logInfo(state, "VAR OVERTURNED GOAL: " + shooter.getLabel()
                        + " — goal disallowed (" + varService.getLastVARDecision() + ")",
                        "VAR", shooter);
                actionEngine.shotMissed();
                Position oobEndpoint;
                if (goal.getRow() == 7.0) {
                    oobEndpoint = new Position(8.5, SimUtils.clamp(ball.getPosition().getColumn(), -0.5, 8.5));
                } else {
                    oobEndpoint = new Position(-0.5, SimUtils.clamp(ball.getPosition().getColumn(), -0.5, 8.5));
                }
                state.getBall().setCarrier(null);
                state.getBall().setTarget(oobEndpoint);
                // The shooter touched the ball last — the goal kick goes to the
                // defending team. Pass shooterTeam as lastTouchTeam so
                // handleBallOutOfBounds awards the restart to the OTHER team.
                state.setBallOOBPending(FootballRulesService.RestartType.GOAL_KICK, shooterTeam, oobEndpoint);
                state.setActionDelayTicks(MatchState.OOB_HOLD_TICKS);
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
            // Shot missed — the ball flies over the end line. Per the football
            // rules, a plain shot miss is NEVER a corner (the attacker played the
            // ball over the line). It becomes a GOAL_KICK for the defending team.
            // A corner arises ONLY when: (a) the keeper saves and pushes it behind
            // the line, or (b) the ball actually deflects off a defender behind
            // the line — both of which are handled at the point of contact, not here.
            actionEngine.shotMissed();
            logger.logActionOutcome(state, action,
                    "MISS (distToGoal=" + String.format("%.2f", distToGoal) + ")",
                    shooter, null, "OUTCOME");

            // Shot miss — ball needs to travel to OOB position, then hold 4 sec, then goal kick
            // The shooter touched the ball last: the goal kick must go to the
            // DEFENDING team. Pass shooterTeam as lastTouchTeam so
            // handleBallOutOfBounds awards the restart to the other team.
            // Determine OOB endpoint based on shot direction
            Position oobEndpoint;
            if (goal.getRow() == 7.0) {
                oobEndpoint = new Position(8.5, SimUtils.clamp(shotTarget.getColumn(), -0.5, 8.5));
            } else {
                oobEndpoint = new Position(-0.5, SimUtils.clamp(shotTarget.getColumn(), -0.5, 8.5));
            }
            state.getBall().setCarrier(null);
            state.getBall().setTarget(oobEndpoint);
            FootballRulesService.RestartType missRestart =
                    rulesService.determineRestart(oobEndpoint, shooterTeam, false);
            if (missRestart == FootballRulesService.RestartType.NONE) {
                missRestart = FootballRulesService.RestartType.GOAL_KICK;
            }
            state.setBallOOBPending(missRestart, shooterTeam, oobEndpoint);
            state.setActionDelayTicks(MatchState.OOB_HOLD_TICKS);
        }
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
        boolean hadAction = state.hasActiveAction();
        actionEngine.checkActionCompletion();
        if (hadAction && !state.hasActiveAction()) {
            // Chase was silently completed — log it so the gap detector sees the
            // resolution rather than a long silent stretch.
            Player winner = selection.closestEligibleActiveChaser(ballPos);
            if (winner != null && SimUtils.distance(winner.getPosition(), ballPos) <= ActionEngine.POSSESSION_RADIUS) {
                logger.logInfo(state, "CHASE resolved: " + winner.getLabel()
                        + " reached ball at (" + String.format("%.2f", ballPos.getRow())
                        + "," + String.format("%.2f", ballPos.getColumn()) + ")",
                        "CHASE", winner);
            } else {
                logger.logInfo(state, "CHASE resolved: ball at ("
                        + String.format("%.2f", ballPos.getRow())
                        + "," + String.format("%.2f", ballPos.getColumn()) + ")",
                        "CHASE", null);
            }
        }
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
        if (action != null) {
            logger.logActionOutcome(state, action,
                    duelType + " -> " + result.winner().getLabel()
                            + " won (" + result.outcome() + ")",
                    attacker, defender, "OUTCOME");
        }

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
        // Track shot blocks by outfield defenders (not GK saves — those are tracked separately)
        if (duelType == DuelType.SHOT && result.outcome() == DuelOutcome.DEFENDER_WINS
                && !"GK".equals(defender.getRole())) {
            stats.onBlock(defender.getTeam());

            // Blocked shots near the end line often deflect over the goal line → corner
            // Real football: ~60% of close-range blocks result in corners
            Position shotPos = attacker.getPosition();
            double distToEndLine = "HOME".equals(attacker.getTeam())
                    ? 7.5 - shotPos.getRow() : shotPos.getRow() - 0.5;
            if (distToEndLine <= 1.5 && state.getRandom().nextDouble() < 0.60) {
                String defendingTeam = "HOME".equals(attacker.getTeam()) ? "AWAY" : "HOME";
                stats.onCornerFromPass();
                Position cornerPos = new Position("HOME".equals(defender.getTeam()) ? 7.5 : 0.5,
                        SimUtils.clamp(shotPos.getColumn(), 1, 6));
                state.getBall().setCarrier(null);
                state.getBall().setTarget(cornerPos);
                state.setBallOOBPending(FootballRulesService.RestartType.CORNER, defender.getTeam(), cornerPos);
                actionEngine.complete("BLOCKED SHOT -> CORNER (holding)");
                return;
            }
        }

        if (result.outcome() == DuelOutcome.DEFENDER_WINS) {
            // Delegate foul/card/VAR/penalty logic to DisciplineService
            // hadDuel=true: a duel was just actively resolved this tick (line 514),
            // so card/yVAR logic is eligible to fire.
            DisciplineService.DisciplineResult discResult =
                    disciplineService.evaluateFoul(state, actionEngine, attacker, defender, duelType, true);

            // If a penalty was awarded, execute the penalty kick
            if (discResult.penaltyAwarded() && discResult.penaltyTaker() != null) {
                executePenaltyFromFoul(state, actionEngine, stats, logger, selection,
                        discResult.penaltyTaker(), defender);
            }
        } else {
            // Attacker won the challenge
            if (duelType == DuelType.SHOT) {
                logger.logInfo(state, "Shot duel won by " + attacker.getLabel()
                        + " — shot continues toward goal", "DUEL", attacker);
            } else if (duelType == DuelType.DRIBBLE) {
                actionEngine.giveBallTo(attacker, "dribble won past " + defender.getLabel());
            } else if (duelType == DuelType.RECEIVE_PASS) {
                actionEngine.giveBallTo(attacker, "contested catch won");
            } else if (duelType == DuelType.CHASE_BALL) {
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
     * Find a defender who can INTERCEPT a pass — NAMERNO presecanje.
     * 
     * Interception requires:
     * 1. HIGH playmaking (≥12) — defender VIDI da će pas ići tamo (čita igru)
     * 2. HIGH defending (≥12) — defender ima veštinu da stigne do lopte
     * 3. Blizu putanje lopte — mora biti u blizini
     * 4. Brzina lopte — sporija lopta = lakše presecanje
     * 
     * Ako defanzivac NEMA visok plej, to NIJE interception — to je DEFLECTION.
     */
    /**
     * Apply a pass/shot deflection. The ball is placed at the collision point,
     * re-routed perpendicular to the lane, and becomes LOOSE (never a carrier).
     * Shared by the arrival-time check and the mid-path lane collision.
     */
    private void applyDeflection(MatchState state, Action action, Player deflector,
                                 Position collisionPoint,
                                 ActionEngine actionEngine, MatchStatsCollector stats,
                                 ActionLogService logger, FootballRulesService rulesService) {
        Ball ball = state.getBall();
        Player receiver = action.getTargetPlayer();
        if (receiver != null) receiver.setLocked(false);

        // Do NOT count deflections as loose balls — the CHASE that follows
        // usually recovers the ball immediately. Only count genuine
        // unrecoverable loose balls (pass missed receiver, save rebound that
        // nobody reaches, etc.).

        ball.setPosition(collisionPoint);
        Position currentPos = ball.getPosition();
        Position deflectorPos = deflector.getPosition();
        double dx = currentPos.getColumn() - deflectorPos.getColumn();
        double dy = currentPos.getRow() - deflectorPos.getRow();
        double dist = Math.hypot(dx, dy);
        if (dist < 1e-6) { dx = 1; dy = 0; dist = 1; }

        double perpRow, perpCol;
        if (state.getRandom().nextBoolean()) {
            perpRow = -dx / dist;
            perpCol = dy / dist;
        } else {
            perpRow = dx / dist;
            perpCol = -dy / dist;
        }

        double deflectionDist = 0.8 + action.getPassSpeed() * 0.3;
        Position deflectedPos = new Position(
                currentPos.getRow() + perpRow * deflectionDist,
                SimUtils.clamp(currentPos.getColumn() + perpCol * deflectionDist, 0.2, 7.8));

        double newSpeed = action.getPassSpeed() * (0.5 + state.getRandom().nextDouble() * 0.2);
        action.setPassSpeed(newSpeed);

        ball.setPosition(deflectedPos);
        ball.setCarrier(null);
        ball.setTarget(null);
        state.setCarrier(null);
        state.setLastTouchTeam(deflector.getTeam());
        actionEngine.complete("DEFLECTED by " + deflector.getLabel());
        stats.onDeflection(deflector.getTeam());
        logger.logActionOutcome(state, action,
                "DEFLECTED by " + deflector.getLabel() + " — ball loose (slowed to "
                        + String.format("%.1f", newSpeed) + " cells/tick)",
                action.getActingPlayer(), deflector, "OUTCOME");

        // Check if deflected ball is OOB (corner, goal kick, throw-in)
        FootballRulesService.RestartType deflRestart =
                rulesService.determineRestart(deflectedPos, deflector.getTeam(), false);
        if (deflRestart != FootballRulesService.RestartType.NONE) {
            if (action.getTargetPlayer() != null) action.getTargetPlayer().setLocked(false);
            if (deflRestart == FootballRulesService.RestartType.CORNER) stats.onCornerFromPass();
            stats.onPassOutOfBounds();
            // Set OOB hold — ball stays at deflected position for 4 sec
            state.setBallOOBPending(deflRestart, deflector.getTeam(), deflectedPos);
            state.setActionDelayTicks(MatchState.OOB_HOLD_TICKS);
            logger.logActionOutcome(state, action,
                    "BALL_OUT after deflection: " + deflRestart,
                    action.getActingPlayer(), deflector, "OUTCOME");
            actionEngine.complete("DEFLECTION -> BALL OUT: " + deflRestart + " (holding)");
        }
    }

    /**
     * Apply an interception: the defender controls the ball and becomes carrier.
     * Shared by the arrival-time check and the mid-path lane collision.
     */
    private void applyInterception(MatchState state, Action action, Player interceptor,
                                   ActionEngine actionEngine, MatchStatsCollector stats,
                                   ActionLogService logger) {
        Player receiver = action.getTargetPlayer();
        if (receiver != null) receiver.setLocked(false);
        state.setLastTouchTeam(interceptor.getTeam());
        actionEngine.giveBallTo(interceptor, "intercepted pass");
        stats.onInterception(interceptor.getId());
        logger.logActionOutcome(state, action,
                "INTERCEPTED by " + interceptor.getLabel(),
                interceptor, action.getActingPlayer(), "OUTCOME");
    }

    /**
     * Mid-path lane collision. Runs DURING flight — if the ball's current
     * segment (prevPos → currentPos) passes within a defender's body radius,
     * the ball physically hits him and must either DEFLECT (faster ball → the
     * defender cannot control it, ball bounces loose) or be INTERCEPTED (slower
     * ball → the defender controls it and becomes carrier). Returns true if the
     * action was resolved by a collision.
     *
     * Sky passes (PassHeight.AIR) do not collide on the ground — they are only
     * contested at arrival. Crosses/centers are treated as passes here.
     */
    private boolean resolveMidPathCollision(MatchState state, Action action, Position prevPos,
                                            ActionEngine actionEngine, MatchStatsCollector stats,
                                            ActionLogService logger, FootballRulesService rulesService) {
        if (action == null) return false;
        if (!(action.isPassInFlight() || action.isCrossInFlight() || action.isShotInFlight())) return false;

        PassHeight height = action.getPassHeight() != null ? action.getPassHeight() : PassHeight.GROUND;
        if (height == PassHeight.AIR) return false;

        Player passer = action.getActingPlayer();
        if (passer == null) return false;
        String passingTeam = passer.getTeam();
        Player receiver = (action.isPassInFlight() || action.isCrossInFlight())
                ? action.getTargetPlayer() : null;

        Ball ball = state.getBall();
        Position cur = ball.getPosition();
        double passSpeed = action.getPassSpeed();

        // Contact + reaction zone around the flight segment: any opponent whose
        // body is within this radius of the pass lane can be struck ("udario u
        // igrača"). Wider than a literal body so that reactions/blocked lanes
        // register, but the probability ramps down toward the edge.
        double collisionRadius = 1.2;

        Player hit = null;
        double hitDist = Double.MAX_VALUE;
        Position hitPoint = null;
        for (Player p : state.getPlayers()) {
            if (p.getTeam().equals(passingTeam)) continue;
            if (p == receiver) continue;
            if ("GK".equals(p.getRole())) continue;
            if (p.isLocked() || p.isSentOff() || p.isInjured()) continue;
            double d = SimUtils.pointSegmentDistance(p.getPosition(), prevPos, cur);
            if (d <= collisionRadius && d < hitDist) {
                hit = p;
                hitDist = d;
                hitPoint = SimUtils.closestPointOnSegment(p.getPosition(), prevPos, cur);
            }
        }
        if (hit == null) return false;

        // Contact probability ramps with closeness to the lane (closer = harder
        // to avoid) and rises slightly with ball speed (fast balls are harder
        // to dodge). A defender exactly on the lane is struck almost every time.
        double proximity = Math.max(0.0, 1.0 - (hitDist / collisionRadius)); // 0 (edge) .. 1 (on lane)
        double contactProb = 0.30 + 0.70 * proximity;                        // 0.30 .. 1.00
        contactProb *= (0.85 + 0.15 * passSpeed / 3.0);
        if (state.getRandom().nextDouble() >= contactProb) return false;

        // Deflection vs interception driven by ball speed (user rule):
        //   fast ball -> DEFLECTION (hard to read AND hard to control — bounces off)
        //   slow ball -> INTERCEPTION (defender reads it and takes possession)
        // passSpeed 1.0 → ~15% deflect / ~85% intercept
        // passSpeed 2.0 → ~38% deflect / ~62% intercept
        // passSpeed 3.0 → ~60% deflect / ~40% intercept
        double deflectProb = 0.15 + (passSpeed - 1.0) / 2.0 * 0.45; // 0.15 .. 0.60
        boolean deflect = state.getRandom().nextDouble() < deflectProb;

        if (deflect) {
            applyDeflection(state, action, hit, hitPoint, actionEngine, stats, logger, rulesService);
        } else {
            applyInterception(state, action, hit, actionEngine, stats, logger);
        }
        return true;
    }

    private Player findPassInterceptor(Action action, Position ballPos, MatchState state) {
        if (action.getActingPlayer() == null) return null;
        String passingTeam = action.getActingPlayer().getTeam();
        PassHeight passHeight = action.getPassHeight() != null ? action.getPassHeight() : PassHeight.GROUND;
        double passSpeed = action.getPassSpeed(); // 1.0 to 3.0

        // Speed modifier: sporija lopta = više vremena za reakciju = lakše presecanje
        // Brza lopta (3.0) → modifier 0.2, Spora lopta (1.0) → modifier 1.0
        double speedModifier = Math.max(0.2, 1.0 - (passSpeed - 1.0) / 2.5);

        // AIR passes: interceptor must be closer — ball is in the air
        double interceptRadius = passHeight == PassHeight.AIR ? 0.6 : 0.8;

        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (Player p : state.getPlayers()) {
            if (p.getTeam().equals(passingTeam)) continue;
            if ("GK".equals(p.getRole())) continue;
            if (p.isLocked() || p.isSentOff() || p.isInjured()) continue;

            double dist = SimUtils.distance(p.getPosition(), ballPos);
            if (dist >= interceptRadius) continue;

            // KLJUČNO: interception zahteva VISOK plej + VISOK def
            // Igrač mora da VIDI pas (playmaking ≥ 12) i da IMA VEŠTINU da ga preseče (defending ≥ 12)
            double playmaking = p.getSkills().playmaking();
            double defending = p.getSkills().defender();

            // Samo igrači sa visokim plejom i defom mogu NAMERNO da presecaju
            if (playmaking < 12 || defending < 12) continue;

            // Interception chance = (plej + def) / 40 * speedModifier
            // Max: (20+20)/40 * 1.0 = 100% za idealne uslove (spora lopta, blizu)
            // Realno: ~15-25% za dobre igrače
            double interceptChance = (playmaking + defending) / 40.0 * speedModifier;

            // AIR passes: need aerial skill too
            if (passHeight == PassHeight.AIR) {
                interceptChance *= (p.getSkills().technique() / 20.0);
            }

            if (state.getRandom().nextDouble() < interceptChance && dist < bestDist) {
                best = p;
                bestDist = dist;
            }
        }
        return best;
    }

    /**
     * Find a defender who DEFLECTS a pass — SLUČAJNI kontakt.
     * 
     * Deflection = lopta udara u defanzivca koji NIJE namerno išao da preseče.
     * Ovo se dešava kada:
     * 1. Defanzivac je blizu putanje lopte
     * 2. Lopta je dovoljno spora da je moguće zakačiti
     * 3. Defanzivac NEMA visok plej za interception (ili nije blizu dovoljno)
     * 
     * Efekat: lopta menja smer, usporava, postaje loose. NE dobija posed.
     * Brzina lopte utiče na verovatnoću: brza lopta = manja deflection (teže zakačiti)
     */
    private Player findPassDeflector(Action action, Position ballPos, MatchState state) {
        if (action.getActingPlayer() == null) return null;
        String passingTeam = action.getActingPlayer().getTeam();
        PassHeight passHeight = action.getPassHeight() != null ? action.getPassHeight() : PassHeight.GROUND;
        double passSpeed = action.getPassSpeed();

        // Deflection radius: veći za sporije lopte (više vremena za telo)
        // Brza lopta (3.0) → 0.3 ćelije, Spora lopta (1.0) → 0.6 ćelija
        double deflectionRadius = 0.3 + (1.0 - (passSpeed - 1.0) / 2.0) * 0.3;
        if (passHeight == PassHeight.AIR) deflectionRadius += 0.15; // AIR: veći radijus

        for (Player p : state.getPlayers()) {
            if (p.getTeam().equals(passingTeam)) continue;
            if ("GK".equals(p.getRole())) continue;
            if (p.isLocked() || p.isSentOff() || p.isInjured()) continue;

            double dist = SimUtils.distance(p.getPosition(), ballPos);
            if (dist >= deflectionRadius) continue;

            // Deflection chance: zavisi od brzine lopte i pozicije defanzivca
            // Brza lopta = manja šansa (teže zakačiti), Spora = veća šansa
            double baseChance;
            if (passHeight == PassHeight.AIR) {
                baseChance = 0.15; // AIR: lopta je viša, više kontakta sa telom
            } else {
                baseChance = 0.08; // GROUND: lopta je niza, teže zakačiti
            }

            // Speed modifier: brza lopta = manja šansa za deflection
            double speedMod = Math.max(0.3, 1.0 - (passSpeed - 1.0) / 2.0);
            double deflectionChance = baseChance * speedMod;

            if (state.getRandom().nextDouble() < deflectionChance) {
                return p;
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

            // If the injured player was the carrier, transfer the ball to the
            // substitute so the ball is never orphaned with an unavailable player.
            if (state.getCarrier() == p) {
                Position ballPos = state.getBall().getPosition();
                substitute.setPosition(ballPos);
                state.getBall().setCarrier(substitute);
                state.setCarrier(substitute);
            }

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
