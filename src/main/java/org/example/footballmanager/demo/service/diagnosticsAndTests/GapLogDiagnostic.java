package org.example.footballmanager.demo.service.diagnosticsAndTests;

import org.example.footballmanager.demo.service.controller.MatchSimulationController;
import org.example.footballmanager.demo.service.result.LogEntry;
import org.example.footballmanager.demo.service.result.MatchResult;
import org.example.footballmanager.demo.service.result.MatchSimulator;

import java.util.ArrayList;
import java.util.List;

/**
 * Timeline-gap QA: computes the gaps (in ticks) between consecutive log
 * entries across the WHOLE match and prints the largest gaps, showing the
 * entries on each side. 1 second = 2 ticks (TICKS_PER_MINUTE=40 → 2 ticks/s).
 * A 6+ tick (>3s) silence between any two log entries is a "hole" in the
 * timeline — the user sees the viewer sit still.
 */
public class GapLogDiagnostic {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 42L;
        MatchSimulator simulator = new MatchSimulator(seed);
        var homePlayers = MatchSimulationController.generateTeam("HOME", "Omladinac");
        var awayPlayers = MatchSimulationController.generateTeam("AWAY", "Partizan");

        MatchResult result = simulator.simulate(homePlayers, awayPlayers,
                "Omladinac", "Partizan");

        List<LogEntry> logs = result.logs().stream()
                .filter(e -> e.getTick() >= 0)
                .sorted(java.util.Comparator.comparingLong(LogEntry::getTick))
                .toList();

        long boundary = Math.max(1, logs.isEmpty() ? 0 : logs.get(0).getTick());
        List<LogEntry> filtered = new ArrayList<>();
        for (LogEntry e : logs) {
            if (e.getTick() >= boundary) filtered.add(e);
        }

        // Group consecutive entries into gap records.
        List<long[]> gaps = new ArrayList<>(); // {fromTick, toTick, gap, idxOfNext}
        for (int i = 0; i + 1 < filtered.size(); i++) {
            LogEntry a = filtered.get(i);
            long gap = filtered.get(i + 1).getTick() - a.getTick();
            if (gap >= 5) { // > 2.5s silence
                gaps.add(new long[]{a.getTick(), filtered.get(i + 1).getTick(), gap, i + 1});
            }
        }

        System.out.println("=== TIMELINE GAP QA (seed=" + seed + ") ===");
        System.out.println("Final score: " + result.finalScore());
        System.out.println("Total log entries: " + filtered.size());
        System.out.println("Gaps >= 5 ticks (>2.5s): " + gaps.size());

        // Classify each gap by what the two boundary log entries describe:
        //   CELEBRATION / VAR / RESTART(+kickoff wait) / else MYSTERY.
        long celebration = 0, varGaps = 0, restart = 0, mystery = 0;
        List<long[]> mysteryGaps = new ArrayList<>();
        for (long[] g : gaps) {
            int nextIdx = (int) g[3];
            String a = filtered.get(nextIdx - 1).getDescription() == null ? ""
                    : filtered.get(nextIdx - 1).getDescription();
            String b = filtered.get(nextIdx).getDescription() == null ? ""
                    : filtered.get(nextIdx).getDescription();
            if (a.contains("CELEBRATION") || b.contains("CELEBRATION")) celebration++;
            else if (a.contains("VAR") || b.contains("VAR")) varGaps++;
            else if (a.contains("RESTART") || b.contains("RESTART")
                    || a.contains("kickoff") || b.contains("kickoff")) restart++;
            else {
                mystery++;
                mysteryGaps.add(g);
            }
        }
        System.out.println("gap classifications: celebration=" + celebration
                + " var=" + varGaps + " restart/kickoff=" + restart + " mystery=" + mystery);

        System.out.println("Restart/kickoff gaps:");
        gaps.stream()
                .filter(g -> {
                    int nextIdx = (int) g[3];
                    String a = filtered.get(nextIdx - 1).getDescription() == null ? ""
                            : filtered.get(nextIdx - 1).getDescription();
                    String b = filtered.get(nextIdx).getDescription() == null ? ""
                            : filtered.get(nextIdx).getDescription();
                    return !a.contains("CELEBRATION") && !b.contains("CELEBRATION")
                            && !a.contains("VAR") && !b.contains("VAR");
                })
                .sorted((x, y) -> Long.compare(y[2], x[2]))
                .limit(10)
                .forEach(g -> {
                    int nextIdx = (int) g[3];
                    LogEntry a = filtered.get(nextIdx - 1);
                    LogEntry b = filtered.get(nextIdx);
                    System.out.printf("  gap=%3d ticks (%5.1fs) tick %d→%d  [%s] %s → [%s] %s%n",
                            g[2], g[2] / 2.0, g[0], g[1],
                            a.getChannel(), truncate(a.getDescription(), 60),
                            b.getChannel(), truncate(b.getDescription(), 60));
                });

        System.out.println("Mystery gaps (not explained by a known hold):");
        if (mysteryGaps.isEmpty()) {
            System.out.println("  (none)");
        }
        mysteryGaps.stream()
                .sorted((x, y) -> Long.compare(y[2], x[2]))
                .limit(15)
                .forEach(g -> {
                    int nextIdx = (int) g[3];
                    LogEntry a = filtered.get(nextIdx - 1);
                    LogEntry b = filtered.get(nextIdx);
                    System.out.printf("  gap=%3d ticks (%5.1fs) tick %d→%d  [%s] %s → [%s] %s%n",
                            g[2], g[2] / 2.0, g[0], g[1],
                            a.getChannel(), truncate(a.getDescription(), 70),
                            b.getChannel(), truncate(b.getDescription(), 70));
                });
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}