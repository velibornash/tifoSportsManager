package org.example.footballmanager.demo.service.result;

import org.example.footballmanager.demo.service.engine.ActionEngine;
import org.example.footballmanager.demo.service.model.*;
import org.example.footballmanager.demo.service.recording.MatchEvent;

import java.util.*;

/**
 * Collects all match statistics during simulation.
 * Tracks per-team and per-player stats from events and actions.
 */
public class MatchStatsCollector {

    private final Map<String, TeamStatsAccumulator> teamStats = new LinkedHashMap<>();
    private final Map<String, PlayerStatsAccumulator> playerStats = new LinkedHashMap<>();
    private final List<GoalDetail> goals = new ArrayList<>();

    private String lastPasserId = null;
    private String lastPasserTeam = null;

    public MatchStatsCollector(String homeTeam, String awayTeam) {
        teamStats.put(homeTeam, new TeamStatsAccumulator(homeTeam));
        teamStats.put(awayTeam, new TeamStatsAccumulator(awayTeam));
    }

    public void registerPlayers(List<Player> players) {
        for (Player p : players) {
            playerStats.put(p.getId(), new PlayerStatsAccumulator(p));
        }
    }

    /** Record a pass attempt. */
    public void onPassAttempt(String team, String passerId) {
        teamStats.get(team).passesAttempted++;
        PlayerStatsAccumulator ps = playerStats.get(passerId);
        if (ps != null) ps.passesAttempted++;
        lastPasserId = passerId;
        lastPasserTeam = team;
    }

    /** Record a completed pass. */
    public void onPassCompleted(String team, String passerId, String receiverId) {
        teamStats.get(team).passesCompleted++;
        PlayerStatsAccumulator ps = playerStats.get(passerId);
        if (ps != null) ps.passesCompleted++;
    }

    /** Record a shot. */
    public void onShot(String team, String shooterId, boolean onTarget) {
        TeamStatsAccumulator ts = teamStats.get(team);
        ts.shots++;
        if (onTarget) ts.shotsOnTarget++;

        PlayerStatsAccumulator ps = playerStats.get(shooterId);
        if (ps != null) {
            ps.shots++;
            if (onTarget) ps.shotsOnTarget++;
        }
    }

    /** Record a goal. */
    public void onGoal(String team, String scorerId, String scorerName,
                        String assistantId, String assistantName,
                        int minute, int homeScore, int awayScore) {
        teamStats.get(team).goals++;

        PlayerStatsAccumulator scorer = playerStats.get(scorerId);
        if (scorer != null) scorer.goals++;

        if (assistantId != null) {
            PlayerStatsAccumulator assist = playerStats.get(assistantId);
            if (assist != null) assist.assists++;
        }

        goals.add(new GoalDetail(minute, scorerId, scorerName, team,
                assistantId, assistantName, homeScore, awayScore,
                scorerName + " scores for " + team + " (" + homeScore + "-" + awayScore + ")"));

        lastPasserId = null;
        lastPasserTeam = null;
    }

    /** Record a foul. */
    public void onFoul(String team, String playerId) {
        teamStats.get(team).fouls++;
        PlayerStatsAccumulator ps = playerStats.get(playerId);
        if (ps != null) ps.foulsCommitted++;
    }

    /** Record a yellow card. */
    public void onYellowCard(String team, String playerId) {
        teamStats.get(team).yellowCards++;
        PlayerStatsAccumulator ps = playerStats.get(playerId);
        if (ps != null) ps.yellowCards++;
    }

    /** Record a red card. */
    public void onRedCard(String team, String playerId) {
        teamStats.get(team).redCards++;
        PlayerStatsAccumulator ps = playerStats.get(playerId);
        if (ps != null) ps.redCards++;
    }

    /** Record a corner. */
    public void onCorner(String team) {
        teamStats.get(team).corners++;
    }

