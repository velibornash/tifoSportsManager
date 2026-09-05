package org.example.footballmanager.demo.service.diagnosticsAndTests;

import org.example.footballmanager.demo.service.controller.MatchSimulationController;
import org.example.footballmanager.demo.service.result.LogEntry;
import org.example.footballmanager.demo.service.result.MatchResult;
import org.example.footballmanager.demo.service.result.MatchSimulator;

import java.util.List;

/**
 * Threat-override QA (task 4): simulates a match and counts how often the
 * THREAT override fires (TYPE_A = press ball carrier within 1.0 cell;
 * TYPE_B = press isolated attacker in the final 2.5 rows within 3.5 cells).
 *
 * The engine now emits a throttled "THREAT" log per assignment change, so
 * this trace shows one line per defendant→threat claim (not per tick).
 */
public class ThreatOverrideDiagnostic {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 42L;
        MatchSimulator simulator = new MatchSimulator(seed);
        var homePlayers = MatchSimulationController.generateTeam("HOME", "Omladinac");
        var awayPlayers = MatchSimulationController.generateTeam("AWAY", "Partizan");

        MatchResult result = simulator.simulate(homePlayers, awayPlayers,
                "Omladinac", "Partizan");

        List<LogEntry> logs = result.logs();
        List<LogEntry> threatLogs = logs.stream()
                .filter(e -> "THREAT".equals(e.getChannel()))
                .toList();

        long typeA = threatLogs.stream().filter(e -> e.getDescription().contains("TYPE_A")).count();
        long typeB = threatLogs.stream().filter(e -> e.getDescription().contains("TYPE_B")).count();
        long homeDefenders = threatLogs.stream().filter(e -> "HOME".equals(e.getTeam())).count();
        long awayDefenders = threatLogs.stream().filter(e -> "AWAY".equals(e.getTeam())).count();

        java.util.Map<String, Integer> claimsByRole = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> roleByLabel = new java.util.HashMap<>();
        for (var p : homePlayers) roleByLabel.put(p.getLabel(), p.getRole());
        for (var p : awayPlayers) roleByLabel.put(p.getLabel(), p.getRole());
        for (LogEntry e : threatLogs) {
            String desc = e.getDescription();
            var m = java.util.regex.Pattern.compile("^THREAT (TYPE_A|TYPE_B): (.+?) presses").matcher(desc);
            String presser = m.find() ? m.group(2).trim() : "";
            String role = roleByLabel.getOrDefault(presser, "?");
            claimsByRole.merge(role, 1, Integer::sum);
        }

        System.out.println("=== THREAT OVERRIDE QA (seed=" + seed + ") ===");
        System.out.println("Final score: " + result.finalScore());
        System.out.println("Total INFO log entries: " + logs.stream()
                .filter(e -> e.getType() == LogEntry.EntryType.INFO).count());
        System.out.println();
        System.out.println("THREAT log lines (throttled assignments): " + threatLogs.size());
        System.out.println("  TYPE_A (press ball carrier ≤ 1.0 cell): " + typeA);
        System.out.println("  TYPE_B (isolated attacker final 2.5 rows): " + typeB);
        System.out.println("  HOME claiming: " + homeDefenders
                + " | AWAY claiming: " + awayDefenders);
        System.out.println("  Claims by role: " + claimsByRole);
        System.out.println();
        System.out.println("--- First 8 THREAT lines ---");
        threatLogs.stream().limit(8).forEach(e ->
                System.out.println("  [" + e.getMatchClock() + "] " + e.getDescription()));
        System.out.println();
        System.out.println(typeA + typeB > 0
                ? "PASS — threat override fires and logs"
                : "FAIL — no threat override logged at all");
    }
}