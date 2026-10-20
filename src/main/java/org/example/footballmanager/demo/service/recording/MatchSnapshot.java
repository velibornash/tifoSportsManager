package org.example.footballmanager.demo.service.recording;

import org.example.footballmanager.demo.service.model.Ball;
import org.example.footballmanager.demo.service.model.Position;

import java.util.List;

/**
 * Complete scene snapshot at a specific tick for replay.
 */
public record MatchSnapshot(
        long tick,
        int round,
        List<PlayerSnapshot> players,
        Position ballPosition,
        Position ballTarget,
        Ball.BallState ballState,
        String ballCarrierId,
        String actionId,
        String actionType,
        String actionActingPlayerId,
        String actionTargetPlayerId,
        Position actionIntendedTarget,
        Position actionActualTarget,
        String status,
        int goalCount,
        int awayGoalCount,
        int matchTicks,
        boolean halfTime,
        boolean matchFinished,
        int passAttempts,
        int passCompletions,
        int shotsOnTarget
) {}
