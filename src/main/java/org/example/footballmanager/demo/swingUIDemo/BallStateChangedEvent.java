package org.example.footballmanager.demo.swingUIDemo;

/** Explicitly recorded ball-state transition; not reconstructed from runtime fields. */
public record BallStateChangedEvent(
        long tick,
        int round,
        String actionId,
        Ball.BallState previousState,
        Ball.BallState newState,
        Position position,
        String carrierId,
        String reason
) implements SimulationEvent {
    @Override
    public Type type() {
        return Type.BALL_STATE_CHANGED;
    }
}
