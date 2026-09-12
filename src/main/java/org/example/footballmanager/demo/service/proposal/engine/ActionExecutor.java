package org.example.footballmanager.demo.service.proposal.engine;

import org.example.footballmanager.demo.service.proposal.model.*;
import org.example.footballmanager.demo.service.proposal.util.SimUtils;

/**
 * Execution engine — ONLY executes the chosen action.
 * Takes a DecisionOption and performs the physical execution.
 * No decision-making, no override rules.
 * Core principle: This engine NEVER overrides the decision engine's choice.
 * If decision says PASS, it passes. If decision says SHOT, it shoots.
 */
public class ActionExecutor {

    /** Execute a decision. This method ONLY executes — no override rules. */
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
                break;
        }

        state.setLastDecisionScore(decision.getScore());
        state.setLastDecisionReason(decision.getReason());
    }

    /** Execute a PASS action. */
    private void executePass(MatchState state, DecisionOption decision) {
        Player carrier = state.getCarrier();
        Player receiver = decision.getTarget();
        if (carrier == null || receiver == null) return;

        // Ball snapped to carrier already by orchestrator before decision
        Ball ball = state.getBall();

        // Calculate pass speed based on passing skill
        double passingSkill = carrier.getSkills().passing();
        double desiredSpeed = calculatePassSpeed(passingSkill);

        // ExecutionQuality gives deviated aim + launch speed + spin
        ExecutionQuality.PassResult result = ExecutionQuality.evaluatePass(
                carrier, carrier.getPosition(), receiver.getPosition(), receiver, desiredSpeed);

        // Launch the ball toward the deviated aim
        state.getBallEngine().launch(ball, carrier.getPosition(),
                result.getActualTarget(), result.getSpeed(), false, result.getSpin());

        // Carrier stops running; receiver holds position during flight
        carrier.setTarget(null);
        receiver.setTarget(null);

        // Remember who should receive
        state.setPendingReceiver(receiver);
        state.setCarrier(null);
        state.setLastTouchTeam(carrier.getTeam());
        state.setLastTouchPlayer(carrier);

        carrier.incrementConsecutiveCarries();
        state.incrementPassAttempts();
        // passesCompleted incremented on actual RECEIVE in orchestrator
    }

    /** Execute a SHOT action. */
    private void executeShot(MatchState state, DecisionOption decision) {
        Player carrier = state.getCarrier();
        if (carrier == null) return;

        Position goal = ActionEngine.goalPositionFor(carrier.getTeam());
        double strikerSkill = carrier.getSkills().striker();

        // ExecutionQuality gives deviated aim + launch speed + spin + onTarget
        ExecutionQuality.ShotResult result = ExecutionQuality.evaluateShot(
                goal, (int) strikerSkill, 0.0, carrier.getPosition());

        // Launch the ball
        state.getBallEngine().launch(state.getBall(), carrier.getPosition(),
                result.getActualTarget(), result.getSpeed(), true, result.getSpin());

        // Carrier stops running
        carrier.setTarget(null);
        state.setCarrier(null);
        state.setLastTouchTeam(carrier.getTeam());
        state.setLastTouchPlayer(carrier);

        carrier.incrementConsecutiveCarries();
        state.incrementShots();
        if (result.isOnTarget()) {
            state.incrementShotsOnTarget();
        }
    }

    /** Execute a CARRY (dribble) action. */
    private void executeCarry(MatchState state, DecisionOption decision) {
        Player carrier = state.getCarrier();
        if (carrier == null) return;

        // Calculate carry target — forward direction
        Position current = carrier.getPosition();
        boolean home = "HOME".equals(carrier.getTeam());
        double forwardDelta = home ? 0.5 : -0.5;

        Position carryTarget = new Position(
                SimUtils.clamp(current.getRow() + forwardDelta, 1.0, 7.0),
                current.getColumn()
        );

        carrier.setTarget(carryTarget);
    }

    /** Execute a CLEAR action — launch the ball away from danger. */
    private void executeClear(MatchState state, DecisionOption decision) {
        Player carrier = state.getCarrier();
        if (carrier == null) return;

        // Clear direction — away from opponent goal (long air kick)
        Position current = carrier.getPosition();
        boolean home = "HOME".equals(carrier.getTeam());
        double clearDelta = home ? -2.0 : 2.0; // deeper kick

        Position clearTarget = new Position(
                SimUtils.clamp(current.getRow() + clearDelta, 1.0, 7.0),
                current.getColumn()
        );

        // Launch as an air ball (clearance) at high speed
        Ball ball = state.getBall();
        state.getBallEngine().launch(ball, current, clearTarget,
                BallPhysicsEngine.MAX_BALL_SPEED, true, 0.2);

        carrier.setTarget(null);
        state.setCarrier(null);
        state.setLastTouchTeam(carrier.getTeam());
        state.setLastTouchPlayer(carrier);
    }

    private double calculatePassSpeed(double passingSkill) {
        return Math.min(BallPhysicsEngine.MAX_BALL_SPEED,
                BallPhysicsEngine.MIN_LAUNCH_SPEED + (passingSkill / 20.0) * 0.75);
    }
}