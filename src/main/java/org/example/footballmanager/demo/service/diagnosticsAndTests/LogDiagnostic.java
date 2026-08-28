package org.example.footballmanager.demo.service.diagnosticsAndTests;

import org.example.footballmanager.demo.service.controller.MatchSimulationController;
import org.example.footballmanager.demo.service.result.MatchResult;
import org.example.footballmanager.demo.service.result.MatchSimulator;
import org.example.footballmanager.demo.service.result.LogEntry;

import java.util.*;

public class LogDiagnostic {
    public static void main(String[] args) {
        long seed = 1000L;
        MatchSimulator sim = new MatchSimulator(seed);
        var homePlayers = MatchSimulationController.generateTeamWithSkill("HOME", "Home", 14);
        var awayPlayers = MatchSimulationController.generateTeamWithSkill("AWAY", "Away", 14);
        MatchResult result = sim.simulate(homePlayers, awayPlayers, "Home", "Away");

        Map<String, Integer> channelCount = new TreeMap<>();
        Set<String> sampleDescs = new TreeSet<>();
        Map<String, Integer> teamCount = new TreeMap<>();
        int nullTeam = 0;

        for (LogEntry e : result.logs()) {
            channelCount.merge(e.getChannel(), 1, Integer::sum);
            String ch = e.getChannel();
            if (ch.equals("ACTION_OUTCOME") || ch.equals("ACTION_EXECUTION") ||
                ch.equals("INFO") || ch.equals("GOAL") || ch.equals("CARD") ||
                ch.equals("FOUL") || ch.equals("VAR") || ch.equals("CHASE")) {
                if (sampleDescs.size() < 50) sampleDescs.add("[" + ch + "] " + e.getDescription());
            }
            String t = e.getTeam();
            if (t == null) {
                nullTeam++;
                teamCount.merge("NULL", 1, Integer::sum);
            } else {
                teamCount.merge(t, 1, Integer::sum);
            }
        }

        System.out.println("=== CHANNEL COUNTS ===");
        channelCount.forEach((k, v) -> System.out.printf("  %-20s: %d%n", k, v));

        System.out.println("\n=== TEAM COUNTS ===");
        teamCount.forEach((k, v) -> System.out.printf("  %s: %d%n", k, v));
        System.out.println("  NULL team: " + nullTeam);

        System.out.println("\n=== SAMPLE DESCRIPTIONS ===");
        sampleDescs.forEach(System.out::println);

        // Print all ACTION_OUTCOME save-related
        System.out.println("\n=== SAVE OUTCOMES ===");
        int saveCount = 0;
        for (LogEntry e : result.logs()) {
            if (e.getChannel().equals("ACTION_OUTCOME") &&
                (e.getDescription().contains("SAVE") || e.getDescription().contains("SHOT"))) {
                System.out.printf("  [%s] team=%s | %s%n",
                    e.getChannel(), e.getTeam(), e.getDescription());
                if (saveCount++ > 30) break;
            }
        }

        // Print all GOAL entries
        System.out.println("\n=== GOAL entries ===");
        int g = 0;
        for (LogEntry e : result.logs()) {
            if (e.getChannel().equals("GOAL")) {
                System.out.printf("  team=%s | %s%n", e.getTeam(), e.getDescription());
                g++;
            }
        }
        System.out.println("Total GOAL entries: " + g);

        // Check shots from stats
        System.out.printf("%nStats: homeSot=%d awaySot=%d homeGoals=%d awayGoals=%d%n",
            result.homeStats().shotsOnTarget(), result.awayStats().shotsOnTarget(),
            result.homeStats().goals(), result.awayStats().goals());
    }
}
