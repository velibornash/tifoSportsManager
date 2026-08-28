package org.example.footballmanager.demo.service.diagnosticsAndTests;

import org.example.footballmanager.demo.service.controller.MatchSimulationController;
import org.example.footballmanager.demo.service.result.*;
import org.example.footballmanager.demo.service.result.LogEntry;

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

            // Count THRU passes and pass outcomes from action log
            for (LogEntry entry : result.logs()) {
                if (entry.getChannel().equals("ACTION") && entry.getDescription().contains("THRU PASS")) {
                    totalThruAttempts++;
                }
            }
            totalThruCompleted += result.homeStats().getThruCompleted() + result.awayStats().getThruCompleted();
            totalInterceptions += result.homeStats().getInterceptionCount() + result.awayStats().getInterceptionCount();
            totalLooseBall += result.homeStats().getLooseBallCount() + result.awayStats().getLooseBallCount();
            totalOutOfBounds += result.homeStats().getPassOutOfBoundsCount() + result.awayStats().getPassOutOfBoundsCount();
            totalThrowIns += result.homeStats().getThrowInCount() + result.awayStats().getThrowInCount();
            totalGoalKicks += result.homeStats().getGoalKickCount() + result.awayStats().getGoalKickCount();
            totalCornersFromPass += result.homeStats().getCornerFromPassCount() + result.awayStats().getCornerFromPassCount();

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
        System.out.println("  Scores: " + scorelines);
    }
}