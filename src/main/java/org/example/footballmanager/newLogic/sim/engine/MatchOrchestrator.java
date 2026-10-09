package org.example.footballmanager.newLogic.sim.engine;

import org.example.footballmanager.newLogic.sim.engine.decision.CleanDecisionEngine;
import org.example.footballmanager.newLogic.sim.model.*;
import org.example.footballmanager.newLogic.sim.recording.MatchRecorder;
import org.example.footballmanager.newLogic.sim.result.ProposalMatchOutcome;
import org.example.footballmanager.newLogic.sim.result.ProposalMatchOutcomeBuilder;
import org.example.footballmanager.newLogic.sim.result.ProposalStatsCollector;
import org.example.footballmanager.newLogic.sim.restarts.RestartManager;
import org.example.footballmanager.newLogic.sim.rules.OffsideService;
import org.example.footballmanager.newLogic.sim.engine.EngineInterfaces.OffsideService.OffsideResult;
import org.example.footballmanager.newLogic.sim.rules.VARService;
import org.example.footballmanager.newLogic.sim.tactics.SideTactics;
import org.example.footballmanager.newLogic.sim.tactics.TacticsRules;
import org.example.footballmanager.newLogic.sim.util.SimUtils;
import org.example.footballmanager.newLogic.sim.util.SimulationRandom;

import java.util.ArrayList;
import java.util.List;

/**
 * Match Orchestrator — coordinates all engine calls within a single tick.
 * Orchestration is NOT an engine — it just calls engines in order.
 *
 * Tick flow (NEW):
 *   1. Advance match clock (MatchClockService)
 *   2. Unlock duel losers
 *   3. VAR check — can we re-decide?
 *   4. Ball physics step (moves ball, handles collisions, goal, OOB hold)
 *   5. Handle ball physics result (RECEIVE/INTERCEPT/BLOCK/GOAL/RESTART/etc.)
 *   6. Decision engine — what to do (only if carrier and not in flight)
 *   7. Execution engine — how to do it (launch ball, set targets)
 *   8. Tactical intent engine — non-carrier players reposition
 *   9. Movement engine — players move toward targets
 *  10. Restart taker claims ball (if arrived at spot)
 *  11. Rules check — offside etc.
 *  12. Duel detection — resolve duels
 *  13. Decrement VAR timer
 */
public class MatchOrchestrator {

    private final MatchState state;
    private final CleanDecisionEngine decisionEngine;
    private final ActionExecutor actionExecutor;
    private final MovementEngine movementEngine;
    private final BallPhysicsEngine ballEngine;
    private final MatchClockService clockService;

    /** Safety cap on the kickoff half-line hold (a kickoff pass that is never
     *  received must not freeze both teams in their own halves). */
    private static final int KICKOFF_HOLD_MAX_TICKS = 20;
    private final OffsideService offsideService;
    private final VARService varService;
    private final RestartManager restartManager;
    private final DuelService duelService;
    private final SubstitutionService substitutions;
    private final InjuryService injuries;
    private final PenaltyEngine penaltyEngine;
    private final TacticalIntentEngine tacticalEngine;
    private final BallResultHandler ballResultHandler;
    private final ThreatOverrideEngine threatOverrideEngine;
    private final FatigueSystem fatigueSystem = new FatigueSystem();
    private final StoppageClock stoppage = new StoppageClock();
    // Built in the constructor, not here: it needs `state` and `substitutions`, and a field
    // initialiser runs before the constructor body has assigned either.
    private final ConditionalSubstitutionRules conditionalSubs;

    private final List<String> eventLog = new ArrayList<>();
    private final ActionLogService actionLog;
    private final MatchRecorder recorder = new MatchRecorder();
    private final ProposalStatsCollector stats = new ProposalStatsCollector("Home FC", "Away United");

    private ActionType lastLoggedType;
    private String lastLoggedCarrier;
    private boolean tacticsSourceLogged;
    private int restartTakerAge; // ticks the current restart taker has been designated

