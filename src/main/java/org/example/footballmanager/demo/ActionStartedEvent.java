package org.example.footballmanager.demo;

/** Immutable record of the decision/action start; execution is recorded separately. */
public record ActionStartedEvent(
        long tick,
        int round,
        String actionId,
        Action.Type actionType,
        String actorId,
        String description
) implements SimulationEvent {
    @Override
    public Type type() { return Type.ACTION_STARTED; }
}
