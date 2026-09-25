package org.example.footballmanager.demo.swingUIDemo;

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
        int goalCount,
        int awayGoalCount,
        int matchTicks,
        boolean halfTime,
        boolean matchFinished,
        int passAttempts,
        int passCompletions,
        int shotsOnTarget
) {
    public SimulationSnapshot {
        players = List.copyOf(players);
    }
}