    /**
     * Set when a penalty taker picks the ball up, consumed by the decision hook on the next tick.
     *
     * <p>It has to be carried in a field like this: the claim block below clears the set-piece type
     * on the very tick the taker reaches the ball, so by the time the decision hook runs there is
     * nothing left to read and a penalty silently degraded into an ordinary 11 m shot.
     */
    /**
     * Ticks a penalty restart has been waiting for its taker.
     *
     * <p>A penalty restart can wedge: the ball sits on the spot, the taker is designated, but he
     * never closes the last fraction of a cell — wall-ringed, or drifting because his tactical
     * target keeps moving. The match then spends ~100 ticks (2.5 match-minutes) churning tactical
     * targets with no decisions at all, and when play eventually resumes the penalty is simply
     * gone. Observed on seed 123: awarded at 66:19, never taken, match recovered at 69'.
     *
     * <p>A penalty cannot be allowed to evaporate, so past {@link #PENALTY_FORCE_TICKS} the taker
     * is put on the spot and the kick is taken. This is deliberately the same "a restart must
     * NEVER freeze the match" rule that widens the claim radius after 40 ticks — extended to the
     * one restart whose whole result depends on it actually happening.
     */
    private boolean fullTimeReached;
    private int penaltyRestartAge;
    private static final int PENALTY_FORCE_TICKS = 40;

    /**
     * Forces a stalled penalty to be taken. Runs every tick, and covers both ways a penalty
     * restart can wedge: the taker is designated but never arrives, or the taker is lost entirely
     * and the ball just sits on the spot with nobody to take it.
     */
    private void checkPenaltyStall() {
        if (!state.isPenaltyPending() || state.getCarrier() != null) {
            penaltyRestartAge = 0;
            return;
        }
        if (++penaltyRestartAge <= PENALTY_FORCE_TICKS) return;

        // The side that was fouled takes it; the spot only tells us which end to use.
        String type = state.getSetPieceType();
        boolean home = type == null || "PENALTY_HOME".equals(type);
        String team = home ? "HOME" : "AWAY";

        // Prefer the designated taker; if the restart lost him, hand it to the best man available.
        Player taker = state.getRestartTaker();
        if (taker == null || taker.isUnavailable() || !team.equals(taker.getTeam())) {
            taker = penaltyEngine.selectTaker(team);
        }
        if (taker == null) {
            // Nobody left who can take it (a full-strength side cannot reach this, but a red card
            // plus a sending-off injury can). Fail loudly rather than looping forever.
            log("PEN", "penalty cannot be taken — no available taker for " + team);
            penaltyRestartAge = 0;
            state.clearSetPieceType();
            return;
        }

        org.example.footballmanager.newLogic.sim.model.Position spot = home
                ? RestartManager.PENALTY_SPOT_HOME
                : RestartManager.PENALTY_SPOT_AWAY;
        log("PEN", "penalty stalled " + penaltyRestartAge + " ticks — forcing the kick by "
                + taker.getLabel());
        taker.setPosition(spot);
        state.getBall().setPosition(spot);
        state.getBall().stop();
        state.setCarrier(taker);
        state.setRestartTaker(null);
        penaltyRestartAge = 0;
        takePenalty(taker);
    }

    /**
     * Runs a penalty once the taker is on the ball: he commits, the keeper dives, and the
     * outcome resets play. The awarded counter was already incremented by
     * {@code RestartManager.handlePenalty}, so this only resolves the execution.
     */
    /**
     * Stops the match clock for a reason and books the time against the half it happened in.
     *
     * <p>Call this where an event already happened; the restart system deliberately does not stop
     * the clock, because it cannot tell a goal celebration from a throw-in.
     */
    public void stoppage(StoppageClock.Reason reason) {
        if (state.isMatchFinished()) return;
        stoppage.stop(state, reason);
    }

    private void takePenalty(Player taker) {
        penaltyRestartAge = 0;
        Player keeper = null;
        for (Player p : state.getPlayers()) {
            if (!taker.getTeam().equals(p.getTeam()) && !p.isUnavailable()
                    && p.getRole() != null && p.getRole().startsWith("GK")) {
                keeper = p;
                break;
            }
        }
        state.setCarrier(null);
        state.setRestartTaker(null);
        PenaltyEngine.Outcome outcome = penaltyEngine.execute(taker, keeper);
        state.setLastTouchPlayer(taker);
        log("PEN", "penalty outcome: " + outcome);
    }

    public MatchOrchestrator(MatchState state) {
        this(state, new TacticsRules());
    }

    public MatchOrchestrator(MatchState state, TacticsRules tactics) {
        this(state, new SideTactics(tactics));
    }

