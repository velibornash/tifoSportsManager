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
        // Track the last executed action for shot-outcome attribution (the
        // carrier is null during flight, so the orchestrator reads this).
        state.setLastActionType(type);
        if (type == ActionType.SHOT && carrier != null) {
            state.setLastShooter(carrier);
        }

        // Shared action logger — every executed action writes a trace line so the
        // app log shows the full decision→execution chain and the empty-ball
        // (no carrier) phase is attributable to the launch that produced it.
        if (state.getActionLogger() != null) {
            Player target = decision.getTarget();
            state.getActionLogger().log("EXE",
                    "EXEC " + type
                            + " by " + carrier.getLabel() + "(" + carrier.getRole() + ")"
                            + " at " + state.getActionLogger().p(carrier.getPosition())
                            + " -> " + state.getActionLogger().p(state.getBall().getPosition())
                            + (target != null
                            ? " target " + target.getLabel()
                            + "(" + target.getRole() + ")"
                            + state.getActionLogger().p(target.getPosition())
                            : "")
                            + " score=" + String.format("%6.1f", decision.getScore())
                            + " [" + decision.getReason() + "]");
        }
    }

    /** Execute a PASS action. */
    private void executePass(MatchState state, DecisionOption decision) {
        Player carrier = state.getCarrier();
        Player receiver = decision.getTarget();
        if (carrier == null || receiver == null) return;

        // Ball snapped to carrier already by orchestrator before decision
        Ball ball = state.getBall();

        // KICKOFF (user rule 2026-09-23): the kickoff pass must be EXACT — it
        // goes straight AT the target player (no opening-target nudge, zero
        // deviation) and launches at MAX ball speed (1.5 cells/tick) so NO
        // opponent can beat the ball (max non-carrier pace is 0.75 cells/tick).
        boolean kickoff = state.isKickoffPending();

        // Pass into the OPENING — the receiver's position nudged away from its
        // nearest opponent (demo/service "openingTarget" model). Passing AT the
        // receiver's occupied body lets the marker on the segment intercept;
        // serving into ~0.5 cell of free space the receiver runs onto completes.
        Position aimedTarget = kickoff ? receiver.getPosition() : openingTarget(state, receiver);

        // Pass launch speed is the POSSESSOR-RELATIVE skill speed (demo/service
        // model): the passer plays at the speed his passing skill can handle, so
        // accuracy stays high (deviation comes only from overspeed). Short
        // passes stay on the ground; anything at/over 1.5 cells is lofted (air
        // decel) so it can cover the distance without dying mid-flight.
        double dist = SimUtils.distance(carrier.getPosition(), receiver.getPosition());
        boolean airborne = dist >= 1.5;
        double desiredSpeed = ExecutionQuality.ballSpeedForSkill(carrier.getSkills().passing());

        // ExecutionQuality gives deviated aim + launch speed + spin. The kickoff
        // pass bypasses it entirely: exact receiver position, max speed, no spin.
        ExecutionQuality.PassResult result = kickoff
                ? new ExecutionQuality.PassResult(receiver.getPosition(),
                        BallPhysicsEngine.MAX_BALL_SPEED, 0.0,
                        (int) carrier.getSkills().passing(), 0.0)
                : ExecutionQuality.evaluatePass(
                        carrier, carrier.getPosition(), aimedTarget, receiver, desiredSpeed);

        // Launch the ball toward the deviated aim
        state.getBallEngine().launch(ball, carrier.getPosition(),
                result.getActualTarget(), result.getSpeed(), airborne, result.getSpin());

        // The kickoff is consumed the moment the ball leaves the center spot —
        // the receiver's own decision (next tick) is a NORMAL decision, no more
        // kickoff logic. OffsideService only skips the check during this flight.
        if (kickoff) {
            state.setKickoffPending(false);
        }

        // Carrier stops running; receiver holds position during flight
        carrier.setTarget(null);
        receiver.setTarget(null);
        // RIGID RULE (user 2026-09-23): the striker must NOT move from his
        // position in the tick he struck the ball — the ball leaves his feet
        // next tick. Rooted for the strike tick (movement resumes on the
        // following tick, when the ball is already visibly in flight).
        carrier.setStrikeHoldTicks(1);

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
        // RIGID RULE (user 2026-09-23): the shooter stays rooted the tick he
        // strikes — the ball leaves next tick.
        carrier.setStrikeHoldTicks(1);
        state.setCarrier(null);
        state.setLastTouchTeam(carrier.getTeam());
        state.setLastTouchPlayer(carrier);

        carrier.incrementConsecutiveCarries();
        state.incrementShots();
        if (result.isOnTarget()) {
            state.incrementShotsOnTarget();
        }
        state.setLastShotOnTarget(result.isOnTarget());
    }

    /** Execute a CARRY (dribble) action. */
    private void executeCarry(MatchState state, DecisionOption decision) {
        Player carrier = state.getCarrier();
        if (carrier == null) return;

        // Calculate carry target — 3 cells forward (one smooth continuous run,
        // matching /demo/service: a 0.5-cell nudge + per-tick re-decision made the
        // carrier shuffle/stop every other tick). A gentle inward column drift
        // keeps wingers from hugging the touchline into the corner.
        Position current = carrier.getPosition();
        boolean home = "HOME".equals(carrier.getTeam());
        double forwardDelta = home ? 3.0 : -3.0;

        // Carry target capped half a cell BEFORE the opponent goal line so the
        // carrier never dribbles onto the line; the re-decision then fires a shot.
        double col = current.getColumn();
        double inward = col < 3.0 ? 0.6 : col > 5.0 ? -0.6 : 0.0;
        Position carryTarget = new Position(
                SimUtils.clamp(current.getRow() + forwardDelta, home ? 1.0 : 1.5, home ? 7.5 : 8.0),
                SimUtils.clamp(col + inward, 1.0, 7.0)
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
        // RIGID RULE (user 2026-09-23): the clearer stays rooted the tick he
        // strikes — the ball leaves next tick.
        carrier.setStrikeHoldTicks(1);
        state.setCarrier(null);
        state.setLastTouchTeam(carrier.getTeam());
        state.setLastTouchPlayer(carrier);
    }
}