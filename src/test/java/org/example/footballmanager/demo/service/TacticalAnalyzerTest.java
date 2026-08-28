package org.example.footballmanager.demo.service;

import org.example.footballmanager.demo.service.controller.MatchSimulationController;
import org.example.footballmanager.demo.service.engine.SimUtils;
import org.example.footballmanager.demo.service.model.Ball;
import org.example.footballmanager.demo.service.model.Player;
import org.example.footballmanager.demo.service.model.Position;
import org.example.footballmanager.demo.service.recording.MatchSnapshot;
import org.example.footballmanager.demo.service.recording.PlayerSnapshot;
import org.example.footballmanager.demo.service.result.MatchResult;
import org.example.footballmanager.demo.service.result.MatchSimulator;
import org.example.footballmanager.demo.service.tactics.TacticsRules;

import java.util.*;

import org.junit.jupiter.api.Test;

/**
 * Tactical & Decision Analysis Test
 *
 * Analyzes a match simulation for:
 * 1. Player positions vs expected tactical positions from tactical_editor_positions
 * 2. Decision justification (football logic)
 * 3. Offside detection and retreat behavior
 * 4. Duel legitimacy
 * 5. Movement anomalies (freezes, illogical positioning)
 *
 * Run with: mvn test -Dtest=TacticalAnalyzerTest
 */
public class TacticalAnalyzerTest {

    private static final double POSITION_TOLERANCE = 1.5; // cells
    private static final double CRITICAL_DEVIATION = 2.5; // cells - major issue

    @Test
    public void runAnalysis() {
        System.out.println("=".repeat(80));
        System.out.println("TACTICAL & DECISION ANALYSIS REPORT");
        System.out.println("=".repeat(80));

        // Run multiple seeds to get variety
        long[] seeds = {42L, 1001L, 89022303103549L};

        for (long seed : seeds) {
            System.out.println("\n" + "=".repeat(80));
            System.out.println("MATCH ANALYSIS - Seed: " + seed);
            System.out.println("=".repeat(80));

            MatchSimulator sim = new MatchSimulator(seed);
            List<Player> home = MatchSimulationController.generateTeam("HOME", "Home");
            List<Player> away = MatchSimulationController.generateTeam("AWAY", "Away");
            MatchResult result = sim.simulate(home, away, "Home", "Away");

            analyzeMatch(result, seed);
        }

        System.out.println("\n" + "=".repeat(80));
        System.out.println("ANALYSIS COMPLETE");
        System.out.println("=".repeat(80));
    }

    private void analyzeMatch(MatchResult result, long seed) {
        List<MatchSnapshot> snapshots = getSnapshots(result);
        List<LogEntryExtended> logs = getLogEntries(result);

        if (snapshots.isEmpty()) {
            System.out.println("No snapshots available for analysis.");
            return;
        }

        // Analyze position deviations
        analyzePositionDeviations(snapshots);

        // Analyze decision patterns
        analyzeDecisionPatterns(logs, snapshots);

        // Analyze offside behavior
        analyzeOffsideBehavior(logs, snapshots);

        // Analyze carrier freeze issues
        analyzeCarrierBehavior(logs, snapshots);

        // Analyze pass logic
        analyzePassLogic(logs, snapshots);

        // Analyze set piece positions
        analyzeSetPiecePositions(logs, snapshots);
    }

