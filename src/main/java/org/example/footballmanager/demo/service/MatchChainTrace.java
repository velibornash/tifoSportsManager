package org.example.footballmanager.demo.service;

import org.example.footballmanager.demo.service.controller.MatchSimulationController;
import org.example.footballmanager.demo.service.result.*;

import java.util.*;

/**
 * Traces the full decision→action→outcome chain for the first N minutes.
 * Each chain shows:
 *   1. DECISION — what options were considered, what was chosen, why
 *   2. ACTION   — how it was executed, skill, targets
 *   3. OUTCOME  — what actually happened (received, intercepted, goal, miss...)
 *   4. DUEL     — if a duel occurred, who won
 *
 * Groups chains by time window (e.g. each "possession phase" or decision cycle).
 */
public class MatchChainTrace {

    public static void main(String[] args) {
        long seed = 42L;
        if (args.length > 0) {
            try { seed = Long.parseLong(args[0]); } catch (NumberFormatException ignored) {}
        }

        MatchSimulator simulator = new MatchSimulator(seed);
        var homePlayers = MatchSimulationController.generateTeam("HOME", "Omladinac");
        var awayPlayers = MatchSimulationController.generateTeam("AWAY", "Partizan");

        MatchResult result = simulator.simulate(homePlayers, awayPlayers, "Omladinac", "Partizan");

        List<LogEntry> logs = result.logs();

        // Parse match clock "M:SS" → total seconds, filter to first 10 minutes
        int cutoffSeconds = 10 * 60;

        System.out.println("=== CHAIN TRACE: First 10 minutes (seed=" + seed + ") ===");
        System.out.println("Final score: " + result.finalScore());
        System.out.println();

        // Group logs into chains: each chain starts with a DECISION and ends with ACTION_OUTCOME or DUEL
        List<List<LogEntry>> chains = new ArrayList<>();
        List<LogEntry> currentChain = null;

        for (LogEntry entry : logs) {
            int secs = parseMatchClock(entry.getMatchClock());
            if (secs > cutoffSeconds) break;

            if (entry.getType() == LogEntry.EntryType.DECISION) {
                // Start a new chain
                if (currentChain != null && !currentChain.isEmpty()) {
                    chains.add(currentChain);
                }
                currentChain = new ArrayList<>();
            }

            if (currentChain == null) currentChain = new ArrayList<>();
            currentChain.add(entry);

            // Chain ends after outcome or duel
            if (entry.getType() == LogEntry.EntryType.ACTION_OUTCOME
                    || entry.getType() == LogEntry.EntryType.DUEL
                    || entry.getType() == LogEntry.EntryType.GOAL
                    || entry.getType() == LogEntry.EntryType.FOUL
                    || entry.getType() == LogEntry.EntryType.CORNER
                    || entry.getType() == LogEntry.EntryType.GOAL_KICK
                    || entry.getType() == LogEntry.EntryType.THROW_IN) {
                chains.add(currentChain);
                currentChain = null;
            }
        }
        if (currentChain != null && !currentChain.isEmpty()) {
            chains.add(currentChain);
        }

        // Print each chain
        int chainNum = 0;
        for (List<LogEntry> chain : chains) {
            chainNum++;
            LogEntry decision = chain.stream()
                    .filter(e -> e.getType() == LogEntry.EntryType.DECISION)
                    .findFirst().orElse(null);

            if (decision == null) {
                // Non-decision chain (restart, corner, etc.)
                printNonDecisionChain(chainNum, chain);
                continue;
            }

            printChain(chainNum, chain);
        }

        System.out.println();
        System.out.println("=== CHAIN SUMMARY ===");
        System.out.println("Total chains in first 10 min: " + chainNum);

        // Count chain types
        Map<String, Integer> chainTypes = new LinkedHashMap<>();
        for (List<LogEntry> chain : chains) {
            String type = "OTHER";
            for (LogEntry e : chain) {
                if (e.getType() == LogEntry.EntryType.DECISION) {
                    String desc = e.getDescription();
                    // Extract decision type from "DECISION: PASS → ..." format
                    int start = desc.indexOf("DECISION: ");
                    if (start >= 0) {
                        type = desc.substring(start + 10).split("[\\s(]")[0];
                    }
                    break;
                }
                if (e.getType() == LogEntry.EntryType.GOAL) { type = "GOAL_EVENT"; break; }
                if (e.getType() == LogEntry.EntryType.CORNER) { type = "CORNER"; break; }
                if (e.getType() == LogEntry.EntryType.GOAL_KICK) { type = "GOAL_KICK"; break; }
                if (e.getType() == LogEntry.EntryType.THROW_IN) { type = "THROW_IN"; break; }
            }
            chainTypes.merge(type, 1, Integer::sum);
        }
        System.out.println("Chain types: " + chainTypes);

        // Count outcomes
        long received = logs.stream().filter(e -> e.getType() == LogEntry.EntryType.ACTION_OUTCOME
                && e.getDescription().contains("RECEIVED")).count();
        long loose = logs.stream().filter(e -> e.getType() == LogEntry.EntryType.ACTION_OUTCOME
                && e.getDescription().contains("LOOSE")).count();
        long intercepted = logs.stream().filter(e -> e.getType() == LogEntry.EntryType.ACTION_OUTCOME
                && e.getDescription().contains("INTERCEPTED")).count();
        long goals = logs.stream().filter(e -> e.getType() == LogEntry.EntryType.GOAL).count();
        long missed = logs.stream().filter(e -> e.getType() == LogEntry.EntryType.ACTION_OUTCOME
                && (e.getDescription().contains("MISS") || e.getDescription().contains("SAVED"))).count();

        System.out.println("Outcomes: received=" + received + " loose=" + loose
                + " intercepted=" + intercepted + " goals=" + goals + " missed/saved=" + missed);
    }

