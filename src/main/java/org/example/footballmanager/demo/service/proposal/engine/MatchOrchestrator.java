package org.example.footballmanager.demo.service.proposal.engine;

import org.example.footballmanager.demo.service.proposal.engine.decision.CleanDecisionEngine;
import org.example.footballmanager.demo.service.proposal.model.*;
import org.example.footballmanager.demo.service.proposal.recording.MatchRecorder;
import org.example.footballmanager.demo.service.proposal.result.ProposalStatsCollector;
import org.example.footballmanager.demo.service.proposal.restarts.RestartManager;
import org.example.footballmanager.demo.service.proposal.rules.FootballRules;
import org.example.footballmanager.demo.service.proposal.rules.OffsideService;
import org.example.footballmanager.demo.service.proposal.engine.EngineInterfaces.OffsideService.OffsideResult;
import org.example.footballmanager.demo.service.proposal.rules.VARService;
import org.example.footballmanager.demo.service.proposal.tactics.TacticsRules;
import org.example.footballmanager.demo.service.proposal.util.SimUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Random;
import java.util.Random;

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
    private final FootballRules rules;
    private final OffsideService offsideService;
    private final VARService varService;
    private final RestartManager restartManager;
    private final DuelService duelService;
    private final TacticalIntentEngine tacticalEngine;
    private final BallResultHandler ballResultHandler;
    private final ThreatOverrideEngine threatOverrideEngine;

    private final List<String> eventLog = new ArrayList<>();
    private final ActionLogService actionLog;
    private final MatchRecorder recorder = new MatchRecorder();
    private final ProposalStatsCollector stats = new ProposalStatsCollector("Home FC", "Away United");

    private ActionType lastLoggedType;
    private String lastLoggedCarrier;
    private boolean tacticsSourceLogged;
    private int restartTakerAge; // ticks the current restart taker has been designated

    public MatchOrchestrator(MatchState state) {
        this(state, new TacticsRules());
    }

    public MatchOrchestrator(MatchState state, TacticsRules tactics) {
        this.state = state;
        this.actionLog = new ActionLogService(state, eventLog);
        this.decisionEngine = new CleanDecisionEngine();
        this.actionExecutor = new ActionExecutor();
        this.movementEngine = new MovementEngine();
        this.ballEngine = new BallPhysicsEngine();
        this.clockService = new MatchClockService();
        this.rules = new FootballRules();
        this.varService = new VARService(state, new Random());
        this.offsideService = new OffsideService(state, varService);
        this.restartManager = new RestartManager(tactics);
        this.duelService = new DuelService(state, recorder, stats);
        this.tacticalEngine = new TacticalIntentEngine(tactics);
        this.threatOverrideEngine = new ThreatOverrideEngine();

        // Wire engine reference into state for ActionExecutor
        state.setBallEngine(ballEngine);
        // Hand the orchestrator-owned shared action logger to the state so every
        // engine writes through ONE logger (sets "decision engine writes" per AGENTS
        // spec), not just the change-gated orchestrator DEC/EXE summaries.
        state.setActionLogger(actionLog);
        stats.registerPlayers(state.getPlayers());
        ballResultHandler = new BallResultHandler(state, recorder, stats, restartManager);
    }

    public List<String> getEventLog() { return eventLog; }
    public RestartManager getRestartManager() { return restartManager; }
    public MatchRecorder getRecorder() { return recorder; }
    public ProposalStatsCollector getStats() { return stats; }

    private void log(String tag, String msg) {
        actionLog.log(tag, msg);
    }

    private String p(Position pos) {
        return actionLog.p(pos);
    }

    /** Execute one simulation tick. Called 40 times per minute. */
    public void tick() {
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
        }

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
        if (canReDecide && state.getCarrier() != null && isCarrierOnBall()) {
            Player carrier = state.getCarrier();

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
    public void simulate(int ticks) {
        for (int i = 0; i < ticks; i++) {
            tick();
            // Half-time: the clock pauses at 1800 ticks (45'). Resume and start
            // the second half with the AWAY team kicking off (teams keep the
            // same attacking direction in this simplified model — HOME attacks
            // row 8, AWAY attacks row 1 — so no end swap is needed).
            if (state.isStopped() && state.getMatchTicks() == 1800) {
                clockService.resume(state);
                restartManager.handleKickoff(state, "AWAY");
            }
        }
    }
}