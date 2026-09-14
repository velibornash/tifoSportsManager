package org.example.footballmanager.demo.service.proposal.result;

/** Per-player match statistics. */
public record PlayerStats(
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
    int dribbles,
    int clearances,
    int interceptions,
    int deflections,
    int blocks,
    int saves,
    int tackles,
    int duelsWon,
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