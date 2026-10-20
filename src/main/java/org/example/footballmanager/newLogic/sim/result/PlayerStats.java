package org.example.footballmanager.newLogic.sim.result;

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
    /**
     * Duels this player <em>lost</em>. Not tackles: the engine does not record who was the tackler, so
     * a number labelled "tackles" would be a guess. The old collector credited this from the losing
     * side of every duel, so a striker dispossessed eight times was shown eight tackles - and rated
     * for them.
     */
    int duelsLost,
    int duelsWon,
    int throughBalls,
    int crosses,
    int centers,
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