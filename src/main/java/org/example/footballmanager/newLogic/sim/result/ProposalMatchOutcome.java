package org.example.footballmanager.newLogic.sim.result;

import java.util.List;

/**
 * Complete post-match outcome of a proposal-engine match — carries every item
 * the "report / izveštaj" needs so a future adapter can map it 1:1 onto the
 * newLogic match/report model: score, possession, expected goals, formations,
 * full team + per-player statistics, lineups and a typed event timeline.
 */
public record ProposalMatchOutcome(
    String homeTeam,
    String awayTeam,
    int homeGoals,
    int awayGoals,
    long totalTicks,
    int minute,
    String homeFormation,
    String awayFormation,
    double possessionHome,
    double possessionAway,
    double expectedGoalsHome,
    double expectedGoalsAway,
    TeamOutcome homeStats,
    TeamOutcome awayStats,
    List<PlayerOutcome> players,
    List<EventEntry> events,
    String playerOfTheMatchId,
    String playerOfTheMatchName,
    String playerOfTheMatchTeam
) {

    public record TeamOutcome(
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
        double avgRating
    ) {
        public int passAccuracy() {
            if (passesAttempted == 0) return 0;
            return (int) Math.round(100.0 * passesCompleted / passesAttempted);
        }

        public double dominance() {
            return Math.max(0.0, Math.min(100.0, possessionPercent));
        }
    }

    public record PlayerOutcome(
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
    ) {}

    public record EventEntry(
        long tick,
        int minute,
        String type,
        String team,
        String playerId,
        String playerName,
        String description
    ) {}

    public List<PlayerOutcome> players() { return players == null ? List.of() : players; }
    public List<EventEntry> events() { return events == null ? List.of() : events; }
}