    /**
     * Each side is shaped by its own club's tactics.
     *
     * <p>The single-rules constructor delegates, so every existing caller keeps the behaviour it had — which
     * is the point: a match where both clubs share one grid is still expressible, it is simply no longer the
     * only thing this class can do.
     */
    public MatchOrchestrator(MatchState state, SideTactics tactics) {
        this.state = state;
        this.actionLog = new ActionLogService(state, eventLog);
        this.decisionEngine = new CleanDecisionEngine();
        this.actionExecutor = new ActionExecutor();
        this.movementEngine = new MovementEngine();
        this.ballEngine = new BallPhysicsEngine();
        this.clockService = new MatchClockService();
        this.varService = new VARService(state, SimulationRandom.rng());
        this.restartManager = new RestartManager(tactics);
        this.offsideService = new OffsideService(state, varService, recorder, restartManager);
        this.offsideService.setStats(stats);
        this.duelService = new DuelService(state, recorder, stats, restartManager, varService);
        this.substitutions = new SubstitutionService(state, recorder, stats);
        this.substitutions.setStoppage(stoppage);
        this.conditionalSubs = new ConditionalSubstitutionRules(state, substitutions);
        this.injuries = new InjuryService(state, recorder, stats);
        this.penaltyEngine = new PenaltyEngine(state, recorder, stats, actionLog, restartManager);
        this.tacticalEngine = new TacticalIntentEngine(tactics);
        this.threatOverrideEngine = new ThreatOverrideEngine();

        // Wire engine reference into state for ActionExecutor
        state.setBallEngine(ballEngine);
        // Hand the orchestrator-owned shared action logger to the state so every
        // engine writes through ONE logger (sets "decision engine writes" per AGENTS
        // spec), not just the change-gated orchestrator DEC/EXE summaries.
        state.setActionLogger(actionLog);
        stats.registerPlayers(state.getPlayers());
        ballResultHandler = new BallResultHandler(state, recorder, stats, restartManager, varService);
        // A goal is the largest single source of lost time in a real match.
        ballResultHandler.setOnStoppage(this::stoppage);
        // So is an injury, and unlike a goal the engine has to be told it happened at all.
        injuries.setOnInjury(() -> stoppage(StoppageClock.Reason.INJURY));
    }

    public List<String> getEventLog() { return eventLog; }
    public RestartManager getRestartManager() { return restartManager; }
    public MatchRecorder getRecorder() { return recorder; }
    public ProposalStatsCollector getStats() { return stats; }
    public MatchState getState() { return state; }

    /**
     * The manager's conditional substitution rules, so they can be attached before the first tick.
     *
     * <p>This rules holder used to be private with no accessor, and the orchestrator was built and
     * simulated inside a single static call — so there was <b>no point at which a plan could be
     * attached</b>. The engine evaluated {@code conditionalSubs.onTick()} every tick of every match,
     * against a list that was always empty. Ten unit tests were green the whole time, because the tests
     * built the object themselves and called {@code add()} directly.
     *
     * <p>Wiring it therefore needed two changes and neither was the obvious one: this accessor, and
     * {@link SimMatchRunner#build} so a caller can hold the orchestrator before it runs.
     */
    public ConditionalSubstitutionRules conditionalSubstitutions() { return conditionalSubs; }


    /**
     * One tick of goal celebration, then the kickoff.
     *
     * <p>Three things run on every tick: the scoring side's outfield players run goalward at a
     * sprint, a snapshot is captured, and the hold is counted down. On the last tick the ball goes
     * back to the centre and the restart is set up, which is the first moment the viewer sees it move
     * the other way — so the ball crossing the line, lying in the net, and then being reset all read
     * as three separate things rather than one jump.
     *
     * <p>The scorer is excluded from the sprint, because he has just finished running and is
     * standing near the penalty spot; everybody else is going somewhere.
     */
    private void tickCelebration() {
        // The match clock KEEPS RUNNING through a celebration. That is both the football rule and the
        // thing that makes the celebration work at all: snapshots are keyed by match tick, so a hold
        // that does not advance the clock writes twenty identical frames onto one tick and the replay
        // can only ever show the last of them. The first version of this returned before the clock
        // step, held for twenty ticks, and produced exactly one frame.
        //
        // Half-time is still handled: the clock service sets its own stopped/half-time flags, and
        // simulate() checks them after every tick, so a goal scored just before the break ends the
        // half where it should.
        clockService.tick(state);

        String team = state.getCelebratingTeam();
        Player scorer = state.getLastShooter();
        Position goalSpot = BallResultHandler.goalExitPositionFor(team);
        boolean towardsGoal = "HOME".equals(team);

        for (Player p : state.getPlayers()) {
            if (p == null || p.isGoalkeeper() || p == scorer || !team.equals(p.getTeam())) continue;
            // Run past the goal, toward their own corner and the crowd behind it.
            double row = towardsGoal ? Math.max(goalSpot.getRow(), p.getPosition().getRow() + 0.35)
                    : Math.min(goalSpot.getRow(), p.getPosition().getRow() - 0.35);
            p.setTarget(new Position(row, clampToField(p.getPosition().getColumn())));
        }
        movementEngine.moveAllTowardTargets(state);

        boolean moreToCome = state.consumeCelebrationHoldTick();
        recorder.captureSnapshot(state);
        if (moreToCome) return;

        // Hold is over. The phase goes back to a set piece and the ball returns to the centre.
        if (state.getPhase() == MatchPhase.GOAL_CELEBRATION) {
            state.setPhase(MatchPhase.SET_PIECE);
        }
        String kickoffTeam = state.getRestartTeam();
        restartManager.handleKickoff(state, kickoffTeam);
        // handleKickoff leaves the carrier null when it cannot find a kicker (all attackers
        // unavailable) — never dereference it.
        Player kicker = state.getCarrier();
        log("RST", "celebration over -> kickoff " + kickoffTeam + " | ball"
                + p(state.getBall().getPosition()) + " | taker "
                + (kicker == null ? "none (no kicker available)" : kicker.getLabel()));
    }

