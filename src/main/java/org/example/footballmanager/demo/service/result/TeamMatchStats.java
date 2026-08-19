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
    double possessionPercent
) {
    public int passAccuracy() {
        if (passesAttempted == 0) return 0;
        return (int) Math.round(100.0 * passesCompleted / passesAttempted);
    }

    public String summary() {
        return String.format(
            "%s: %d-%d (shots %d/%d, passes %d/%d (%d%%), fouls %d, corners %d, cards %d/%d)",
            teamName, goals, 0, shotsOnTarget, shots,
            passesCompleted, passesAttempted, passAccuracy(),
            fouls, corners, yellowCards, redCards
        );
    }
}
