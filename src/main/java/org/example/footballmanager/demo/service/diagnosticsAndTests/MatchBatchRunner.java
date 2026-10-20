package org.example.footballmanager.demo.service.diagnosticsAndTests;

import org.example.footballmanager.demo.service.controller.MatchSimulationController;
import org.example.footballmanager.demo.service.result.*;

import java.util.*;

/**
 * Runs 10 matches and aggregates stats for tuning.
 */
public class MatchBatchRunner {

    public static void main(String[] args) {
        int numMatches = 10;
        if (args.length > 0) {
            try { numMatches = Integer.parseInt(args[0]); } catch (NumberFormatException ignored) {}
        }

        int totalHomeGoals = 0, totalAwayGoals = 0;
        int totalShots = 0, totalShotsOnTarget = 0;
        int totalPassesAttempted = 0, totalPassesCompleted = 0;
        int totalFouls = 0, totalYellowCards = 0, totalRedCards = 0;
        int totalCorners = 0, totalOffsides = 0;
        int totalPenalties = 0;
        double totalHomePossession = 0;
        int totalActions = 0;
        int totalDuels = 0, totalDecisions = 0;
        int totalThruAttempts = 0;
        int totalThruCompleted = 0;
        int totalInterceptions = 0;
        int totalLooseBall = 0;
        int totalOutOfBounds = 0;
        int totalThrowIns = 0;
        int totalGoalKicks = 0;
        int totalCornersFromPass = 0;
        int totalSaves = 0;
        int totalBlocks = 0;
        int totalDeflections = 0;
        int totalClearances = 0;
        int totalGoalsOpenPlay = 0;
        int totalGoalsCross = 0;
        int totalGoalsCenter = 0;
        int totalGoalsCorner = 0;
        int totalGoalsFreeKick = 0;
        int totalGoalsPenalty = 0;
        List<String> scorelines = new ArrayList<>();

        System.out.println("=== BATCH: " + numMatches + " MATCHES ===\n");

        for (int i = 0; i < numMatches; i++) {
            long seed = 1000 + i * 7L;
            MatchSimulator sim = new MatchSimulator(seed);
            long skillSeed = seed;
            var homePlayers = MatchSimulationController.generateTeam("HOME", "Home", skillSeed);
            var awayPlayers = MatchSimulationController.generateTeam("AWAY", "Away", skillSeed);

            MatchResult result = sim.simulate(homePlayers, awayPlayers, "Home", "Away");

            int hg = result.homeGoals(), ag = result.awayGoals();
            totalHomeGoals += hg;
            totalAwayGoals += ag;
            totalShots += result.homeStats().shots() + result.awayStats().shots();
            totalShotsOnTarget += result.homeStats().shotsOnTarget() + result.awayStats().shotsOnTarget();
            totalPassesAttempted += result.homeStats().passesAttempted() + result.awayStats().passesAttempted();
            totalPassesCompleted += result.homeStats().passesCompleted() + result.awayStats().passesCompleted();
            totalFouls += result.homeStats().fouls() + result.awayStats().fouls();
            totalYellowCards += result.homeStats().yellowCards() + result.awayStats().yellowCards();
            totalRedCards += result.homeStats().redCards() + result.awayStats().redCards();
            totalCorners += result.homeStats().corners() + result.awayStats().corners();
            totalOffsides += result.homeStats().offsides() + result.awayStats().offsides();
            totalPenalties += result.homeStats().penalties() + result.awayStats().penalties();
            totalHomePossession += result.homeStats().possessionPercent();

            // Count through-ball attempts and completions from the authoritative stats collector.
            totalThruAttempts += result.homeStats().getThruAttempts() + result.awayStats().getThruAttempts();
            totalThruCompleted += result.homeStats().getThruCompleted() + result.awayStats().getThruCompleted();
            // Global counters (interceptionCount, looseBallCount, passOutOfBoundsCount,
            // cornerFromPassCount) are copied verbatim into BOTH teams' stats in
            // buildTeamStats, so we read them from homeStats only (home == away == global).
            totalInterceptions += result.homeStats().getInterceptionCount();
            totalLooseBall += result.homeStats().getLooseBallCount();
            totalOutOfBounds += result.homeStats().getPassOutOfBoundsCount();
            totalThrowIns += result.homeStats().getThrowInCount() + result.awayStats().getThrowInCount();
            totalGoalKicks += result.homeStats().getGoalKickCount() + result.awayStats().getGoalKickCount();
            totalCornersFromPass += result.homeStats().getCornerFromPassCount();
            totalSaves += result.homeStats().saves() + result.awayStats().saves();
            totalBlocks += result.homeStats().blocks() + result.awayStats().blocks();
            totalDeflections += result.homeStats().deflections() + result.awayStats().deflections();
            totalClearances += result.homeStats().clearances() + result.awayStats().clearances();
            totalGoalsOpenPlay += result.homeStats().getGoalsFromOpenPlay() + result.awayStats().getGoalsFromOpenPlay();
            totalGoalsCross += result.homeStats().getGoalsFromCross() + result.awayStats().getGoalsFromCross();
            totalGoalsCenter += result.homeStats().getGoalsFromCenter() + result.awayStats().getGoalsFromCenter();
            totalGoalsCorner += result.homeStats().getGoalsFromCorner() + result.awayStats().getGoalsFromCorner();
            totalGoalsFreeKick += result.homeStats().getGoalsFromFreeKick() + result.awayStats().getGoalsFromFreeKick();
            totalGoalsPenalty += result.homeStats().getGoalsFromPenalty() + result.awayStats().getGoalsFromPenalty();

            int matchFouls = result.homeStats().fouls() + result.awayStats().fouls();
            int matchCorners = result.homeStats().corners() + result.awayStats().corners();
            int matchYellows = result.homeStats().yellowCards() + result.awayStats().yellowCards();
            int matchReds = result.homeStats().redCards() + result.awayStats().redCards();
            int matchShots = result.homeStats().shots() + result.awayStats().shots();
            int matchShotsOnTarget = result.homeStats().shotsOnTarget() + result.awayStats().shotsOnTarget();
            int matchPassesAttempted = result.homeStats().passesAttempted() + result.awayStats().passesAttempted();
            int matchPassesCompleted = result.homeStats().passesCompleted() + result.awayStats().passesCompleted();
            String scoreline = hg + "-" + ag;
            scorelines.add(scoreline);
            System.out.printf("  Match %2d: %s  (possession %.0f%%-%.0f%%  shots %d/%d  passes %d/%d  fouls %d  corners %d  yellows %d  reds %d)%n",
                    i + 1, scoreline,
                    result.homeStats().possessionPercent(), result.awayStats().possessionPercent(),
                    result.homeStats().shots() + result.awayStats().shots(),
                    result.homeStats().shotsOnTarget() + result.awayStats().shotsOnTarget(),
                    result.homeStats().passesCompleted() + result.awayStats().passesCompleted(),
                    result.homeStats().passesAttempted() + result.awayStats().passesAttempted(),
                    matchFouls, matchCorners, matchYellows, matchReds);
        }

        System.out.println("\n=== AGGREGATE (" + numMatches + " matches) ===");
        System.out.printf("  Goals: %d-%d (total %d, avg %.1f per match)%n",
                totalHomeGoals, totalAwayGoals, totalHomeGoals + totalAwayGoals,
                (totalHomeGoals + totalAwayGoals) / (double) numMatches);
        System.out.printf("  Shots: %d total (%.1f per match)%n", totalShots, totalShots / (double) numMatches);
        System.out.printf("  Shots on target: %d (%.0f%%)%n", totalShotsOnTarget,
                totalShots > 0 ? 100.0 * totalShotsOnTarget / totalShots : 0);
        System.out.printf("  Passes: %d/%d (%.0f%% accuracy)%n", totalPassesCompleted, totalPassesAttempted,
                totalPassesAttempted > 0 ? 100.0 * totalPassesCompleted / totalPassesAttempted : 0);
        System.out.printf("  Fouls: %d (%.1f per match)%n", totalFouls, totalFouls / (double) numMatches);
        System.out.printf("  Yellow cards: %d (%.1f per match)  Red cards: %d (%.1f per match)%n",
                totalYellowCards, totalYellowCards / (double) numMatches,
                totalRedCards, totalRedCards / (double) numMatches);
        System.out.printf("  Penalties: %d (%.1f per match)%n", totalPenalties, totalPenalties / (double) numMatches);
        System.out.printf("  Corners: %d (%.1f per match)%n", totalCorners, totalCorners / (double) numMatches);
        System.out.printf("  Offsides: %d%n", totalOffsides);
        System.out.printf("  THRU passes attempted: %d (completed: %d)%n", totalThruAttempts, totalThruCompleted);
        System.out.printf("  Pass failures: %d (interp: %d, loose: %d, out-of-bounds: %d)%n",
                totalPassesAttempted - totalPassesCompleted,
                totalInterceptions, totalLooseBall, totalOutOfBounds);
        System.out.printf("  Pass-caused restarts: %d throw-ins, %d corners%n",
                totalThrowIns, totalCornersFromPass);
        System.out.printf("  Restarts: %d throw-ins, %d goal-kicks, %d corners%s%n",
                totalThrowIns, totalGoalKicks, totalCornersFromPass,
                (totalThrowIns + totalGoalKicks + totalCornersFromPass > 0
                        ? " (pass-caused)" : ""));
        System.out.printf("  Possession (home avg): %.0f%%%n", totalHomePossession / numMatches);
        System.out.printf("  Saves: %d (%.2f/match)  Blocks: %d (%.2f/match)  Deflections: %d (%.2f/match)  Clearances: %d (%.2f/match)%n",
                totalSaves, totalSaves / (double) numMatches,
                totalBlocks, totalBlocks / (double) numMatches,
                totalDeflections, totalDeflections / (double) numMatches,
                totalClearances, totalClearances / (double) numMatches);
        System.out.printf("  Interceptions: %d (%.2f/match)%n",
                totalInterceptions, totalInterceptions / (double) numMatches);
        System.out.printf("  Goals by source: openPlay %d, cross %d, center %d, corner %d, freeKick %d, penalty %d%n",
                totalGoalsOpenPlay, totalGoalsCross, totalGoalsCenter,
                totalGoalsCorner, totalGoalsFreeKick, totalGoalsPenalty);
        System.out.println("  Scores: " + scorelines);
    }
}