    private double clampToField(double column) {
        return Math.max(0.9, Math.min(7.0, column));
    }

    private void log(String tag, String msg) {
        actionLog.log(tag, msg);
    }

    private String p(Position pos) {
        return actionLog.p(pos);
    }

    /** Execute one simulation tick. Called 40 times per minute. */
    public void tick() {
        // A referee's stoppage halts the MATCH, not just the clock.
        //
        // The first version of this only stopped the clock, which is not a stoppage at all: the
        // rest of the pipeline kept running with a dead ball - players moved, duels resolved, the
        // ball travelled - so a "paused" match produced hundreds of events and the statistics
        // inflated by ~50%. Only the stoppage clock runs until the referee releases play.
        if (stoppage.isStopped()) {
            stoppage.tick(state);
            return;
        }

        // A goal celebration runs BEFORE the stoppage check, because the stoppage it replaces used
        // to fire on the very next tick and returned here with nothing to show: the ball had already
        // been moved to the centre and the kickoff already played in the goal's own tick.
        //
        // This is the one place in the pipeline where players move, a snapshot is recorded, and
        // nothing else happens. All three matter. Movement is the point — the scoring side runs to
        // its own corner. The snapshot is what puts the ball in the net in the replay, without which
        // the goal is a line in a log and nothing on the screen.
        if (state.isCelebrating()) {
            tickCelebration();
            return;
        }

        // Half-time is a pause of the same kind, owned by the clock service and released by
        // simulate() once the added time has been announced.
        if (state.isStopped() && state.isHalfTime()) {
            return;
        }

        // === 1. ADVANCE CLOCK ===
        boolean running = clockService.tick(state);
        if (!running) return;

        // === 1b. OFFSIDE POSITION TRACKING (every tick) ===
        // Accumulates consecutiveOffside per attacker (forward of the ball with
        // < 2 opponents goal-side). ThreatOverrideEngine TYPE C reads the counter
        // and pulls a chronic offender back toward his own goal (retreat). Being
        // onside on any tick resets the streak — this is what feeds the retreat.
        offsideService.trackOffsidePositions(state);

        // A restart is consumed the moment ANYONE takes the ball (the restart
        // ball physically sits at its spot; whoever reaches it first plays it).
        // If the taker is still designated while a carrier exists, the restart
        // is over — clear it. Otherwise the ex-taker stays frozen as "taker":
        // refreshTargets skips him (no renewed walk target), looseBallChaser is
        // suppressed (dead ball never recovered), and the match freezes for
        // the rest of the half (wall-ring on a dead ball).
        if (state.getCarrier() != null) {
            Player designated = state.getRestartTaker();
            if (designated != null && designated != state.getCarrier()) {
                log("RST", "restart taken before taker: " + state.getCarrier().getLabel()
                        + " beat " + designated.getLabel() + " to the ball (taker released)");
            }
            state.setRestartTaker(null);
            restartTakerAge = 0;
            // The restart has been consumed — the ball is back in play, so the
            // set-piece guard in OffsideService must lift (it skips offside checks
            // only while a set piece is PENDING, not for the rest of the match).
            state.clearSetPieceType();
            // ...and the phase returns to OPEN_PLAY. Without this, every snapshot
            // after the first corner/goal-kick was labelled SET_PIECE for the rest
            // of the match (replay phase field + any phase-dependent logic).
            if (state.getPhase() == MatchPhase.SET_PIECE) {
                state.setPhase(MatchPhase.OPEN_PLAY);
            }
        }

        // A penalty that is neither taken nor progressing must not be allowed to evaporate.
        // Checked here, unconditionally, because inside the claim block it never ran: by then
        // the restart had already lost its taker, so the block's entry condition was false and
        // the penalty sat unclaimed until open play happened to reclaim the spot.
        checkPenaltyStall();

        // === 2. UNLOCK DUEL LOSERS ===
        for (Player p : state.getPlayers()) {
            if (p.isLocked() && p.getLockTicks() > 0) {
                p.setLockTicks(p.getLockTicks() - 1);
                if (p.getLockTicks() == 0) {
                    p.setLocked(false);
                }
            }
        }

        // === 3. CAN WE RE-DECIDE? ===
        // Only blocked while a VAR review is ACTIVE (varDelayTicks > 0)
        boolean canReDecide = !state.isVARReviewActive();

        // === 4. BALL PHYSICS ENGINE ===
        // Move ball, handle collisions, goal, OOB — returns pure physics result
        BallStepResult ballResult = ballEngine.stepBall(state);

        // === 5. HANDLE BALL PHYSICS RESULT ===
        handleBallPhysicsResult(ballResult);

        // === 6. DECISION + EXECUTION ===
        // Re-decide every tick while a player is in possession (carrier != null)
        // and ball is not in flight (handled by physics result not being FLIGHT/IN_TRANSITION)
        //
        // RIGID RULE (user 2026-09-17, P-UI): while a restart taker is still
        // WALKING to the ball, NOBODY may start an action — the restart is not
        // live play yet. The `restartTaker == null` term is the explicit guard
        // for that invariant: the invariant itself already holds implicitly
        // (restart entry points null the carrier, BallPhysicsEngine now lets only
        // the designated taker pick the restart ball up), and this term makes it
        // structurally impossible for a future caller to break it.
        if (canReDecide && state.getCarrier() != null
                && state.getRestartTaker() == null && isCarrierOnBall()) {
            Player carrier = state.getCarrier();

            // A penalty is its own mechanic, not an 11 m shot. This must run BEFORE the
            // decision engine, otherwise the final-rows hard-SHOT rule fires and the taker
            // simply blasts it from the spot with no run-up, no dive and no nerve - which
            // is exactly the bug S1.7 was raised to fix.
                if (state.isPenaltyPending()) {
                state.setPenaltyPending(false);
                takePenalty(carrier);
                return;
            }

            // RIGID RULE (user 2026-09-17): the carrier must be physically ON the
            // ball before deciding/executing ANY action. An off-ball carrier leaves
            // the ball where it lies and the Movement Engine walks him onto it —
            // the decision is deferred until he actually arrives. Only once he is
            // on the ball (within ON_BALL_EPS) is it glued to his feet.
            state.getBall().setPosition(carrier.getPosition());
            state.getBall().stop();

            DecisionResult result = decisionEngine.decideWithOptions(state);
            DecisionOption decision = result.getChosen();

            // Offside at pass-moment: a CLEAR offside is whistled immediately
            // (IFK restart set by OffsideService, pass NOT executed); a marginal
            // band is deferred to VAR which resolves at the RULES CHECK step.
            boolean offsideBlockedPass = false;
            if (decision.getType() == ActionType.PASS && decision.getTarget() != null) {
                OffsideResult offside = offsideService.checkOffside(
                        decision.getTarget(), carrier.getPosition(), state);
                if (offside.confirmed()) {
                    log("OFF", "offside whistle (clear band) - pass to "
                            + decision.getTarget().getLabel() + " disallowed, indirect free kick");
                    recorder.appendEvent(state.getMatchTicks(), "OFFSIDE",
                            "OFFSIDE by " + decision.getTarget().getLabel()
                                    + " - indirect free kick",
                            carrier, decision.getTarget());
                    offsideBlockedPass = true;
                }
            }
            if (!offsideBlockedPass) {
                actionExecutor.execute(state, decision);

            boolean changed = decision.getType() != lastLoggedType
                    || !carrier.getLabel().equals(lastLoggedCarrier);
            if (changed) {
                lastLoggedType = decision.getType();
                lastLoggedCarrier = carrier.getLabel();
                String decMsg = formatDecision(carrier, result);
                log("DEC", decMsg);
                recorder.appendEvent(state.getMatchTicks(), "DECISION", decMsg, carrier, decision.getTarget());
            }

            // Enriched per-action event with explicit actor attribution (carrier
            // is null after PASS/SHOT/CLEAR execution). Gated on "changed" so a
            // continuing DRIBBLE (re-executed every tick) does not spam events.
            String actionEvent = actionEventType(decision.getType());
            if (actionEvent != null && changed) {
                String actionMsg = actionEvent + " by " + carrier.getLabel() + "(" + carrier.getRole() + ")"
                        + " at " + p(carrier.getPosition())
                        + (decision.getTarget() != null
                            ? " -> " + decision.getTarget().getLabel() + "(" + decision.getTarget().getRole() + ")"
                            : "")
                        + (decision.getType() == ActionType.SHOT
                            ? (state.isLastShotOnTarget() ? " (on target)" : " (off target)")
                            : "");
                recorder.appendEvent(state.getMatchTicks(), actionEvent, actionMsg, carrier, decision.getTarget());
            }

            // Feed the stats collector for each executed action (one per decision change).
            switch (decision.getType()) {
                case PASS -> stats.onPassAttempt(carrier.getTeam(), carrier.getId());
                // A through ball, cross and centre are all PASSES for the
                // pass-accuracy counters, and additionally get their own stat.
                case THRU -> {
                    stats.onPassAttempt(carrier.getTeam(), carrier.getId());
                    stats.onThroughBall(carrier.getTeam(), carrier.getId());
                }
                case CROSS -> {
                    stats.onPassAttempt(carrier.getTeam(), carrier.getId());
                    stats.onCross(carrier.getTeam(), carrier.getId());
                }
                case CENTER -> {
                    stats.onPassAttempt(carrier.getTeam(), carrier.getId());
                    stats.onCenter(carrier.getTeam(), carrier.getId());
                }
                case SHOT -> stats.onShot(carrier.getTeam(), carrier.getId(), state.isLastShotOnTarget());
                case DRIBBLE -> {
                    if (changed) stats.onDribble(carrier.getTeam(), carrier.getId());
                }
                case CLEAR -> stats.onClearance(carrier.getTeam(), carrier.getId());
            }
            }
        }

        // === 7. TACTICAL INTENT ENGINE ===
        // Non-carrier players reposition toward their tactical rules targets
        // (DB → bundled JSON → catalog anchors). Logged once at match start.
        if (!tacticsSourceLogged) {
            log("TAC", "tactical rules: " + restartManager.getTactics().getSource()
                    + " (" + restartManager.getTactics().getRuleCount() + " rules, "
                    + "0-based editor cells -> 1-based field positions)");
            tacticsSourceLogged = true;
        }
        tacticalEngine.refreshTargets(state);

        // === 7b. THREAT OVERRIDE ENGINE ===
        // Runs AFTER tactical targets are computed and BEFORE movement, so an
        // override rewrites the target the Movement Engine follows THIS tick.
        // TYPE A: nearest eligible defender approaches the ball carrier all the
        // way to duel range (the press-duel radius then fires the tackle).
        // TYPE B: defender presses an isolated opponent in the defensive final
        // quarter. TYPE C: chronic-offside attacker retreats toward own goal.
        threatOverrideEngine.evaluate(state);

        // === 8. MOVEMENT ENGINE ===
        // Capture on-ball state BEFORE movement: the glue in 8b is a
        // follow-the-feet attach (continuous possession, ball moves WITH the
        // player), not a teleport — it must survive the carrier's run this tick.
        boolean carrierOnBallBeforeMove = state.getCarrier() != null && isCarrierOnBall();
        // Players move toward their tactical targets every tick
        movementEngine.moveAllTowardTargets(state);
        fatigueSystem.update(state);

        // === 8b. POSSESSION GLUE ===
        // Movement moved the carrier; the ball follows him ONLY while he had
        // controlled possession (was on the ball) at tick start (RIGID RULE
        // user 2026-09-17). An off-ball carrier leaves the ball where it lies —
        // the Movement Engine walks him onto it, next tick isCarrierOnBall()
        // turns true and the glue resumes.
        if (state.getCarrier() != null && carrierOnBallBeforeMove) {
            Position carryPos = state.getCarrier().getPosition();
            state.getBall().setPosition(carryPos);
            state.getBall().stop();
        }

        // === 9. RESTART TAKER CLAIMS THE BALL ===
        // After movement, if taker reached the ball (physically ON it, RIGID RULE
        // user 2026-09-17), he claims it — the ball is already at the restart
        // spot, so no snap is needed.
        if (state.getCarrier() == null && state.getRestartTaker() != null) {
            Player taker = state.getRestartTaker();
            restartTakerAge++;
            int age = restartTakerAge;
            double dist = SimUtils.distance(taker.getPosition(), state.getBall().getPosition());
            // Normal claim: taker physically ON the ball (RIGID RULE). A stalled
            // taker (wall-ringed, locked mid-walk, etc.) eventually claims from a
            // slightly wider reach so a restart can NEVER freeze the match.
            double claimRadius = restartTakerAge > 40 ? 0.6 : BallPhysicsEngine.ON_BALL_EPS;
            if (dist <= claimRadius) {
                state.setCarrier(taker);
                state.getBall().stop();
                state.setRestartTaker(null);
                restartTakerAge = 0;
                // Read the type BEFORE it is cleared - this is the only moment it is still
                // available, and it is what tells the decision hook to run a penalty rather
                // than letting the final-rows hard-SHOT rule handle it.
                state.clearSetPieceType();
                if (state.getPhase() == MatchPhase.SET_PIECE) {
                    state.setPhase(MatchPhase.OPEN_PLAY);
                }
                if (dist > BallPhysicsEngine.ON_BALL_EPS) {
                    log("RST", "TAKER stall-claim: " + taker.getLabel()
                            + " at " + p(taker.getPosition()) + " ball" + p(state.getBall().getPosition())
                            + " (was " + String.format("%.2f", dist) + " cells, age " + age + ")");
                } else {
                    log("RST", "TAKER claims ball: " + taker.getLabel()
                            + " ball" + p(state.getBall().getPosition()) + " " + taker.getLabel() + p(taker.getPosition()));
                }
            }
        } else {
            restartTakerAge = 0;
        }

        // === 10. RULES CHECK ===
        // Offside whistle resolved AFTER action execution (deferred offside +
        // VAR cadence), not before — see step 6 for the pass-moment check.
        offsideService.resolvePendingVAROffside(state);

        // === 11. DUEL DETECTION ===
        detectAndResolveDuels();

        // Kickoff half-line hold: a safety release, so a kickoff pass that is
        // never received (intercepted, deflected out) can never freeze every
        // player in his own half for the rest of the match.
        if (state.isKickoffHalfHold()
                && state.getMatchTicks() > state.getKickoffHalfHoldTick() + KICKOFF_HOLD_MAX_TICKS) {
            state.setKickoffHalfHold(false);
            log("RST", "kickoff hold released by timeout");
        }

        // === 12. DECREMENT VAR TIMER ===
        state.decrementVAR();

        // Capture snapshot for replay
        recorder.captureSnapshot(state);

        // Track possession tick from the team that LAST touched the ball (persists
        // during flight, unlike the carrier which is null mid-pass/shot).
        String possTeam = state.getLastTouchTeam();
        if (possTeam != null) {
            stats.onPossessionTick(possTeam);
        }
    }

