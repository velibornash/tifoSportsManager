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
    private int homePossessionTicks = 0;
    private int awayPossessionTicks = 0;
    private int thruAttemptsHome = 0, thruAttemptsAway = 0;
    private int thruCompletedHome = 0, thruCompletedAway = 0;
    private int cornerFromPassCount = 0;
    private int interceptionCount = 0, looseBallCount = 0;
    private int passInterceptionCount = 0;
    private int passOutOfBoundsCount = 0;
    private int savesHome = 0, savesAway = 0;
    private int blocksHome = 0, blocksAway = 0;
    private int deflectionsHome = 0, deflectionsAway = 0;
    private int clearancesHome = 0, clearancesAway = 0;

    public MatchStatsCollector(String homeTeam, String awayTeam) {
        teamStats.put("HOME", new TeamStatsAccumulator(homeTeam));
        teamStats.put("AWAY", new TeamStatsAccumulator(awayTeam));
        teamStats.put(homeTeam, teamStats.get("HOME"));
        teamStats.put(awayTeam, teamStats.get("AWAY"));
    }

    public void registerPlayers(List<Player> players) {
        for (Player p : players) {
            playerStats.put(p.getId(), new PlayerStatsAccumulator(p));
        }
    }

    public void registerPlayer(Player player) {
        playerStats.put(player.getId(), new PlayerStatsAccumulator(player));
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

    /** Mark a previously counted shot as on-target (called when ball reaches target). */
    public void markShotOnTarget(String team, String shooterId) {
        TeamStatsAccumulator ts = teamStats.get(team);
        ts.shotsOnTarget++;

        PlayerStatsAccumulator ps = playerStats.get(shooterId);
        if (ps != null) ps.shotsOnTarget++;
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

    /** Record possession tick for a team. */
    public void addPossessionTick(String team) {
        if ("HOME".equals(team)) homePossessionTicks++;
        else awayPossessionTicks++;
    }

    /** Record a throw-in. */
    public void onThrowIn(String team) {
        teamStats.get(team).throwIns++;
    }

    /** Record a goal kick. */
    public void onGoalKick(String team) {
        teamStats.get(team).goalKicks++;
    }

    /** Record a goalkeeper save. */
    public void onSave(String team) {
        if ("HOME".equals(team)) savesHome++;
        else savesAway++;
    }

    /** Record a shot block by an outfield defender. */
    public void onBlock(String team) {
        if ("HOME".equals(team)) blocksHome++;
        else blocksAway++;
    }

    /** Record a deflection (defender touches a pass/shot but doesn't cleanly intercept). */
    public void onDeflection(String team) {
        if ("HOME".equals(team)) deflectionsHome++;
        else deflectionsAway++;
    }

    /** Record a clearance (defensive boot upfield). */
    public void onClearance(String team) {
        if ("HOME".equals(team)) clearancesHome++;
        else clearancesAway++;
    }

    /** Get saves count for a team. */
    public int getSaves(String team) { return "HOME".equals(team) ? savesHome : savesAway; }

    /** Get blocks count for a team. */
    public int getBlocks(String team) { return "HOME".equals(team) ? blocksHome : blocksAway; }

    /** Get deflections count for a team. */
    public int getDeflections(String team) { return "HOME".equals(team) ? deflectionsHome : deflectionsAway; }

    /** Get clearances count for a team. */
    public int getClearances(String team) { return "HOME".equals(team) ? clearancesHome : clearancesAway; }

    /** Record an interception. */
    public void onInterception(String playerId) {
        PlayerStatsAccumulator ps = playerStats.get(playerId);
        if (ps != null) ps.interceptions++;
        interceptionCount++;
    }

    /** Record a THRU pass attempt. */
    public void onThruAttempt(String team) {
        if ("HOME".equals(team)) thruAttemptsHome++;
        else thruAttemptsAway++;
        teamStats.get(team).thruAttempts++;
    }

    /** Record a completed THRU pass. */
    public void onThruCompleted(String team) {
        if ("HOME".equals(team)) thruCompletedHome++;
        else thruCompletedAway++;
        teamStats.get(team).thruCompleted++;
    }

    /** Record a loose ball (pass not received). */
    public void onLooseBall() {
        looseBallCount++;
    }

    /** Record a pass going out of bounds (throw-in, goal kick, or corner). */
    public void onPassOutOfBounds() {
        passOutOfBoundsCount++;
    }

    /** Record a pass interception (defender reaches the ball before the receiver). */
    public void onPassInterception() {
        passInterceptionCount++;
    }

    /** Get pass interception count (from findPassInterceptor, not duels). */
    public int getPassInterceptionCount() { return passInterceptionCount; }

    /** Record a corner from a pass going out of bounds. */
    public void onCornerFromPass() {
        cornerFromPassCount++;
    }

    /** Get total THRU pass attempts across both teams. */
    public int getThruAttempts() { return thruAttemptsHome + thruAttemptsAway; }
    /** Get total completed THRU passes. */
    public int getThruCompleted() { return thruCompletedHome + thruCompletedAway; }
    /** Get corner-from-pass count. */
    public int getCornerFromPassCount() { return cornerFromPassCount; }
    /** Get interception count (from passes). */
    public int getInterceptionCount() { return interceptionCount; }
    /** Get loose ball count (pass not received). */
    public int getLooseBallCount() { return looseBallCount; }
    /** Get pass out-of-bounds count. */
    public int getPassOutOfBoundsCount() { return passOutOfBoundsCount; }

    /** Get the last passer for assist tracking. */
    public String getLastPasserId() { return lastPasserId; }
    public String getLastPasserTeam() { return lastPasserTeam; }

    /** Build final team stats. */
    public TeamMatchStats buildTeamStats(String team, int totalTicks, int matchMinutes) {
        TeamStatsAccumulator ts = teamStats.get(team);
        if (ts == null) return new TeamMatchStats(team, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        boolean isHome = ts == teamStats.get("HOME");

        double possession = 0;
        if (homePossessionTicks + awayPossessionTicks > 0) {
            double denom = homePossessionTicks + awayPossessionTicks;
            possession = isHome
                    ? 100.0 * homePossessionTicks / denom
                    : 100.0 * awayPossessionTicks / denom;
        }

        return new TeamMatchStats(
                team, ts.goals, ts.shots, ts.shotsOnTarget,
                ts.passesAttempted, ts.passesCompleted,
                ts.fouls, ts.penalties, ts.yellowCards, ts.redCards,
                ts.corners, ts.offsides, possession,
                ts.thruAttempts, ts.thruCompleted,
                interceptionCount, passInterceptionCount,
                looseBallCount, ts.throwIns, ts.goalKicks, cornerFromPassCount,
                passOutOfBoundsCount,
                isHome ? savesHome : savesAway,
                isHome ? blocksHome : blocksAway,
                isHome ? deflectionsHome : deflectionsAway,
                isHome ? clearancesHome : clearancesAway
        );
    }

    /** Build final player stats with rating. */
    public List<PlayerMatchStats> buildPlayerStats(String team, int matchMinutes) {
        TeamStatsAccumulator ts = teamStats.get(team);
        if (ts == null) return Collections.emptyList();
        boolean isHome = ts == teamStats.get("HOME");
        String side = isHome ? "HOME" : "AWAY";
        List<PlayerMatchStats> result = new ArrayList<>();
        for (PlayerStatsAccumulator ps : playerStats.values()) {
            if (!ps.player.getTeam().equals(side)) continue;
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

    /** Get overall possession percentage for HOME team. */
    public double getHomePossessionPct() {
        if (homePossessionTicks + awayPossessionTicks == 0) return 50.0;
        return 100.0 * homePossessionTicks / (homePossessionTicks + awayPossessionTicks);
    }

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
        int thruAttempts, thruCompleted;
        int goalKicks, throwIns;

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
