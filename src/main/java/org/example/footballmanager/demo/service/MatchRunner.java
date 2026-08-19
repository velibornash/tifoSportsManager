package org.example.footballmanager.demo.service;

import org.example.footballmanager.demo.service.controller.MatchSimulationController;
import org.example.footballmanager.demo.service.result.*;

import java.util.List;

/**
 * Standalone runner — runs a match simulation and prints the full result.
 * No Spring context needed. Just run main().
 */
public class MatchRunner {

    public static void main(String[] args) {
        long seed = System.nanoTime();
        if (args.length > 0) {
            try { seed = Long.parseLong(args[0]); } catch (NumberFormatException ignored) {}
        }

        System.out.println("=== TIFO MATCH SIMULATOR ===");
        System.out.println("Seed: " + seed);
        System.out.println();

        MatchSimulator simulator = new MatchSimulator(seed);

        // Generate two teams
        var homePlayers = MatchSimulationController.generateTeam("HOME", "Crvena Zvezda");
        var awayPlayers = MatchSimulationController.generateTeam("AWAY", "Partizan");

        // Print lineups
        printLineup("Crvena Zvezda", homePlayers);
        printLineup("Partizan", awayPlayers);

        System.out.println("Simulating match...");
        System.out.println();

        // Run match
        MatchResult result = simulator.simulate(homePlayers, awayPlayers, "Crvena Zvezda", "Partizan");

        // Print result
        printResult(result);
    }

    private static void printLineup(String teamName, List<org.example.footballmanager.demo.service.model.Player> players) {
        System.out.println("--- " + teamName + " ---");
        for (int i = 0; i < players.size(); i++) {
            var p = players.get(i);
            var s = p.getSkills();
            System.out.printf("  %2d. %-20s %-3s  P:%-2d T:%-2d K:%-2d Te:%-2d Ps:%-2d Pa:%-2d S:%-2d D:%-2d%n",
                    i + 1, p.getLabel(), p.getRole(),
                    s.pace(), s.stamina(), s.keeper(), s.technique(),
                    s.playmaking(), s.passing(), s.striker(), s.defender());
        }
        System.out.println();
    }

    private static void printResult(MatchResult result) {
        System.out.println("========================================");
        System.out.println("  " + result.finalScore());
        System.out.println("  " + result.homeTeamName() + " vs " + result.awayTeamName());
        System.out.println("========================================");
        System.out.println();

        // Goals
        if (!result.goals().isEmpty()) {
            System.out.println("--- GOALS ---");
            for (GoalDetail g : result.goals()) {
                String assist = g.assistantName() != null ? " (assist: " + g.assistantName() + ")" : "";
                System.out.printf("  %2d'  %s  %s  [%s]%s%n",
                        g.minute(), g.scorerName(), g.scorerTeam(), g.scoreString(), assist);
            }
            System.out.println();
        }

        // Team stats
        System.out.println("--- STATISTICS ---");
        printTeamStats(result.homeStats());
        printTeamStats(result.awayStats());
        System.out.println();

        // Player stats - top performers
        System.out.println("--- TOP PERFORMERS ---");
        System.out.println("  " + result.homeTeamName() + ":");
        result.homePlayerStats().stream().limit(3).forEach(p ->
                System.out.printf("    %-20s  %.1f  (%dG %dA %d shots)%n",
                        p.playerName(), p.rating(), p.goals(), p.assists(), p.shots()));
        System.out.println("  " + result.awayTeamName() + ":");
        result.awayPlayerStats().stream().limit(3).forEach(p ->
                System.out.printf("    %-20s  %.1f  (%dG %dA %d shots)%n",
                        p.playerName(), p.rating(), p.goals(), p.assists(), p.shots()));
        System.out.println();

        // Report
        MatchReport report = result.report();
        System.out.println("--- REPORT ---");
        System.out.println("  " + report.headline());
        System.out.println("  " + report.summary());
        System.out.println("  Man of the Match: " + report.manOfTheMatch());
        System.out.println();

        // Full report
        System.out.println(report.fullReport());
    }

    private static void printTeamStats(TeamMatchStats stats) {
        System.out.printf("  %-20s  %d-%d  Shots: %d/%d  Passes: %d/%d (%d%%)  Fouls: %d  Corners: %d  Cards: %d/%d  Possession: %.0f%%%n",
                stats.teamName(), stats.goals(), 0,
                stats.shotsOnTarget(), stats.shots(),
                stats.passesCompleted(), stats.passesAttempted(), stats.passAccuracy(),
                stats.fouls(), stats.corners(),
                stats.yellowCards(), stats.redCards(),
                stats.possessionPercent());
    }
}
