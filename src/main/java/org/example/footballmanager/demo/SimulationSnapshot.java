package org.example.footballmanager.demo;

import java.util.List;

/** Complete immutable scene state at one animation tick. */
public record SimulationSnapshot(
        long tick,
        int round,
        List<PlayerSnapshot> players,
        Position ballPosition,
        Position ballTarget,
        Ball.BallState ballState,
        String ballCarrierId,
        String activeActionId,
        Action.Type activeActionType,
        String activeActorId,
        String activeTargetPlayerId,
        Position intendedTarget,
        Position actualTarget,
        String status,
        int goalCount
) {
    public SimulationSnapshot {
        players = List.copyOf(players);
    }
}
