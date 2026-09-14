package org.example.footballmanager.demo.service.proposal.engine;

import org.example.footballmanager.demo.service.proposal.engine.decision.CleanDecisionEngine;
import org.example.footballmanager.demo.service.proposal.model.*;
import org.example.footballmanager.demo.service.proposal.recording.MatchRecorder;
import org.example.footballmanager.demo.service.proposal.result.ProposalStatsCollector;
import org.example.footballmanager.demo.service.proposal.restarts.RestartManager;
import org.example.footballmanager.demo.service.proposal.rules.FootballRules;
import org.example.footballmanager.demo.service.proposal.tactics.TacticsRules;
import org.example.footballmanager.demo.service.proposal.util.SimUtils;

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
    private final FootballRules rules;
    private final RestartManager restartManager;
    private final DuelEngine duelEngine;
    private final TacticalIntentEngine tacticalEngine;
    private final BallResultHandler ballResultHandler;

    private final List<String> eventLog = new ArrayList<>();
    private final MatchRecorder recorder = new MatchRecorder();
    private final ProposalStatsCollector stats = new ProposalStatsCollector("Home FC", "Away United");

    private ActionType lastLoggedType;
    private String lastLoggedCarrier;
    private boolean tacticsSourceLogged;

    public MatchOrchestrator(MatchState state) {
        this(state, new TacticsRules());
    }

    public MatchOrchestrator(MatchState state, TacticsRules tactics) {
        this.state = state;
        this.decisionEngine = new CleanDecisionEngine();
        this.actionExecutor = new ActionExecutor();
        this.movementEngine = new MovementEngine();
        this.ballEngine = new BallPhysicsEngine();
        this.clockService = new MatchClockService();
        this.rules = new FootballRules();
        this.restartManager = new RestartManager(tactics);
        this.duelEngine = new DuelEngine();
        this.tacticalEngine = new TacticalIntentEngine(tactics);

        // Wire engine reference into state for ActionExecutor
        state.setBallEngine(ballEngine);
        stats.registerPlayers(state.getPlayers());
        ballResultHandler = new BallResultHandler(
                state, recorder, stats, restartManager, eventLog);
    }

    public List<String> getEventLog() { return eventLog; }
    public RestartManager getRestartManager() { return restartManager; }
    public MatchRecorder getRecorder() { return recorder; }
    public ProposalStatsCollector getStats() { return stats; }

    private void log(String tag, String msg) {
        String line = "[" + minute() + "|" + tag + "] " + msg;
        eventLog.add(line);
        System.out.println(line);
    }

    private String p(Position pos) {
        return pos == null ? "?" : "(%.1f,%.1f)".formatted(pos.getRow(), pos.getColumn());
    }

    /** Execute one simulation tick. Called 40 times per minute. */
    public void tick() {
        // === 1. ADVANCE CLOCK ===
        boolean running = clockService.tick(state);
        if (!running) return;

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
        if (canReDecide && state.getCarrier() != null) {
            Player carrier = state.getCarrier();

            // Ball must be at the carrier's feet before deciding/executing.
            // Orchestrator ensures this: after RECEIVE/LOOSE_PICKUP ball pos = carrier pos.
            // For safety, snap here too.
            state.getBall().setPosition(carrier.getPosition());
            state.getBall().stop();

            DecisionResult result = decisionEngine.decideWithOptions(state);
            DecisionOption decision = result.getChosen();
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

        // === 8. MOVEMENT ENGINE ===
        // Players move toward their tactical targets every tick
        movementEngine.moveAllTowardTargets(state);

        // === 8b. POSSESSION GLUE ===
        // Movement moved the carrier; the ball must follow him. Without this,
        // a carrier dribbling is visually left behind (ball alone), and the next
        // action would appear to start with the carrier NOT on the ball
        // (user-reported bug 2026-09-14: shot/pass fires, ball flies alone).
        if (state.getCarrier() != null) {
            Position carryPos = state.getCarrier().getPosition();
            state.getBall().setPosition(carryPos);
            state.getBall().stop();
        }

        // === 9. RESTART TAKER CLAIMS THE BALL ===
        // After movement, if taker reached the ball, they claim it
        if (state.getCarrier() == null && state.getRestartTaker() != null) {
            Player taker = state.getRestartTaker();
            if (SimUtils.distance(taker.getPosition(), state.getBall().getPosition()) <= BallPhysicsEngine.PICKUP_DISTANCE) {
                state.setCarrier(taker);
                state.getBall().setPosition(new Position(taker.getPosition().getRow(), taker.getPosition().getColumn()));
                state.getBall().stop();
                state.setRestartTaker(null);
                log("RST", "TAKER claims ball: " + taker.getLabel()
                        + " ball" + p(state.getBall().getPosition()) + " " + taker.getLabel() + p(taker.getPosition()));
            }
        }

        // === 10. RULES CHECK ===
        // Offside checked AFTER action execution, not before
        rules.checkOffsideAfterAction(state);

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

    private void handleBallPhysicsResult(BallStepResult res) {
        ballResultHandler.handle(res);
    }


    private void detectAndResolveDuels() {
        if (state.getCarrier() == null) return;
        Player carrier = state.getCarrier();

        for (Player opponent : state.getPlayers()) {
            if (opponent.getTeam().equals(carrier.getTeam())) continue;
            if (opponent.isUnavailable() || opponent.isLocked()) continue;

            DuelEngine.DuelType duelType = duelEngine.checkDuel(carrier, opponent, state);
            if (duelType != null) {
                Player winner = duelEngine.resolveDuel(carrier, opponent, duelType, state);
                duelEngine.applyDuelResult(state, winner, winner == carrier ? opponent : carrier);
                String duelMsg = "DUEL " + duelType + " won by " + winner.getLabel()
                        + " (" + carrier.getLabel() + p(carrier.getPosition())
                        + " v " + opponent.getLabel() + p(opponent.getPosition()) + ")"
                        + " ball" + p(state.getBall().getPosition());
                log("DUL", duelMsg);
                recorder.appendEvent(state.getMatchTicks(), "DUEL", duelMsg, state);
                stats.onDuelWon(winner.getId(), (winner == carrier ? opponent : carrier).getId());
            }
        }
    }

    private String formatDecision(Player carrier, DecisionResult result) {
        DecisionOption chosen = result.getChosen();
        Player receiver = chosen.getTarget();
        StringBuilder sb = new StringBuilder();
        sb.append("DECISION ").append(carrier.getLabel()).append("(").append(carrier.getRole()).append(")")
                .append(" -> ").append(chosen.getType())
                .append(" score=").append(String.format("%6.1f", chosen.getScore()));
        for (DecisionOption o : result.getOptions()) {
            if (o == chosen) continue;
            sb.append(" | ").append(o.getType()).append("=")
                    .append(String.format("%5.1f", o.getScore()));
        }
        sb.append(" | ball").append(p(state.getBall().getPosition()))
                .append(" ").append(carrier.getLabel()).append(p(carrier.getPosition()));
        if (receiver != null) {
            sb.append(" -> ").append(receiver.getLabel()).append("(").append(receiver.getRole()).append(")")
                    .append(p(receiver.getPosition()));
        }
        sb.append(" | ").append(chosen.getReason());
        return sb.toString();
    }

    private String minute() {
        return String.format("%d:%02d",
                state.getMatchTicks() / 40,
                state.getMatchTicks() % 40 * 90 / 40);
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