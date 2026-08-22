package org.example.footballmanager.demo.service.result;

import java.util.List;

/**
 * Per-team match statistics.
 */
public record TeamMatchStats(
    String teamName,
    int goals,
    int shots,
    int shotsOnTarget,
    int passesAttempted,
    int passesCompleted,
    int fouls,
    int penalties,
    int yellowCards,
    int redCards,
    int corners,
    int offsides,
    double possessionPercent,
    int thruAttempts,
    int thruCompleted,
    int interceptions,
    int passInterceptions,
    int looseBallPasses,
    int throwInCount,
    int goalKickCount,
    int cornerFromPassCount,
    int passOutOfBoundsCount
) {
    public int passAccuracy() {
        if (passesAttempted == 0) return 0;
        return (int) Math.round(100.0 * passesCompleted / passesAttempted);
    }

    public int getThruAttempts() { return thruAttempts; }
    public int getThruCompleted() { return thruCompleted; }
    public int getInterceptionCount() { return interceptions; }
    public int getPassInterceptionCount() { return passInterceptions; }
    public int getLooseBallCount() { return looseBallPasses; }
    public int getThrowInCount() { return throwInCount; }
    public int getGoalKickCount() { return goalKickCount; }
    public int getCornerFromPassCount() { return cornerFromPassCount; }
    public int getPassOutOfBoundsCount() { return passOutOfBoundsCount; }

    public String summary() {
        return String.format(
            "%s: %d-%d (shots %d/%d, passes %d/%d (%d%%), fouls %d, corners %d, cards %d/%d)",
            teamName, goals, 0, shotsOnTarget, shots,
            passesCompleted, passesAttempted, passAccuracy(),
            fouls, corners, yellowCards, redCards
        );
    }
}
