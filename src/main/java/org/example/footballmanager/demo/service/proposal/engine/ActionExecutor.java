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

        // Pass into the OPENING — the receiver's position nudged away from its
        // nearest opponent (demo/service "openingTarget" model). Passing AT the
        // receiver's occupied body lets the marker on the segment intercept;
        // serving into ~0.5 cell of free space the receiver runs onto completes.
        Position aimedTarget = openingTarget(state, receiver);

        // Pass launch speed is the POSSESSOR-RELATIVE skill speed (demo/service
        // model): the passer plays at the speed his passing skill can handle, so
        // accuracy stays high (deviation comes only from overspeed). Short
        // passes stay on the ground; anything at/over 1.5 cells is lofted (air
        // decel) so it can cover the distance without dying mid-flight.
        double dist = SimUtils.distance(carrier.getPosition(), receiver.getPosition());
        boolean airborne = dist >= 1.5;
        double desiredSpeed = ExecutionQuality.ballSpeedForSkill(carrier.getSkills().passing());

        // ExecutionQuality gives deviated aim + launch speed + spin
        ExecutionQuality.PassResult result = ExecutionQuality.evaluatePass(
                carrier, carrier.getPosition(), aimedTarget, receiver, desiredSpeed);

        // Launch the ball toward the deviated aim
        state.getBallEngine().launch(ball, carrier.getPosition(),
                result.getActualTarget(), result.getSpeed(), airborne, result.getSpin());

        // Carrier stops running; receiver holds position during flight
        carrier.setTarget(null);
        receiver.setTarget(null);

        // Remember who should receive — and where the ball will land, so the
        // receiver can RUN ONTO the pass during flight (demo/service model).
        state.setPendingReceiver(receiver);
        state.setReceivePoint(result.getActualTarget());
        state.setCarrier(null);
        state.setLastTouchTeam(carrier.getTeam());
        state.setLastTouchPlayer(carrier);

        carrier.incrementConsecutiveCarries();
        state.incrementPassAttempts();
        // passesCompleted incremented on actual RECEIVE in orchestrator
    }

    /** Compute the pass aim point: receiver's position nudged away from its
     *  nearest opponent so the ball lands in free space, not on the marker. */
    private Position openingTarget(MatchState state, Player receiver) {
        Position pos = receiver.getPosition();
        Player nearest = null;
        double bestD = Double.MAX_VALUE;
        for (Player p : state.getPlayers()) {
            if (p.equals(receiver) || p.isUnavailable()) continue;
            if (p.getTeam().equals(receiver.getTeam())) continue;
            double d = SimUtils.distance(pos, p.getPosition());
            if (d < bestD) { bestD = d; nearest = p; }
        }
        if (nearest == null) return pos;
        // Nudge up to 0.5 cells directly away from the nearest opponent.
        double dr = pos.getRow() - nearest.getPosition().getRow();
        double dc = pos.getColumn() - nearest.getPosition().getColumn();
        double len = Math.hypot(dr, dc);
        if (len < 1e-9) return pos;
        double nudge = Math.min(0.5, bestD / 2.0);
        return new Position(
                pos.getRow() + dr / len * nudge,
                pos.getColumn() + dc / len * nudge
        );
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

        // Carry target capped half a cell BEFORE the opponent goal line so the
        // carrier never dribbles onto the line; the re-decision then fires a shot.
        double maxRow = home ? 7.5 : 8.0;
        double minRow = home ? 1.0 : 1.5;
        Position carryTarget = new Position(
                SimUtils.clamp(current.getRow() + forwardDelta, home ? 1.0 : 1.5, home ? 7.5 : 8.0),
                current.getColumn()
        );

        carrier.setTarget(carryTarget);
    }

    /** Execute a CLEAR action — launch the ball away from danger. */
    private void executeClear(MatchState state, DecisionOption decision) {
        Player carrier = state.getCarrier();
        if (carrier == null) return;

        // Clear direction — away from OWN goal (long air kick).
        // HOME defends row 1.0 so clears UP (+row, toward AWAY goal);
        // AWAY defends row 8.0 so clears DOWN (-row, toward HOME goal).
        // (This sign was inverted: HOME cleared to row 1.0 = into his own net.)
        Position current = carrier.getPosition();
        boolean home = "HOME".equals(carrier.getTeam());
        double clearDelta = home ? +2.0 : -2.0; // deeper kick

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
}