    /**
     * RIGID RULE (user 2026-09-17): a player is "ON the ball" — allowed to decide/
     * execute PASS/SHOT/DRIBBLE/CLEAR — only when physically within ON_BALL_EPS of
     * the ball's position. No action ever starts with the ball teleported onto the
     * player; the player walks onto the ball first.
     */
    private boolean isCarrierOnBall() {
        Player carrier = state.getCarrier();
        if (carrier == null) return false;
        return SimUtils.distance(carrier.getPosition(), state.getBall().getPosition())
                <= BallPhysicsEngine.ON_BALL_EPS;
    }

    private void handleBallPhysicsResult(BallStepResult res) {
        ballResultHandler.handle(res);
    }


    private void detectAndResolveDuels() {
        if (state.getCarrier() == null) return;
        Player carrier = state.getCarrier();

        duelService.detectAndResolveDuels();

        // === SUBSTITUTIONS (Sprint 1.8) ===
        // Emergency replacements for a red card or an injury, plus a routine change once
        // someone is exhausted. Runs after duels so a player sent off this tick is replaced on
        // the next one rather than in the same tick as the tackle.
        // Order is the owner's precedence chain: an injury replacement is not a decision anybody
        // made, a manager's rule is a deliberate act, and the fatigue change is the fallback when
        // nobody asked. substitutions.onTick() does injury first, then fatigue; the manager's rules
        // sit between them, so they run after the injury pass and before the fatigue one.
        substitutions.onTickInjuriesOnly();
        conditionalSubs.onTick();
        substitutions.onTickFatigueOnly();
        // A substitution that actually changed the team is a stoppage in its own right.
        if (substitutions.didSubstituteLastTick()) {
            stoppage(StoppageClock.Reason.SUBSTITUTION);
        }

        // === INJURIES (Sprint 1.6) ===
        // After substitutions, so a player who has just come off injured is not immediately
        // re-selected as a victim on the same tick.
        injuries.onTick();
    }

