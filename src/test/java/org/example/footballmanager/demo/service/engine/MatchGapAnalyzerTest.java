package org.example.footballmanager.demo.service.engine;

import org.example.footballmanager.demo.service.model.*;
import org.example.footballmanager.demo.service.result.MatchSimulator;
import org.example.footballmanager.demo.service.result.MatchResult;
import org.example.footballmanager.demo.service.result.ActionLogService;
import org.example.footballmanager.demo.service.result.LogEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs a match, captures all logs, and analyzes gaps between consecutive log events.
 *
 * Checks for:
 * - Gaps > 5 seconds between consecutive log events (flagged)
 * - Legitimate interruptions (offside, VAR, foul, card, corner, goal kick, throw-in, free kick, penalty)
 * - Unexplained long gaps (> 2 minutes) indicating missing logs
 */
class MatchGapAnalyzerTest {

    static int parseClockSeconds(String clock) {
        if (clock == null || clock.isEmpty()) return -1;
        String[] parts = clock.split(":");
        if (parts.length != 2) return -1;
        try {
            int minutes = Integer.parseInt(parts[0]);
            int seconds = Integer.parseInt(parts[1]);
            return minutes * 60 + seconds;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static String categorize(String desc) {
        if (desc == null) return "NONE";
        String d = desc.toUpperCase();
        if (d.contains("OFFSIDE") || d.contains("VAR OFFSIDE")) return "OFFSIDE";
        if (d.contains("VAR")) return "VAR";
        if (d.contains("FOUL")) return "FOUL";
        if (d.contains("CARD")) return "CARD";
        if (d.contains("CORNER")) return "CORNER";
        if (d.contains("GOAL KICK")) return "GOAL_KICK";
        if (d.contains("THROW-IN") || d.contains("THROW IN")) return "THROW_IN";
        if (d.contains("FREE KICK")) return "FREE_KICK";
        if (d.contains("PENALTY")) return "PENALTY";
        if (d.contains("SUBSTITUTION")) return "SUBSTITUTION";
        if (d.contains("INJURY")) return "INJURY";
        if (d.contains("GOAL")) return "GOAL";
        if (d.contains("BALL OUT")) return "BALL_OUT";
        return "NONE";
    }

    static List<Player> buildTeam(String side, int startIdx) {
        List<Player> team = new ArrayList<>();
        String[] positions = {"GK", "DCL", "DCB", "DCR", "DFL", "CML", "CMB", "CMR", "WNL", "STL", "STR"};
        for (int i = 0; i < 11; i++) {
            int r = (i == 0) ? 1 : (i <= 4) ? 3 : (i <= 7) ? 4 : 5;
            int c = (i == 0) ? 3 : 1 + (i % 6);
            team.add(new Player(side + "_" + i, side + " " + positions[i], side, positions[i],
                    new Position(r, c), new Position(r, c), PlayerSkills.neutral()));
        }
        return team;
    }

    /**
     * Run a full match, dump every log entry, then run gap analysis.
     * Flags any gap > 5 seconds; flags as BUG if > 2 min with no interruption reason.
     */
    @Test
    void captureAndAnalyzeGaps() {
        List<Player> home = buildTeam("HOME", 0);
        List<Player> away = buildTeam("AWAY", 0);

        MatchSimulator sim = new MatchSimulator(42);
        MatchResult result = sim.simulate(home, away, "Home FC", "Away United");
        List<LogEntry> logs = new ArrayList<>(result.logs());

        logs.sort(Comparator.comparingLong(LogEntry::getTick));

        System.out.println("\n===== LOG ENTRIES (" + logs.size() + ") =====");
        for (LogEntry e : logs) {
            String clock = e.getMatchClock();
            int sec = parseClockSeconds(clock);
            System.out.printf("%02d:%02d [%s] %s%n",
                    sec >= 0 ? sec / 60 : 0, sec >= 0 ? sec % 60 : 0,
                    e.getType(), e.getDescription());
        }

        System.out.println("\n===== GAP ANALYSIS =====");
        int issueCount = 0;
        for (int i = 1; i < logs.size(); i++) {
            LogEntry prev = logs.get(i - 1);
            LogEntry curr = logs.get(i);
            int prevSec = parseClockSeconds(prev.getMatchClock());
            int currSec = parseClockSeconds(curr.getMatchClock());
            if (prevSec < 0 || currSec < 0) continue;
            int gap = currSec - prevSec;
            if (gap < 0) gap += 90 * 60;
            if (gap > 5) {
                String cat = categorize(prev.getDescription());
                boolean legitimate = cat.equals("OFFSIDE") || cat.equals("VAR") || cat.equals("FOUL")
                        || cat.equals("CARD") || cat.equals("CORNER") || cat.equals("GOAL_KICK")
                        || cat.equals("THROW_IN") || cat.equals("FREE_KICK") || cat.equals("PENALTY")
                        || cat.equals("SUBSTITUTION") || cat.equals("INJURY") || cat.equals("GOAL")
                        || cat.equals("BALL_OUT");
                String status = (legitimate && gap <= 120) ? "OK (interruption)"
                        : (gap > 120) ? "BUG (>2 min)" : "SUSPICIOUS";
                issueCount++;
                System.out.printf("GAP %02d:%02d -> %02d:%02d = %d sec | %s | %s%n",
                        prevSec / 60, prevSec % 60, currSec / 60, currSec % 60, gap,
                        prev.getDescription().substring(0, Math.min(70, prev.getDescription().length())), status);
            }
        }
        if (issueCount == 0) {
            System.out.println("No gaps > 5 seconds found.");
        }
        System.out.println("===== DONE =====\n");
    }
}