    private static void printChain(int num, List<LogEntry> chain) {
        LogEntry decision = chain.stream()
                .filter(e -> e.getType() == LogEntry.EntryType.DECISION)
                .findFirst().orElse(null);

        // Extract carrier info
        String carrier = decision != null ? decision.getPlayerName() : "?";
        String team = decision != null ? decision.getTeam() : "?";
        String clock = chain.get(0).getMatchClock();

        // Extract chosen action type
        String chosenType = "?";
        if (decision != null) {
            String desc = decision.getDescription();
            int start = desc.indexOf("DECISION: ");
            if (start >= 0) {
                String after = desc.substring(start + 10);
                chosenType = after.split("[\\s(]")[0];
            }
        }

        // Extract outcome
        String outcome = "—";
        for (LogEntry e : chain) {
            if (e.getType() == LogEntry.EntryType.ACTION_OUTCOME) {
                // Pull key info from outcome description
                String desc = e.getDescription();
                // e.g. "OUTCOME: PASS → RECEIVED by Home 3 (dist=0.45)"
                int arrow = desc.indexOf("→ ");
                if (arrow >= 0) {
                    outcome = desc.substring(arrow + 2);
                    // Truncate long outcomes
                    if (outcome.length() > 80) outcome = outcome.substring(0, 80) + "...";
                } else {
                    outcome = desc;
                }
            }
            if (e.getType() == LogEntry.EntryType.GOAL) {
                outcome = "⚽ " + e.getDescription();
            }
            if (e.getType() == LogEntry.EntryType.FOUL) {
                outcome = "FOUL: " + e.getDescription();
            }
        }

        // Check for duels
        String duelInfo = "";
        for (LogEntry e : chain) {
            if (e.getType() == LogEntry.EntryType.DUEL) {
                duelInfo = "  DUEL: " + e.getDescription();
            }
        }

        // Format: chain header
        System.out.printf("--- Chain #%d [%s] %s {%s} --- %s%n",
                num, clock, carrier, team, chosenType);

        // Print decision details (options considered)
        if (decision != null) {
            printDecisionDetails(decision);
        }

        // Print action execution
        for (LogEntry e : chain) {
            if (e.getType() == LogEntry.EntryType.ACTION_EXECUTION) {
                System.out.println("  EXEC: " + truncate(e.getDescription(), 120));
            }
        }

        // Print outcome
        System.out.println("  OUTCOME: " + outcome);
        if (!duelInfo.isEmpty()) {
            System.out.println("  " + duelInfo);
        }

        // Print any extra info (offsides, etc.)
        for (LogEntry e : chain) {
            if (e.getType() == LogEntry.EntryType.INFO && !e.getDescription().contains("KICK OFF")) {
                System.out.println("  INFO: " + truncate(e.getDescription(), 120));
            }
        }

        System.out.println();
    }

    private static void printDecisionDetails(LogEntry decision) {
        String desc = decision.getDescription();

        // Extract options
        int optIdx = desc.indexOf("Options considered:");
        if (optIdx >= 0) {
            String opts = desc.substring(optIdx + 18).trim();
            // Parse individual options: "PASS(score=0.612) visible=true → Home 3 ..."
            String[] parts = opts.split("\\s+(?=\\w+\\(score=)");
            for (String part : parts) {
                part = part.trim();
                if (!part.isEmpty()) {
                    System.out.println("    OPTION: " + part);
                }
            }
        }

        // Extract reason
        int reasonIdx = desc.indexOf("reason=[");
        if (reasonIdx >= 0) {
            int end = desc.indexOf("]", reasonIdx);
            if (end > reasonIdx) {
                String reason = desc.substring(reasonIdx + 8, end);
                if (!reason.isEmpty()) {
                    System.out.println("    REASON: " + reason);
                }
            }
        }

        // Extract score
        int scoreIdx = desc.indexOf("score=");
        if (scoreIdx >= 0) {
            String scoreStr = desc.substring(scoreIdx + 6, desc.indexOf(" ", scoreIdx + 6));
            System.out.println("    SCORE: " + scoreStr);
        }
    }

    private static void printNonDecisionChain(int num, List<LogEntry> chain) {
        String clock = chain.get(0).getMatchClock();
        System.out.printf("--- Chain #%d [%s] RESTART/SETPIECE ---%n", num, clock);
        for (LogEntry e : chain) {
            System.out.println("  " + e.getType() + ": " + truncate(e.getDescription(), 120));
        }
        System.out.println();
    }

    private static int parseMatchClock(String clock) {
        // Parse "M:SS" → total seconds
        if (clock == null) return 0;
        String[] parts = clock.split(":");
        if (parts.length == 2) {
            try {
                int min = Integer.parseInt(parts[0]);
                int sec = Integer.parseInt(parts[1]);
                return min * 60 + sec;
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