    private String formatDecision(Player carrier, DecisionResult result) {
        return actionLog.formatDecision(carrier, result);
    }

    private String minute() {
        return actionLog.minute();
    }

    /** Resolve which team a player (matched by short label) belongs to. */
    private String resolveTeamByLabel(String label) {
        if (label == null) return null;
        for (Player p : state.getPlayers()) {
            if (label.equals(p.getLabel())) return p.getTeam();
        }
        return null;
    }

    /** Map an executed decision to its enriched event type (null = no event). */
    private String actionEventType(ActionType type) {
        return switch (type) {
            case PASS -> "PASS";
            case SHOT -> "SHOT";
            case DRIBBLE -> "DRIBBLE";
            case CLEAR -> "CLEAR";
            default -> null;
        };
    }

    /** Run the simulation for a number of ticks. */
    /**
     * Runs the match to full time.
     *
     * <p>{@code ticks} is the scheduled 90 minutes; the loop runs past it because the referee adds
     * time for everything lost to goals, injuries, substitutions and cards. It stops on
     * {@code isMatchFinished()} rather than on a tick count, and carries a guard purely so a bug in
     * the clock cannot spin forever.
     */
    /**
     * Runs the match to full time.
     *
     * <p>A half is not 45 minutes. It is 45 minutes <em>plus</em> the time the referee announces
     * for everything lost to goals, injuries, substitutions and cards — and that figure is only
     * known once the 45 minutes have been played, because it is a sum of what happened during them.
     * So each half runs in two phases, which is exactly how it works in a real stadium:
     *
     * <ol>
     *   <li>the scheduled 45 minutes run;</li>
     *   <li>at 45:00 the referee announces the added time and it is played out;</li>
     *   <li>then half-time, and the second half begins.</li>
     * </ol>
     *
     * <p>Modelling this as a single "half ends at 1800 + added" was circular — the clock needed an
     * announcement that only happened because the clock had stopped — and the first attempt got it
     * backwards, applying the first half's added time to the second half. Every match was finishing
     * at 47 minutes.
     *
     * <p>{@code ticks} is the scheduled 90 minutes; the loop runs past it because of the added
     * time, and stops on {@code isMatchFinished()} rather than on a count.
     */
    public void simulate(int ticks) {
        clockService.setHalfEndTick(ScheduledEnd.FIRST_HALF);
        int phase = Phase.FIRST_SCHEDULED;

        for (int i = 0; i < ticks * 2; i++) {
            if (state.isMatchFinished()) return;
            tick();

            if (!(state.isStopped() && state.isHalfTime()) || fullTimeReached) continue;

            switch (phase) {
                case Phase.FIRST_SCHEDULED -> {
                    // 45:00 — announce what the first half lost and play it out.
                    int added = stoppage.addedTimeForHalfEnding(false);
                    log("CLK", "half time — " + stoppage.announcedSeconds(added)
                            + " seconds added for the first half");
                    clockService.setHalfEndTick(ScheduledEnd.FIRST_HALF + added);
                    resumePlay();
                    phase = Phase.FIRST_ADDED;
                }
                case Phase.FIRST_ADDED -> {
                    // The added time is played out. Now it is genuinely half-time.
                    log("CLK", "half time interval");
                    stoppage.startSecondHalf();
                    clockService.setHalfEndTick(ScheduledEnd.SECOND_HALF);
                    resumePlay();
                    restartManager.handleKickoff(state, "AWAY");
                    phase = Phase.SECOND_SCHEDULED;
                }
                case Phase.SECOND_SCHEDULED -> {
                    int added = stoppage.addedTimeForHalfEnding(true);
                    log("CLK", "full time — " + stoppage.announcedSeconds(added)
                            + " seconds of added time for the second half");
                    clockService.setHalfEndTick(ScheduledEnd.SECOND_HALF + added);
                    resumePlay();
                    phase = Phase.SECOND_ADDED;
                }
                case Phase.SECOND_ADDED -> {
                    log("CLK", "full time");
                    fullTimeReached = true;
                    state.setMatchFinished(true);
                }
                default -> { }
            }
        }
    }

    /** Clears the half-time pause and lets the clock run again. */
    private void resumePlay() {
        state.setHalfTime(false);
        clockService.resume(state);
    }

    /** Scheduled (pre-added-time) end of each half, in ticks. */
    private static final class ScheduledEnd {
        static final int FIRST_HALF = MatchClockService.TOTAL_MATCH_TICKS / 2;
        static final int SECOND_HALF = MatchClockService.TOTAL_MATCH_TICKS;
        private ScheduledEnd() { }
    }

    /** Which part of the match the clock is currently in. */
    private static final class Phase {
        static final int FIRST_SCHEDULED = 0;
        static final int FIRST_ADDED = 1;
        static final int SECOND_SCHEDULED = 2;
        static final int SECOND_ADDED = 3;
        private Phase() { }
    }

    /** Build the complete post-match outcome (report-ready). */
    public ProposalMatchOutcome buildOutcome() {
        return new ProposalMatchOutcomeBuilder().build(this);
    }
}