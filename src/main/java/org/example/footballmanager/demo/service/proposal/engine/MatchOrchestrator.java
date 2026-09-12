package org.example.footballmanager.demo.service.proposal.engine;

import org.example.footballmanager.demo.service.proposal.engine.decision.CleanDecisionEngine;
import org.example.footballmanager.demo.service.proposal.model.*;
import org.example.footballmanager.demo.service.proposal.restarts.RestartManager;
import org.example.footballmanager.demo.service.proposal.rules.FootballRules;
import org.example.footballmanager.demo.service.proposal.tactics.TacticsRules;
import org.example.footballmanager.demo.service.proposal.util.SimUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Match Orchestrator - coordinates all engine calls within a single tick.
 * Orchestration is NOT an engine - it just calls engines in order.
 * 
 * Tick flow:
 *   1. Advance match clock (MatchClockService)
 *   2. Unlock duel losers
 *   3. VAR check - can we re-decide?
 *   4. Decision engine - what to do
 *   5. Execution engine - how to do it
 *   6. Ball physics engine - ball movement
 *   7. Ball arrival handling (receiver / loose ball / goal)
 *   8. Movement engine - player movement
 *   9. Rules check - offside etc.
 *   10. Restart check - ball out of bounds?
 *   11. Duel detection - resolve duels
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

    private final List<String> eventLog = new ArrayList<>();

    private ActionType lastLoggedType;
    private String lastLoggedCarrier;
    private String lastShooterTeam;
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
    }

    public List<String> getEventLog() { return eventLog; }
    public RestartManager getRestartManager() { return restartManager; }

    private void log(String tag, String msg) {
        String line = "[" + minute() + "|" + tag + "] " + msg;
        eventLog.add(line);
        System.out.println(line);
    }

    private String p(Position pos) {
        return pos == null ? "?" : "(%.1f,%.1f)".formatted(pos.getRow(), pos.getColumn());
    }

    /**
     * Execute one simulation tick. Called 40 times per minute.
     */
    public void tick() {
        // === 1. ADVANCE CLOCK ===
        // CRITICAL: Clock always advances. No OOB holds. No pause during flight.
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

        // === 4. DECISION + EXECUTION ===
        // The engine re-decides EVERY tick while a player is in possession.
        // Execution happens each tick (e.g. dribble keeps moving forward);
        // logging is deduplicated so repeated same-type dribbles don't spam.
        Ball ball = state.getBall();
        boolean ballInFlight = ball.getCarrier() == null && ball.getTarget() != null;
        if (canReDecide && state.getCarrier() != null && !ballInFlight) {
            Player carrier = state.getCarrier();
            // Ball must be at the carrier's feet before deciding/executing.
            // Otherwise a dribble-lagged ball icon would let the player shoot
            // "without being on the ball" (shot/pass scored from carrier's row
            // but flown from the trailing ball position).
            ball.setPosition(carrier.getPosition());
            ball.setSpeed(0);
            DecisionResult result = decisionEngine.decideWithOptions(state);
            DecisionOption decision = result.getChosen();
            if (decision.getType() == ActionType.SHOT) {
                lastShooterTeam = carrier.getTeam();
            }
            actionExecutor.execute(state, decision);

            boolean changed = decision.getType() != lastLoggedType
                    || !carrier.getLabel().equals(lastLoggedCarrier);
            if (changed) {
                lastLoggedType = decision.getType();
                lastLoggedCarrier = carrier.getLabel();
                log("DEC", formatDecision(carrier, result));
            }
        }

        // === 5. BALL PHYSICS ENGINE ===
        if (ball.getCarrier() != null) {
            ballEngine.followCarrier(ball);
        } else if (ball.getTarget() != null) {
            boolean arrived = ballEngine.moveBallTowardTarget(ball);
            if (arrived) {
                handleBallArrival();
            }
        } else if (ball.getRollDirection() != null && ball.getSpeed() > 0) {
            ballEngine.moveLooseBall(ball);
        }

        // === 6. TACTICAL INTENT ENGINE ===
        // Non-carrier players reposition toward their tactical rules targets
        // (DB → bundled JSON → catalog anchors). Logged once at match start.
        if (!tacticsSourceLogged) {
            log("TAC", "tactical rules: " + restartManager.getTactics().getSource()
                    + " (" + restartManager.getTactics().getRuleCount() + " rules, "
                    + "0-based editor cells -> 1-based field positions)");
            tacticsSourceLogged = true;
        }
        tacticalEngine.refreshTargets(state);

        // === 7. MOVEMENT ENGINE ===
        // Players move toward their tactical targets every tick
        movementEngine.moveAllTowardTargets(state);

        // === 7b. RESTART TAKER CLAIMS THE BALL ===
        if (state.getCarrier() == null && state.getRestartTaker() != null) {
            Player taker = state.getRestartTaker();
            if (SimUtils.distance(taker.getPosition(), ball.getPosition()) <= BallPhysicsEngine.PICKUP_DISTANCE) {
                state.setCarrier(taker);
                ball.setCarrier(taker);
                ball.setTarget(null);
                ball.setSpeed(0);
                state.setRestartTaker(null);
                taker.setTarget(null);
                log("RST", "TAKER claims ball: " + taker.getLabel()
                        + " ball" + p(ball.getPosition()) + " " + taker.getLabel() + p(taker.getPosition()));
            }
        }

        // === 8. RULES CHECK ===
        // Offside checked AFTER action execution, not before
        rules.checkOffsideAfterAction(state);

        // === 9. RESTART CHECK (at END of tick) ===
        // Ball ONLY goes OOB after a tick completes, never mid-tick
        handlePossibleOutOfBounds();

        // === 10. DUEL DETECTION ===
        detectAndResolveDuels();

        // === 11. DECREMENT VAR TIMER ===
        state.decrementVAR();
    }

    private void handleBallArrival() {
        Ball ball = state.getBall();
        Position arrival = ball.getPosition();

        Player receiver = state.getPendingReceiver();
        state.setPendingReceiver(null);

        if (receiver != null) {
            // Pass received
            state.setCarrier(receiver);
            ball.setCarrier(receiver);
            ball.setTarget(null);
            ball.setSpeed(0);
            receiver.setPosition(arrival);
            log("ORC", "RECEIVE " + receiver.getLabel() + "(" + receiver.getRole() + ")"
                    + " at " + p(arrival) + " | ball" + p(arrival));
            return;
        }

        // Shot or loose ball arrival
        if (lastShooterTeam != null) {
            Position targetGoal = ActionEngine.goalPositionFor(lastShooterTeam);
            if (SimUtils.distance(arrival, targetGoal) < 1.0) {
                goalScored(lastShooterTeam);
                lastShooterTeam = null;
                return;
            }
            log("ORC", "SHOT MISSED by " + lastShooterTeam
                    + " -> loose ball | ball" + p(arrival));
            lastShooterTeam = null;
        }

        // Loose ball - nearest player takes it
        Player nearest = findNearestPlayer(arrival);
        if (nearest != null) {
            state.setCarrier(nearest);
            ball.setCarrier(nearest);
            ball.setTarget(null);
            ball.setSpeed(0);
            log("ORC", "LOOSE BALL recovered by " + nearest.getLabel()
                    + " | ball" + p(arrival) + " " + nearest.getLabel() + p(nearest.getPosition()));
        }
    }

    private Player findNearestPlayer(Position pos) {
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (Player p : state.getPlayers()) {
            if (p.isUnavailable()) continue;
            double d = SimUtils.distance(p.getPosition(), pos);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    private void goalScored(String scorerTeam) {
        if ("HOME".equals(scorerTeam)) state.addHomeGoal();
        else state.addAwayGoal();
        log("ORC", "*** GOAL " + scorerTeam
                + " - score " + state.getHomeGoals() + ":" + state.getAwayGoals() + " ***"
                + " ball" + p(state.getBall().getPosition()));
        // Reset for kickoff (instant restart, clock keeps running)
        restartManager.handleKickoff(state, "HOME".equals(scorerTeam) ? "AWAY" : "HOME");
        log("RST", "kickoff -> ball at center, taker " + state.getCarrier().getLabel());
    }

    private void handlePossibleOutOfBounds() {
        Ball ball = state.getBall();
        String goalLine = ballEngine.checkGoalLine(ball, lastTouchTeam());
        if (goalLine != null) {
            restartManager.handleRestart(state, goalLine);
            log("BAL", "OOB goal line -> " + goalLine + " ball" + p(ball.getPosition()));
            log("RST", "restart " + goalLine + " ball" + p(ball.getPosition())
                    + " taker " + (state.getRestartTaker() == null ? "none" : state.getRestartTaker().getLabel()));
            return;
        }
        String sideline = ballEngine.checkSideline(ball, lastTouchTeam());
        if (sideline != null) {
            restartManager.handleRestart(state, sideline);
            log("BAL", "OOB sideline -> " + sideline + " ball" + p(ball.getPosition()));
            log("RST", "restart " + sideline + " ball" + p(ball.getPosition())
                    + " taker " + (state.getRestartTaker() == null ? "none" : state.getRestartTaker().getLabel()));
        }
    }

    private String lastTouchTeam() {
        Player ballCarrier = state.getBall().getCarrier();
        if (ballCarrier != null) return ballCarrier.getTeam();
        return state.getCarrierTeam();
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
                log("DUL", "DUEL " + duelType + " won by " + winner.getLabel()
                        + " (" + carrier.getLabel() + p(carrier.getPosition())
                        + " v " + opponent.getLabel() + p(opponent.getPosition()) + ")"
                        + " ball" + p(state.getBall().getPosition()));
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

    /**
     * Run the simulation for a number of ticks.
     */
    public void simulate(int ticks) {
        for (int i = 0; i < ticks; i++) {
            tick();
        }
    }
}