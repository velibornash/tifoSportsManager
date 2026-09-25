package org.example.footballmanager.demo.swingUIDemo;

/** Persistable duel lifecycle event, independent of console logging. */
public record DuelEvent(
        long tick,
        int round,
        String actionId,
        Phase phase,
        DuelType duelType,
        String attackerId,
        String defenderId,
        Position contestPosition,
        String winnerId,
        DuelOutcome outcome,
        int attackerPower,
        int defenderPower
) implements SimulationEvent {
    public enum Phase { STARTED, RESOLVED, ENDED }

    @Override
    public Type type() {
        return Type.DUEL;
    }
}
