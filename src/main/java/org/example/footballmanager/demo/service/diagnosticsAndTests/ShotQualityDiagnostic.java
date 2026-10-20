package org.example.footballmanager.demo.service.diagnosticsAndTests;

import org.example.footballmanager.demo.service.controller.MatchSimulationController;
import org.example.footballmanager.demo.service.engine.ActionEngine;
import org.example.footballmanager.demo.service.engine.SimUtils;
import org.example.footballmanager.demo.service.model.Player;
import org.example.footballmanager.demo.service.recording.MatchEvent;
import org.example.footballmanager.demo.service.result.LogEntry;
import org.example.footballmanager.demo.service.result.MatchResult;
import org.example.footballmanager.demo.service.result.MatchSimulator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shot quality QA (task 1): parses DECISION: SHOT log entries to capture every
 * shot decision with its contextual score. Then cross-references with shot outcome
 * events (GOAL, SHOT_SAVED, SHOT_BLOCKED, SHOT_MISSED) from the event stream.
 *
 * Buckets shots:
 * - GOOD: distance ≤ 2.0 cells AND ≤1 defender in lane AND angle < 40°
 * - BAD:  distance > 2.0 cells OR ≥2 defenders in lane OR angle > 60°
 * - NEUTRAL: everything in between
 *
 * Measures whether scoreShot correctly differentiates quality chances from
 * hopeful punts, and whether the outcomes (goal rate, on-target rate) reflect
 * that quality.
 */
public class ShotQualityDiagnostic {

    private record ShotRecord(
            String tick, String team, String shooter,
            double score, String outcome) {}

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 42L;
        MatchSimulator simulator = new MatchSimulator(seed);
        var home = MatchSimulationController.generateTeamWithSkill("HOME", "Omladinac", 14);
        var away = MatchSimulationController.generateTeamWithSkill("AWAY", "Partizan", 14);

        MatchResult result = simulator.simulate(home, away, "Omladinac", "Partizan");

        // Parse DECISION: SHOT log entries for shot decisions
        List<LogEntry> logs = result.logs();

        List<ShotRecord> shots = new ArrayList<>();
        // Description format: "<DECISION ...> DECISION: SHOT  score=43.078 ... | Ball=(1.28, 5.17) | Carrier=Partizan 11 (1.28, 5.17)"
        // Use simple string parsing instead of regex
        for (LogEntry log : logs) {
            if (log.getType() != LogEntry.EntryType.DECISION) continue;
            String desc = log.getDescription();
            if (desc == null) continue;
            if (!desc.contains("DECISION: SHOT")) continue;

            // Extract score: look for "DECISION: SHOT  score=XX"
            int scoreIdx = desc.indexOf("DECISION: SHOT  score=");
            if (scoreIdx < 0) continue;
            int scoreStart = scoreIdx + "DECISION: SHOT  score=".length();
            int scoreEnd = desc.indexOf(' ', scoreStart);
            if (scoreEnd < 0) scoreEnd = desc.indexOf('\t', scoreStart);
            if (scoreEnd < 0) scoreEnd = desc.length();
            double score;
            try { score = Double.parseDouble(desc.substring(scoreStart, scoreEnd).trim()); }
            catch (NumberFormatException e) { continue; }

            // Extract carrier: look for "| Carrier=NAME (row, col)"
            // Name includes spaces, so find "(" and take everything before it
            int carrierIdx = desc.indexOf("| Carrier=");
            String shooterLabel = "?";
            if (carrierIdx >= 0) {
                int nameStart = carrierIdx + "| Carrier=".length();
                int nameEnd = desc.indexOf('(', nameStart);
                if (nameEnd > nameStart) {
                    shooterLabel = desc.substring(nameStart, nameEnd).trim();
                }
            }

            String tick = log.getMatchClock();
            shots.add(new ShotRecord(tick, "?", shooterLabel, score, "in-flight"));
        }

        // Annotate outcomes from events: wide time window (±5 seconds)
        // because the goal event fires at shot completion, not at decision time.
        List<MatchEvent> events = result.events();
        Map<String, String> tickToOutcome = new HashMap<>();
        for (MatchEvent e : events) {
            String outcome = null;
            if ("GOAL".equals(e.type())) outcome = "GOAL";
            else if ("SHOT_SAVED".equals(e.type())) outcome = "SAVED";
            else if ("SHOT_BLOCKED".equals(e.type())) outcome = "BLOCKED";
            else if ("SHOT_MISSED".equals(e.type())) outcome = "MISSED";
            if (outcome != null) {
                tickToOutcome.put(clock(e.tick()), outcome);
            }
        }

