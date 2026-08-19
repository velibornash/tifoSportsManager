package org.example.footballmanager.demo.service.result;

/**
 * Per-player match statistics.
 */
public record PlayerMatchStats(
    String playerId,
    String playerName,
    String teamName,
    String role,
    int goals,
    int assists,
    int shots,
    int shotsOnTarget,
    int passesAttempted,
    int passesCompleted,
    int tackles,
    int interceptions,
    int foulsCommitted,
    int yellowCards,
    int redCards,
    int minutesPlayed,
    double rating
) {
    public int passAccuracy() {
        if (passesAttempted == 0) return 0;
        return (int) Math.round(100.0 * passesCompleted / passesAttempted);
    }
}