    /** Record an offside. */
    public void onOffside(String team) {
        teamStats.get(team).offsides++;
    }

    /** Record a penalty. */
    public void onPenalty(String team) {
        teamStats.get(team).penalties++;
    }

    /** Record a tackle. */
    public void onTackle(String playerId) {
        PlayerStatsAccumulator ps = playerStats.get(playerId);
        if (ps != null) ps.tackles++;
    }

    /** Record an interception. */
    public void onInterception(String playerId) {
        PlayerStatsAccumulator ps = playerStats.get(playerId);
        if (ps != null) ps.interceptions++;
    }

    /** Get the last passer for assist tracking. */
    public String getLastPasserId() { return lastPasserId; }
    public String getLastPasserTeam() { return lastPasserTeam; }

    /** Build final team stats. */
    public TeamMatchStats buildTeamStats(String team, int totalTicks, int matchMinutes) {
        TeamStatsAccumulator ts = teamStats.get(team);
        String opponent = teamStats.keySet().stream()
                .filter(t -> !t.equals(team)).findFirst().orElse("");

        double possession = 0;
        int totalPasses = ts.passesAttempted + teamStats.get(opponent).passesAttempted;
        if (totalPasses > 0) {
            possession = 100.0 * ts.passesAttempted / totalPasses;
        }

        return new TeamMatchStats(
                team, ts.goals, ts.shots, ts.shotsOnTarget,
                ts.passesAttempted, ts.passesCompleted,
                ts.fouls, ts.penalties, ts.yellowCards, ts.redCards,
                ts.corners, ts.offsides, possession
        );
    }

    /** Build final player stats with rating. */
    public List<PlayerMatchStats> buildPlayerStats(String team, int matchMinutes) {
        List<PlayerMatchStats> result = new ArrayList<>();
        for (PlayerStatsAccumulator ps : playerStats.values()) {
            if (!ps.player.getTeam().equals(team)) continue;
            double rating = calculateRating(ps, matchMinutes);
            result.add(new PlayerMatchStats(
                    ps.player.getId(), ps.player.getLabel(), team, ps.player.getRole(),
                    ps.goals, ps.assists, ps.shots, ps.shotsOnTarget,
                    ps.passesAttempted, ps.passesCompleted,
                    ps.tackles, ps.interceptions, ps.foulsCommitted,
                    ps.yellowCards, ps.redCards, matchMinutes, rating
            ));
        }
        result.sort((a, b) -> Double.compare(b.rating(), a.rating()));
        return result;
    }

    /** Build goal details list. */
    public List<GoalDetail> getGoals() { return Collections.unmodifiableList(goals); }

    private double calculateRating(PlayerStatsAccumulator ps, int matchMinutes) {
        double rating = 6.0;
        rating += ps.goals * 1.5;
        rating += ps.assists * 1.0;
        rating += ps.passesCompleted * 0.02;
        rating -= ps.foulsCommitted * 0.3;
        rating -= ps.yellowCards * 0.5;
        rating -= ps.redCards * 2.0;
        rating += ps.tackles * 0.2;
        rating += ps.interceptions * 0.3;
        if (ps.player.getRole().equals("GK")) {
            rating += ps.interceptions * 0.5;
        }
        return Math.max(1.0, Math.min(10.0, rating));
    }

    // --- Inner accumulators ---

    private static class TeamStatsAccumulator {
        final String team;
        int goals, shots, shotsOnTarget;
        int passesAttempted, passesCompleted;
        int fouls, penalties, yellowCards, redCards, corners, offsides;

        TeamStatsAccumulator(String team) { this.team = team; }
    }

    private static class PlayerStatsAccumulator {
        final Player player;
        int goals, assists, shots, shotsOnTarget;
        int passesAttempted, passesCompleted;
        int tackles, interceptions, foulsCommitted;
        int yellowCards, redCards;

        PlayerStatsAccumulator(Player player) { this.player = player; }
    }
}
