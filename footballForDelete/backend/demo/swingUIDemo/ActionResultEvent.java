package org.example.footballmanager.demo.swingUIDemo;

/**
 * Immutable record of an action result. Decision and execution fields are
 * retained so a replay/statistics consumer does not need to infer them later.
 */
public record ActionResultEvent(
        long tick,
        int round,
        String actionId,
        Action.Type actionType,
        ActionOutcome outcome,
        String actorId,
        String targetPlayerId,
        Position intendedTarget,
        Position actualTarget,
        int executionSkill,
        Ball.BallState previousBallState,
        Ball.BallState newBallState,
        String carrierId,
        String duelWinnerId
) implements SimulationEvent {
    @Override
    public Type type() {
        return Type.ACTION_RESULT;
    }
}
