package org.example.footballmanager.demo.service.proposal.result;

/** Per-team match statistics. */
public record TeamStats(
    String teamName,
    int goals,
    int shots,
    int shotsOnTarget,
    int passesAttempted,
    int passesCompleted,
    int dribbles,
    int clearances,
    int interceptions,
    int deflections,
    int blocks,
    int saves,
    int corners,
    int goalKicks,
    int throwIns,
    int offsides,
    int fouls,
    int yellowCards,
    int redCards,
    double possessionPercent,
    double avgPossessionTicks,
    int longestPossessionTicks
) {
    public int passAccuracy() {
        if (passesAttempted == 0) return 0;
        return (int) Math.round(100.0 * passesCompleted / passesAttempted);
    }

    public String summary() {
        return String.format("%s: %d goals, shots %d/%d, passes %d/%d (%d%%), possession %.0f%% (chain avg %.1f)",
                teamName, goals, shotsOnTarget, shots,
                passesCompleted, passesAttempted, passAccuracy(), possessionPercent,
                avgPossessionTicks);
    }
}