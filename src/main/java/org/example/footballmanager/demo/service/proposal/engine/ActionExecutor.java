package org.example.footballmanager.demo.service.proposal.engine;

import org.example.footballmanager.demo.service.proposal.model.*;
import org.example.footballmanager.demo.service.proposal.util.SimUtils;

/**
 * Execution engine - ONLY executes the chosen action.
 * Takes a DecisionOption and performs the physical execution.
 * No decision-making, no override rules.
 * 
 * Core principle: This engine NEVER overrides the decision engine's choice.
 * If decision says PASS, it passes. If decision says SHOT, it shoots.
 */
public class ActionExecutor {

    /**
     * Execute a decision. This method ONLY executes - no override rules.
     */
    public void execute(MatchState state, DecisionOption decision) {
        if (decision == null || decision.getType() == null) {
            return;
        }

        Player carrier = state.getCarrier();
        if (carrier == null && decision.getType() != ActionType.CLEAR) {
            return;
        }

        ActionType type = decision.getType();

        switch (type) {
            case PASS:
                executePass(state, decision);
                break;
            case SHOT:
                executeShot(state, decision);
                break;
            case DRIBBLE:
                executeCarry(state, decision);
                break;
            case CLEAR:
                executeClear(state, decision);
                break;
            default:
                // For now, ignore other types
                break;
        }

        state.setLastDecisionScore(decision.getScore());
        state.setLastDecisionReason(decision.getReason());
    }

    /**
     * Execute a PASS action.
     * Uses ExecutionQuality to determine if pass succeeds or fails.
     */
    private void executePass(MatchState state, DecisionOption decision) {
        Player carrier = state.getCarrier();
        Player receiver = decision.getTarget();
        if (carrier == null) return;
        if (receiver == null) return;

        // Calculate pass speed based on passing skill
        double passingSkill = carrier.getSkills().passing();
        double desiredSpeed = calculatePassSpeed(passingSkill);

        // Determine actual target with deviation
        Position intendedTarget = receiver.getPosition();
        ExecutionQuality.PassResult result = ExecutionQuality.evaluatePass(
            carrier, carrier.getPosition(), intendedTarget, receiver,
            desiredSpeed);

        // Set ball properties - ball starts flying toward actual target
        Ball ball = state.getBall();
        ball.setTarget(result.getActualTarget());
        ball.setSpeed(result.getSpeed());
        ball.setCarrier(null);
        ball.setAirborne(false);

        // Remember who should receive the ball when it arrives.
        // Receiver holds position during flight (target cleared) so the
        // arrival snap stays on the pass line.
        receiver.setTarget(null);
        state.setPendingReceiver(receiver);

        // Track stats
        state.setCarrier(null);
        carrier.incrementConsecutiveCarries();
        state.incrementPassAttempts();
        if (result.isReceived()) {
            state.setPassesCompleted(state.getPassesCompleted() + 1);
        }
    }

    /**
     * Execute a SHOT action.
     * Uses ExecutionQuality to determine if shot is on target, saved, or missed.
     */
    private void executeShot(MatchState state, DecisionOption decision) {
        Player carrier = state.getCarrier();
        if (carrier == null) return;

        Position goal = ActionEngine.goalPositionFor(carrier.getTeam());
        double strikerSkill = carrier.getSkills().striker();

        // Evaluate shot
        ExecutionQuality.ShotResult result = ExecutionQuality.evaluateShot(
            goal, (int) strikerSkill, 0.0, carrier.getPosition());

        // Set ball properties
        Ball ball = state.getBall();
        ball.setTarget(result.getActualTarget());
        ball.setSpeed(result.getSpeed());
        ball.setCarrier(null);
        ball.setAirborne(true);

        state.setCarrier(null);
        carrier.incrementConsecutiveCarries();
        state.incrementShots();
        if (result.isOnTarget()) {
            state.incrementShotsOnTarget();
        }
    }

    /**
     * Execute a CARRY action (DRIBBLE).
     * Carrier moves forward toward target.
     */
    private void executeCarry(MatchState state, DecisionOption decision) {
        Player carrier = state.getCarrier();
        if (carrier == null) return;

        // Calculate carry target - forward direction
        Position current = carrier.getPosition();
        boolean home = "HOME".equals(carrier.getTeam());
        double forwardDelta = home ? 0.5 : -0.5;

        Position carryTarget = new Position(
            SimUtils.clamp(current.getRow() + forwardDelta, 1.0, 7.0),
            current.getColumn() // same column for simplicity
        );

        // Set carrier target
        carrier.setTarget(carryTarget);
    }

    /**
     * Execute a CLEAR action.
     */
    private void executeClear(MatchState state, DecisionOption decision) {
        Player carrier = state.getCarrier();
        if (carrier == null) return;

        // Clear direction - away from opponent goal
        Position current = carrier.getPosition();
        boolean home = "HOME".equals(carrier.getTeam());
        double clearDelta = home ? -1.5 : 1.5;

        Position clearTarget = new Position(
            SimUtils.clamp(current.getRow() + clearDelta, 1.0, 7.0),
            current.getColumn()
        );

        carrier.setTarget(clearTarget);
    }

    private double calculatePassSpeed(double passingSkill) {
        return Math.min(1.5, 0.5 + (passingSkill / 20.0) * 1.0);
    }
}