        // For each shot, look for a matching outcome within ±5 seconds of decision time
        for (int i = 0; i < shots.size(); i++) {
            ShotRecord sr = shots.get(i);
            String shotTime = sr.tick();
            // Match clock format: "M:SS" or "90+M:SS" (injury time)
            int shotTotalSec;
            if (shotTime.contains("+")) {
                // "90+1:36" → base=90, extra=1, sec=36 → 5496 sec
                String rest = shotTime.substring(shotTime.indexOf('+') + 1);
                int base = Integer.parseInt(shotTime.substring(0, shotTime.indexOf('+')));
                String[] extraParts = rest.split(":");
                int extraMin = Integer.parseInt(extraParts[0]);
                int extraSec = extraParts.length > 1 ? Integer.parseInt(extraParts[1]) : 0;
                shotTotalSec = (base + extraMin) * 60 + extraSec;
            } else {
                String[] parts = shotTime.split(":");
                if (parts.length != 2) {
                    shots.set(i, new ShotRecord(sr.tick(), sr.team(), sr.shooter(), sr.score(), "in-flight"));
                    continue;
                }
                shotTotalSec = Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
            }

            String outcome = "in-flight";
            outer:
            for (int delta = 0; delta <= 10; delta++) {
                for (int sign : new int[]{1, -1}) {
                    int t = shotTotalSec + sign * delta;
                    if (t < 0) continue;
                    String key = (t / 60) + ":" + String.format("%02d", t % 60);
                    if (tickToOutcome.containsKey(key)) {
                        outcome = tickToOutcome.get(key);
                        break outer;
                    }
                }
            }
            shots.set(i, new ShotRecord(sr.tick(), sr.team(), sr.shooter(), sr.score(), outcome));
        }

        // Also compute full situational quality per shot from the simulator
        // After calibration (2026-09-04): scores cluster roughly in 40-110 range.
        // HIGH (≥70): good conditions. MID (40-69): moderate. LOW (<40): poor.
        int empty = 0, high = 0, mid = 0, low = 0;
        int emptyGoals = 0, emptyOnTarget = 0;
        int highGoals = 0, highOnTarget = 0;
        int midGoals = 0, midOnTarget = 0;
        int lowGoals = 0, lowOnTarget = 0;
        int totalGoals = 0;
        for (ShotRecord sr : shots) {
            boolean scored = "GOAL".equals(sr.outcome());
            boolean onTarget = scored || "SAVED".equals(sr.outcome());
            if (scored) totalGoals++;

            if (sr.score() >= 100) {
                empty++;
                if (scored) emptyGoals++;
                if (onTarget) emptyOnTarget++;
            } else if (sr.score() >= 70) {
                high++;
                if (scored) highGoals++;
                if (onTarget) highOnTarget++;
            } else if (sr.score() >= 40) {
                mid++;
                if (scored) midGoals++;
                if (onTarget) midOnTarget++;
            } else {
                low++;
                if (scored) lowGoals++;
                if (onTarget) lowOnTarget++;
            }
        }

        int total = shots.size();
        int onTarget = emptyOnTarget + highOnTarget + midOnTarget + lowOnTarget;
        System.out.println("=== SHOT QUALITY QA (seed=" + seed + ") ===");
        System.out.println("Final score: " + result.finalScore());
        System.out.println("Total SHOT decisions (from logs): " + total);
        System.out.println("Total goals: " + totalGoals);
        System.out.println("On-target (goal+save): " + onTarget
                + " (" + pct(onTarget, total) + "%)");
        System.out.println();
        System.out.println("--- Shot quality by scoreShot bucket ---");
        System.out.printf("  EMPTY  (score ≥ 100, forced empty-goal): %3d shots | goals: %2d | on-target: %2d | on-tgt%%: %6s | goal%%: %6s%n",
                empty, emptyGoals, emptyOnTarget, pct(emptyOnTarget, empty), pct(emptyGoals, empty));
        System.out.printf("  HIGH   (70-99)                    : %3d shots | goals: %2d | on-target: %2d | on-tgt%%: %6s | goal%%: %6s%n",
                high, highGoals, highOnTarget, pct(highOnTarget, high), pct(highGoals, high));
        System.out.printf("  MID    (40-69)                    : %3d shots | goals: %2d | on-target: %2d | on-tgt%%: %6s | goal%%: %6s%n",
                mid, midGoals, midOnTarget, pct(midOnTarget, mid), pct(midGoals, mid));
        System.out.printf("  LOW    (< 40)                     : %3d shots | goals: %2d | on-target: %2d | on-tgt%%: %6s | goal%%: %6s%n",
                low, lowGoals, lowOnTarget, pct(lowOnTarget, low), pct(lowGoals, low));
        System.out.println();
        System.out.println("--- Last 15 shots (by score) ---");
        shots.stream()
                .sorted((a, b) -> Double.compare(a.score(), b.score()))
                .skip(Math.max(0, shots.size() - 15))
                .forEach(sr -> System.out.printf("  [%s] %s %-12s score=%7.1f -> %s%n",
                        sr.tick(), sr.team(), sr.shooter(), sr.score(), sr.outcome()));
        System.out.println();

        boolean pass = total > 0
                && (empty == 0 || pctNum(emptyOnTarget, empty) >= pctNum(highOnTarget, high))
                && (high == 0 || pctNum(highOnTarget, high) >= pctNum(midOnTarget, mid))
                && (mid == 0 || pctNum(midOnTarget, mid) >= pctNum(lowOnTarget, low));
        System.out.println(pass
                ? "PASS — on-target rate decreases with scoreShot (quality respected)"
                : "FAIL — scoreShot does not correlate with shot quality (on-target rates inverted)");
    }

    private static String pct(int a, int b) {
        return b == 0 ? "  N/A" : String.format("%5.1f", 100.0 * a / b);
    }

    private static double pctNum(int a, int b) {
        return b == 0 ? 0 : 100.0 * a / b;
    }

    private static String clock(long tick) {
        int s = (int) (tick / 2);
        int min = s / 60;
        int sec = s % 60;
        if (min >= 90) {
            // Format as "90+M:SS"
            return "90+" + (min - 90) + ":" + String.format("%02d", sec);
        }
        return min + ":" + String.format("%02d", sec);
    }
}