    @SuppressWarnings("unchecked")
    private List<MatchSnapshot> getSnapshots(MatchResult result) {
        try {
            java.lang.reflect.Field field = result.getClass().getDeclaredField("snapshots");
            field.setAccessible(true);
            return (List<MatchSnapshot>) field.get(result);
        } catch (Exception e) {
            System.err.println("Could not access snapshots: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    @SuppressWarnings("unchecked")
    private List<LogEntryExtended> getLogEntries(MatchResult result) {
        List<LogEntryExtended> entries = new ArrayList<>();
        try {
            java.lang.reflect.Field field = result.getClass().getDeclaredField("logs");
            field.setAccessible(true);
            List<?> rawLogs = (List<?>) field.get(result);
            for (Object raw : rawLogs) {
                if (raw instanceof org.example.footballmanager.demo.service.result.LogEntry le) {
                    entries.add(new LogEntryExtended(le));
                }
            }
        } catch (Exception e) {
            System.err.println("Could not access logs: " + e.getMessage());
        }
        return entries;
    }

    // =========================================================================
    // POSITION DEVIATION ANALYSIS
    // =========================================================================

    private void analyzePositionDeviations(List<MatchSnapshot> snapshots) {
        System.out.println("\n--- POSITION DEVIATION ANALYSIS ---");

        Map<String, List<DeviationRecord>> deviationsByRole = new HashMap<>();
        int totalChecks = 0;
        int majorDeviations = 0;

        TacticsRules tacticsRules = new TacticsRules();

        for (MatchSnapshot snap : snapshots) {
            Position ballPos = snap.ballPosition();
            if (ballPos == null) continue;

            String ballStateKey = TacticsRules.ballStateKey(ballPos);

            for (PlayerSnapshot ps : snap.players()) {
                if (ps == null) continue;

                // Calculate expected position
                Position expected = tacticsRules.desiredCell(
                        ps.role(), ballPos, ps.team());

                Position actual = ps.position();
                if (actual == null) continue;

                double deviation = SimUtils.distance(actual, expected);

                totalChecks++;

                if (deviation > POSITION_TOLERANCE) {
                    majorDeviations++;

                    String reason = inferDeviationReason(
                            ps.role(), actual, expected, ballPos, snap, ps.team());

                    deviationsByRole.computeIfAbsent(ps.role(), k -> new ArrayList<>())
                            .add(new DeviationRecord(
                                    snap.tick(), ps.label(), ps.role(),
                                    actual, expected, deviation, reason));
                }
            }
        }

        System.out.printf("Total position checks: %d%n", totalChecks);
        System.out.printf("Major deviations (>%.1f cells): %d (%.1f%%)%n",
                POSITION_TOLERANCE, majorDeviations,
                100.0 * majorDeviations / Math.max(1, totalChecks));

        // Report top deviations by role
        for (Map.Entry<String, List<DeviationRecord>> entry : deviationsByRole.entrySet()) {
            List<DeviationRecord> records = entry.getValue();
            if (records.size() > 3) {
                System.out.printf("%n  Role %s: %d deviations%n", entry.getKey(), records.size());

                // Group by reason
                Map<String, Long> reasons = new HashMap<>();
                for (DeviationRecord r : records) {
                    reasons.merge(r.reason, 1L, Long::sum);
                }
                for (Map.Entry<String, Long> reason : reasons.entrySet()) {
                    System.out.printf("    - %s: %d occurrences%n", reason.getKey(), reason.getValue());
                }
            }
        }
    }

    private String inferDeviationReason(String role, Position actual, Position expected,
                                         Position ballPos, MatchSnapshot snap, String team) {
        // GK anchor check
        if ("GK".equals(role)) {
            return "GK_ANCHOR (expected - GK stays near goal line)";
        }

        // Check if in offside retreat (player ahead of ball)
        boolean home = "HOME".equals(team);
        double ballRow = ballPos.getRow();
        double playerRow = actual.getRow();

        if (home && playerRow > ballRow + 1.0) {
            return "OFFSIDE_RETREAT (player ahead of ball, likely retreating)";
        }
        if (!home && playerRow < ballRow - 1.0) {
            return "OFFSIDE_RETREAT (player ahead of ball, likely retreating)";
        }

        // Threat override check - player moved toward opponent
        PlayerSnapshot carrier = findCarrier(snap);
        if (carrier != null) {
            double distToCarrier = SimUtils.distance(actual, carrier.position());
            if (distToCarrier < 2.0) {
                return "THREAT_OVERRIDE (player tracking/marking opponent)";
            }
        }

        // Check if player is chasing loose ball
        if (snap.ballState() != null &&
                snap.ballState().toString().contains("LOOSE")) {
            return "CHASE_LOOSE_BALL (player pursuing loose ball)";
        }

        // Ball out of bounds - player reacting to set piece
        if (ballPos.getRow() < 1 || ballPos.getRow() > 7 ||
                ballPos.getColumn() < 1 || ballPos.getColumn() > 6) {
            return "SET_PIECE_REACTION (ball out of bounds)";
        }

        return "UNKNOWN_OVERRIDE";
    }

    private PlayerSnapshot findCarrier(MatchSnapshot snap) {
        if (snap.ballCarrierId() == null) return null;
        for (PlayerSnapshot p : snap.players()) {
            if (p.id() != null && p.id().equals(snap.ballCarrierId())) {
                return p;
            }
        }
        return null;
    }

    // =========================================================================
    // DECISION PATTERN ANALYSIS
    // =========================================================================

    private void analyzeDecisionPatterns(List<LogEntryExtended> logs, List<MatchSnapshot> snapshots) {
        System.out.println("\n--- DECISION PATTERN ANALYSIS ---");

        Map<String, Integer> actionCounts = new HashMap<>();
        Map<String, Integer> decisionCounts = new HashMap<>();
        int illogicalPasses = 0;
        int crossFieldPasses = 0;

        for (LogEntryExtended log : logs) {
            if ("DECISION".equals(log.getType())) {
                String desc = log.getDescription();
                decisionCounts.merge(desc, 1, Integer::sum);

                // Look for decisions in final third
                if (desc.contains("FINAL_THIRD") || desc.contains("BOX")) {
                    // Check if decision makes sense
                    if (desc.contains("SHOT") && desc.contains("score=-")) {
                        System.out.printf("  ILLOGICAL: %s chose SHOT with negative score%n", log.getPlayerName());
                    }
                }
            }

            if ("ACTION_EXECUTION".equals(log.getType())) {
                String desc = log.getDescription();
                if (desc.contains("by")) {
                    String action = desc.substring(0, desc.indexOf(" by")).replace("ACTION: ", "");
                    actionCounts.merge(action, 1, Integer::sum);

                    // Analyze pass direction
                    if (action.contains("PASS")) {
                        Position ballPos = findBallPositionAtTick(logs, snapshots, log.getTick());
                        if (ballPos != null) {
                            // Check for cross-field passes
                            if (desc.contains("intended=")) {
                                String intended = extractTarget(desc);
                                if (intended != null) {
                                    Position target = parsePosition(intended);
                                    if (target != null) {
                                        double lateralDist = Math.abs(target.getColumn() - ballPos.getColumn());
                                        double forwardDist = Math.abs(target.getRow() - ballPos.getRow());

                                        // Cross-field = lateral movement > 3 cells
                                        if (lateralDist > 3.0 && forwardDist < 2.0) {
                                            crossFieldPasses++;
                                            System.out.printf("  CROSS-FIELD PASS: %s from col=%.1f to col=%.1f%n",
                                                    log.getPlayerName(), ballPos.getColumn(), target.getColumn());
                                        }

                                        // Backward pass when should be forward
                                        if (forwardDist < -1.0) {
                                            // Player passed backward when attack was building
                                            System.out.printf("  LATE_BACKWARD_PASS: %s (may be tactical)%n", log.getPlayerName());
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        System.out.printf("%nAction distribution:%n");
        actionCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10)
                .forEach(e -> System.out.printf("  %s: %d%n", e.getKey(), e.getValue()));

        System.out.printf("%nCross-field passes: %d%n", crossFieldPasses);
        System.out.printf("Illogical passes (need deeper analysis): %d%n", illogicalPasses);
    }

    private String extractTarget(String desc) {
        int intendedIdx = desc.indexOf("intended=");
        if (intendedIdx < 0) return null;
        String rest = desc.substring(intendedIdx + 9);
        int spaceIdx = rest.indexOf(" ");
        return spaceIdx > 0 ? rest.substring(0, spaceIdx) : rest;
    }

    private Position parsePosition(String posStr) {
        if (posStr == null || !posStr.contains(",")) return null;
        try {
            String cleaned = posStr.replace("(", "").replace(")", "").trim();
            String[] parts = cleaned.split(",");
            return new Position(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]));
        } catch (Exception e) {
            return null;
        }
    }

    private Position findBallPositionAtTick(List<LogEntryExtended> logs,
                                          List<MatchSnapshot> snapshots, long tick) {
        // Find snapshot at or just before tick
        for (MatchSnapshot snap : snapshots) {
            if (snap.tick() == tick) {
                return snap.ballPosition();
            }
        }
        return null;
    }

    // =========================================================================
    // OFFSIDE ANALYSIS
    // =========================================================================

    private void analyzeOffsideBehavior(List<LogEntryExtended> logs, List<MatchSnapshot> snapshots) {
        System.out.println("\n--- OFFSIDE BEHAVIOR ANALYSIS ---");

        List<OffsideEvent> offsideEvents = new ArrayList<>();
        List<OffsideRetreatEvent> retreatEvents = new ArrayList<>();

        for (LogEntryExtended log : logs) {
            if ("INFO".equals(log.getType()) &&
                    (log.getDescription().contains("OFFSIDE") ||
                            log.getDescription().contains("VAR OVERTURNED offside"))) {

                String desc = log.getDescription();
                boolean overturned = desc.contains("OVERTURNED");

                offsideEvents.add(new OffsideEvent(
                        log.getTick(),
                        log.getMatchClock(),
                        log.getTeam(),
                        log.getPlayerName(),
                        overturned
                ));
            }

            // Look for offside retreat indicators
            if ("INFO".equals(log.getType()) &&
                    log.getDescription().contains("retreat")) {
                retreatEvents.add(new OffsideRetreatEvent(
                        log.getTick(),
                        log.getMatchClock(),
                        log.getPlayerName()
                ));
            }
        }

        System.out.printf("Total offside calls: %d%n", offsideEvents.size());

        long confirmed = offsideEvents.stream().filter(e -> !e.overturned).count();
        long overturned = offsideEvents.stream().filter(e -> e.overturned).count();

        System.out.printf("  - Confirmed: %d (%.1f%%)%n", confirmed, 100.0 * confirmed / Math.max(1, offsideEvents.size()));
        System.out.printf("  - Overturned by VAR: %d (%.1f%%)%n", overturned, 100.0 * overturned / Math.max(1, offsideEvents.size()));

        if (!retreatEvents.isEmpty()) {
            System.out.printf("%nOffside retreat activations detected: %d%n", retreatEvents.size());
            retreatEvents.forEach(e -> System.out.printf("  - %s at %s%n", e.playerName, e.matchClock));
        } else {
            System.out.println("\nWARNING: No offside retreat events detected!");
            System.out.println("  This suggests offside retreat may not be triggering correctly.");
            System.out.println("  (Players who are repeatedly offside should retreat after 3 consecutive offsides)");
        }

        // Check if offsides are happening in logical situations
        System.out.println("\nOffside situations:");
        offsideEvents.stream()
                .filter(e -> !e.overturned)
                .limit(5)
                .forEach(e -> System.out.printf("  %s: %s (minute %s)%n",
                        e.team, e.playerName, e.matchClock));
    }

    // =========================================================================
    // CARRIER BEHAVIOR ANALYSIS (Freeze Detection)
    // =========================================================================

    private void analyzeCarrierBehavior(List<LogEntryExtended> logs, List<MatchSnapshot> snapshots) {
        System.out.println("\n--- CARRIER BEHAVIOR / FREEZE DETECTION ---");

        // Track consecutive CARRY actions by same player
        Map<String, CarrierStreak> carrierStreaks = new HashMap<>();
        List<FreezeEvent> potentialFreezes = new ArrayList<>();

        for (LogEntryExtended log : logs) {
            if ("ACTION_EXECUTION".equals(log.getType())) {
                String desc = log.getDescription();

                if (desc.contains("CARRY")) {
                    String playerName = log.getPlayerName();
                    CarrierStreak streak = carrierStreaks.computeIfAbsent(playerName, k -> new CarrierStreak(playerName));

                    long currentTick = log.getTick();
                    if (streak.lastTick > 0 && (currentTick - streak.lastTick) < 100) {
                        streak.streakTicks += (currentTick - streak.lastTick);
                        streak.actions++;
                    } else {
                        streak.streakTicks = 0;
                        streak.actions = 0;
                    }

                    streak.lastTick = currentTick;

                    if (streak.streakTicks > 500) { // ~10 seconds
                        potentialFreezes.add(new FreezeEvent(
                                log.getTick(), log.getMatchClock(), playerName,
                                streak.streakTicks, "EXCESSIVE_CARRY_DURATION"
                        ));
                    }
                } else {
                    // Non-carry action resets streak
                    String playerName = log.getPlayerName();
                    carrierStreaks.remove(playerName);
                }
            }
        }

        if (potentialFreezes.isEmpty()) {
            System.out.println("No carrier freezes detected (good).");
        } else {
            System.out.println("POTENTIAL CARRIER FREEZES DETECTED:");
            potentialFreezes.forEach(f -> System.out.printf(
                    "  %s held ball for %d ticks (~%.1f sec) - %s%n",
                    f.playerName, f.durationTicks,
                    f.durationTicks / 50.0, // Assuming ~50 ticks/sec
                    f.reason));
        }

        // Analyze carrier action distribution
        Map<String, Integer> carrierActions = new HashMap<>();
        for (LogEntryExtended log : logs) {
            if ("ACTION_EXECUTION".equals(log.getType())) {
                String desc = log.getDescription();
                if (desc.contains("by")) {
                    String action = desc.substring(0, desc.indexOf(" by")).replace("ACTION: ", "");
                    if (action.contains("CARRY")) {
                        carrierActions.merge(log.getPlayerName(), 1, Integer::sum);
                    }
                }
            }
        }

        System.out.println("\nTop ball carriers (by CARRY action count):");
        carrierActions.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5)
                .forEach(e -> System.out.printf("  %s: %d carries%n", e.getKey(), e.getValue()));
    }

    // =========================================================================
    // PASS LOGIC ANALYSIS
    // =========================================================================

    private void analyzePassLogic(List<LogEntryExtended> logs, List<MatchSnapshot> snapshots) {
        System.out.println("\n--- PASS LOGIC ANALYSIS ---");

        int totalPasses = 0;
        int completedPasses = 0;
        Map<String, PassAnalysis> passByRole = new HashMap<>();

        for (LogEntryExtended log : logs) {
            if ("ACTION_EXECUTION".equals(log.getType()) && log.getDescription().contains("PASS")) {
                totalPasses++;
                String playerName = log.getPlayerName();

                PassAnalysis analysis = passByRole.computeIfAbsent(playerName, k -> new PassAnalysis(playerName));
                analysis.total++;

                // Check outcome from subsequent logs
                long nextTick = log.getTick() + 1;
                for (LogEntryExtended next : logs) {
                    if (next.getTick() >= nextTick && next.getTick() <= nextTick + 50) {
                        if ("ACTION_OUTCOME".equals(next.getType()) &&
                                next.getDescription().contains("RECEIVED")) {
                            analysis.completed++;
                            completedPasses++;
                        }
                    }
                }
            }
        }

        System.out.printf("Total passes: %d%n", totalPasses);
        System.out.printf("Pass completion rate: %.1f%%%n",
                100.0 * completedPasses / Math.max(1, totalPasses));

        // Check for illogical passing situations
        System.out.println("\nChecking for illogical pass patterns...");

        for (LogEntryExtended log : logs) {
            if ("DECISION".equals(log.getType())) {
                String desc = log.getDescription();

                // Player alone on wing but doesn't run toward goal
                if (desc.contains("WNG") || desc.contains("wing")) {
                    if (desc.contains("CARRY") && desc.contains("score=")) {
                        // Extract score
                        int scoreStart = desc.indexOf("score=") + 6;
                        int scoreEnd = desc.indexOf(" ", scoreStart);
                        if (scoreEnd < 0) scoreEnd = desc.length();
                        try {
                            double score = Double.parseDouble(desc.substring(scoreStart, scoreEnd));
                            if (score < 0.3) {
                                System.out.printf("  LOW_SCORE_CARRY: %s chose CARRY with score %.3f%n",
                                        log.getPlayerName(), score);
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
    }

    // =========================================================================
    // SET PIECE POSITION ANALYSIS
    // =========================================================================

    private void analyzeSetPiecePositions(List<LogEntryExtended> logs, List<MatchSnapshot> snapshots) {
        System.out.println("\n--- SET PIECE POSITION ANALYSIS ---");

        List<SetPieceEvent> corners = new ArrayList<>();
        List<SetPieceEvent> goalKicks = new ArrayList<>();

        for (LogEntryExtended log : logs) {
            if ("RESTART".equals(log.getType()) || "INFO".equals(log.getType())) {
                String desc = log.getDescription();

                if (desc.contains("CORNER")) {
                    corners.add(new SetPieceEvent(
                            log.getTick(), log.getMatchClock(),
                            desc.contains("HOME") ? "HOME" : "AWAY",
                            "CORNER", desc
                    ));
                }

                if (desc.contains("GOAL KICK")) {
                    goalKicks.add(new SetPieceEvent(
                            log.getTick(), log.getMatchClock(),
                            desc.contains("HOME") ? "HOME" : "AWAY",
                            "GOAL_KICK", desc
                    ));
                }
            }
        }

        System.out.printf("Total corners: %d%n", corners.size());
        System.out.printf("Total goal kicks: %d%n", goalKicks.size());

        // Analyze corner positions
        if (!corners.isEmpty()) {
            System.out.println("\nCorner positions detected:");
            corners.forEach(c -> System.out.printf("  %s at %s - %s%n",
                    c.team, c.matchClock, c.description));

            // Check if corner positions are at the correct locations
            // According to user: corner should be at row 7 (or 1 for AWAY) at columns 1 or 6
            System.out.println("\nNOTE: Corner positions should be at field corners (row 7, col 1 or 6 for HOME attacking)");
            System.out.println("      Actual positions depend on where ball went out.");
        }

        // Goal kick analysis
        if (!goalKicks.isEmpty()) {
            System.out.println("\nGoal kick analysis:");
            goalKicks.stream().limit(3)
                    .forEach(g -> System.out.printf("  %s at %s - %s%n",
                            g.team, g.matchClock, g.description));
        }
    }

    // =========================================================================
    // HELPER CLASSES
    // =========================================================================

    private static class DeviationRecord {
        long tick;
        String label;
        String role;
        Position actual;
        Position expected;
        double deviation;
        String reason;

        DeviationRecord(long tick, String label, String role, Position actual,
                        Position expected, double deviation, String reason) {
            this.tick = tick;
            this.label = label;
            this.role = role;
            this.actual = actual;
            this.expected = expected;
            this.deviation = deviation;
            this.reason = reason;
        }
    }

    private static class LogEntryExtended {
        private final org.example.footballmanager.demo.service.result.LogEntry delegate;

        LogEntryExtended(org.example.footballmanager.demo.service.result.LogEntry delegate) {
            this.delegate = delegate;
        }

        public String getType() { return delegate.getType().name(); }
        public String getDescription() { return delegate.getDescription(); }
        public String getTeam() { return delegate.getTeam(); }
        public String getPlayerName() { return delegate.getPlayerName(); }
        public long getTick() { return delegate.getTick(); }
        public String getMatchClock() { return delegate.getMatchClock(); }
        public String getChannel() { return delegate.getChannel(); }
    }

    private static class OffsideEvent {
        long tick;
        String matchClock;
        String team;
        String playerName;
        boolean overturned;

        OffsideEvent(long tick, String matchClock, String team, String playerName, boolean overturned) {
            this.tick = tick;
            this.matchClock = matchClock;
            this.team = team;
            this.playerName = playerName;
            this.overturned = overturned;
        }
    }

    private static class OffsideRetreatEvent {
        long tick;
        String matchClock;
        String playerName;

        OffsideRetreatEvent(long tick, String matchClock, String playerName) {
            this.tick = tick;
            this.matchClock = matchClock;
            this.playerName = playerName;
        }
    }

    private static class CarrierStreak {
        String playerName;
        long lastTick = 0;
        long streakTicks = 0;
        int actions = 0;

        CarrierStreak(String playerName) {
            this.playerName = playerName;
        }
    }

    private static class FreezeEvent {
        long tick;
        String matchClock;
        String playerName;
        long durationTicks;
        String reason;

        FreezeEvent(long tick, String matchClock, String playerName, long durationTicks, String reason) {
            this.tick = tick;
            this.matchClock = matchClock;
            this.playerName = playerName;
            this.durationTicks = durationTicks;
            this.reason = reason;
        }
    }

    private static class PassAnalysis {
        String playerName;
        int total = 0;
        int completed = 0;

        PassAnalysis(String playerName) {
            this.playerName = playerName;
        }
    }

    private static class SetPieceEvent {
        long tick;
        String matchClock;
        String team;
        String type;
        String description;

        SetPieceEvent(long tick, String matchClock, String team, String type, String description) {
            this.tick = tick;
            this.matchClock = matchClock;
            this.team = team;
            this.type = type;
            this.description = description;
        }
    }
}
