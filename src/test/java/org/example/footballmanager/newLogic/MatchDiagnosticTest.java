package org.example.footballmanager.newLogic;

import org.example.footballmanager.newLogic.engine.*;
import org.example.footballmanager.newLogic.model.*;
import org.example.footballmanager.newLogic.model.event.*;
import org.example.footballmanager.newLogic.service.MatchOrchestrator;
import org.example.footballmanager.newLogic.store.MatchStore;
import org.junit.jupiter.api.Test;

import java.util.*;

public class MatchDiagnosticTest {

    @Test
    void runDetailedMatchAnalysis() {
        MatchStore store = new MatchStore();
        MatchOrchestrator orchestrator = new MatchOrchestrator(store);
        long matchId = orchestrator.startMatch("Test Home", "Test Away");
        Match match = orchestrator.getMatch(matchId);

        MatchSimulator simulator = orchestrator.getSimulator();
        simulateAndAnalyze(simulator, match, 5);
    }

    private void simulateAndAnalyze(MatchSimulator simulator, Match match, int analysisMinutes) {
        simulator.simulate(match);

        var state = simulator.getState();
        var tickHistory = state.tickHistory;

        System.out.println("\n" + "=".repeat(80));
        System.out.println("MATCH DIAGNOSTIC REPORT - First " + analysisMinutes + " minutes");
        System.out.println("=".repeat(80));

        System.out.println("\n--- SETUP ---");
        System.out.println("Formation check:");
        for (PlayerSnapshot snap : tickHistory.get(0).players()) {
            String slotKey = state.playerSlotKeys.getOrDefault(snap.playerId(), "UNKNOWN");
            System.out.printf("  %s (ID=%d, %s) slot=%s pos=(%.1f,%.1f)%n",
                snap.name(), snap.playerId(), snap.teamSide(), slotKey, snap.x(), snap.y());
        }

        System.out.println("\n--- TACTIC ZONES (5x5 grid) ---");
        printZoneGrid();

        System.out.println("\n--- SAMPLE TICKS ANALYSIS ---");
        int[] sampleMinutes = {1, 2, 3, 4, 5};
        int ticksPerMinute = 120;

        for (int analysisMinute : sampleMinutes) {
            if (analysisMinute > analysisMinutes) break;
            int tickStart = (analysisMinute - 1) * ticksPerMinute;
            int tickEnd = Math.min(tickStart + 20, tickHistory.size() - 1);

            System.out.printf("%n=== MINUTE %d (ticks %d-%d) ===%n", analysisMinute, tickStart, tickEnd);

            for (int t = tickStart; t < tickEnd; t += 3) {
                var tick = tickHistory.get(t);
                printTickAnalysis(tick, t, state);
            }
        }

        System.out.println("\n" + "=".repeat(80));
        System.out.println("TACTICAL COMPLIANCE CHECK");
        System.out.println("=".repeat(80));

        checkTacticalCompliance(state, tickHistory);

        System.out.println("\n" + "=".repeat(80));
        System.out.println("BALL CARRIER BEHAVIOR ANALYSIS");
        System.out.println("=".repeat(80));

        analyzeBallCarrierBehavior(state, tickHistory);

        System.out.println("\n" + "=".repeat(80));
        System.out.println("DECISION ANALYSIS");
        System.out.println("=".repeat(80));

        analyzeDecisions(state);

        System.out.println("\n" + "=".repeat(80));
        System.out.println("FINDINGS");
        System.out.println("=".repeat(80));

        printFindings(state, tickHistory);
    }

    private void printZoneGrid() {
        System.out.println("  5x5 Grid Zones (X bands: 10,30,50,70,90 | Y bands: 10,26,50,74,90)");
        System.out.println("  +----+----+----+----+----+");
        System.out.println("  | 0,4| 1,4| 2,4| 3,4| 4,4|  <- Top row (y=10)");
        System.out.println("  +----+----+----+----+----+");
        System.out.println("  | 0,3| 1,3| 2,3| 3,3| 4,3|");
        System.out.println("  +----+----+----+----+----+");
        System.out.println("  | 0,2| 1,2| 2,2| 3,2| 4,2|  <- Middle row (y=50)");
        System.out.println("  +----+----+----+----+----+");
        System.out.println("  | 0,1| 1,1| 2,1| 3,1| 4,1|");
        System.out.println("  +----+----+----+----+----+");
        System.out.println("  | 0,0| 1,0| 2,0| 3,0| 4,0|  <- Bottom row (y=90)");
        System.out.println("  +----+----+----+----+----+");
        System.out.println("  HOME -> attacks toward right (CELL_4_*)");
        System.out.println("  AWAY -> attacks toward left (CELL_0_*)");
    }

    private void printTickAnalysis(TickSnapshot tick, int tickIndex, MatchState state) {
        System.out.printf("%n  [Tick %d] Ball: (%.1f, %.1f)%n", tickIndex, tick.ball().x(), tick.ball().y());

        Long carrierId = tick.carrierId();
        if (carrierId != null) {
            var carrier = tick.players().stream().filter(p -> p.playerId() == carrierId).findFirst().orElse(null);
            if (carrier != null) {
                String intent = carrier.intent() != null ? carrier.intent().name() : "null";
                System.out.printf("  Carrier: %s (ID=%d) pos=(%.1f,%.1f) intent=%s%n",
                    carrier.name(), carrierId, carrier.x(), carrier.y(), intent);
            }
        }

        if (state.blendTargets != null && !state.blendTargets.isEmpty()) {
            for (var entry : state.blendTargets.entrySet()) {
                var bt = entry.getValue();
                System.out.printf("  Blend[%d]: target=(%.1f,%.1f) progress=%.2f%n",
                    entry.getKey(), bt.targetX(), bt.targetY(), bt.progress());
            }
        }

        int ballZoneX = (int) Math.max(0, Math.min(4, (tick.ball().x() - 4) / 18.4));
        int ballZoneY = (int) Math.max(0, Math.min(4, (tick.ball().y() - 4) / 18.4));
        System.out.printf("  Ball zone: CELL_%d_%d%n", ballZoneX, ballZoneY);

        var events = state.events.stream()
            .filter(e -> e.tick() >= tickIndex && e.tick() < tickIndex + 3)
            .toList();
        if (!events.isEmpty()) {
            System.out.printf("  Events in this window: %d%n", events.size());
            for (var ev : events) {
                if (!(ev instanceof BallCarrierDecisionEvent)) {
                    System.out.printf("    %d' [%s]%n", ev.minute(), ev.type().name());
                }
            }
        }
    }

    private void checkTacticalCompliance(MatchState state, List<TickSnapshot> tickHistory) {
        System.out.println("\n--- PLAYER POSITION vs DESIRED POSITION ---");

        Map<Long, List<double[]>> positionErrors = new HashMap<>();
        Map<Long, String> playerNames = new HashMap<>();
        Map<Long, String> slotKeys = new HashMap<>();

        for (TickSnapshot tick : tickHistory) {
            for (PlayerSnapshot snap : tick.players()) {
                long pid = snap.playerId();
                double desiredX = snap.desiredPosition()[0];
                double desiredY = snap.desiredPosition()[1];
                double distToDesired = Math.sqrt(
                    Math.pow(snap.x() - desiredX, 2) +
                    Math.pow(snap.y() - desiredY, 2));

                playerNames.putIfAbsent(pid, snap.name());
                slotKeys.putIfAbsent(pid, state.playerSlotKeys.getOrDefault(pid, "UNK"));

                if (distToDesired > 5.0) {
                    positionErrors.computeIfAbsent(pid, k -> new ArrayList<>()).add(new double[]{distToDesired, snap.x(), snap.y(), desiredX, desiredY});
                }
            }
        }

        for (var entry : positionErrors.entrySet()) {
            long pid = entry.getKey();
            List<double[]> errors = entry.getValue();
            double avgError = errors.stream().mapToDouble(e -> e[0]).average().orElse(0);
            double maxError = errors.stream().mapToDouble(e -> e[0]).max().orElse(0);

            System.out.printf("  %s (ID=%d, slot=%s): avgError=%.1f maxError=%.1f%n",
                playerNames.get(pid), pid, slotKeys.get(pid), avgError, maxError);

            if (maxError > 15.0) {
                var worst = errors.stream().max(Comparator.comparingDouble(e -> e[0])).orElse(null);
                if (worst != null) {
                    System.out.printf("    WORST: at (%.1f,%.1f) desired (%.1f,%.1f) dist=%.1f%n",
                        worst[1], worst[2], worst[3], worst[4], worst[0]);
                }
            }
        }

        if (positionErrors.isEmpty()) {
            System.out.println("  All players within 5 units of desired position - GOOD!");
        }
    }

    private void analyzeBallCarrierBehavior(MatchState state, List<TickSnapshot> tickHistory) {
        System.out.println("\n--- BALL CARRIER HOLDING TIME ---");

        Map<Long, BallCarrierStats> carrierStats = new HashMap<>();
        long currentCarrier = -1;
        int holdTicks = 0;
        int maxHoldTicks = 0;

        for (int i = 0; i < tickHistory.size(); i++) {
            var tick = tickHistory.get(i);
            Long carrierId = tick.carrierId();

            if (carrierId != null && !carrierId.equals(currentCarrier)) {
                if (currentCarrier != -1 && holdTicks > 10) {
                    carrierStats.computeIfAbsent(currentCarrier, k -> new BallCarrierStats())
                        .recordHold(holdTicks);
                    if (holdTicks > maxHoldTicks) maxHoldTicks = holdTicks;
                }
                currentCarrier = carrierId;
                holdTicks = 0;
            }
            holdTicks++;
        }

        for (var entry : carrierStats.entrySet()) {
            var stats = entry.getValue();
            System.out.printf("  Carrier ID %d: holds=%d avgHold=%.1f maxHold=%d%n",
                entry.getKey(), stats.holdCount, stats.avgHold(), stats.maxHold);
        }

        System.out.printf("  Overall max hold ticks: %d (~%.1f seconds)%n", maxHoldTicks, maxHoldTicks / 2.0);

        if (maxHoldTicks > 60) {
            System.out.println("  ⚠️  WARNING: Ball carrier held ball for >30 seconds!");
        }
    }

    private void analyzeDecisions(MatchState state) {
        List<BallCarrierDecisionEvent> decisions = state.events.stream()
            .filter(e -> e instanceof BallCarrierDecisionEvent)
            .map(e -> (BallCarrierDecisionEvent) e)
            .limit(50)
            .toList();

        System.out.println("\n--- RECENT DECISIONS (first 50) ---");
        Map<String, Integer> decisionCounts = new HashMap<>();
        for (var d : decisions) {
            decisionCounts.merge(d.action(), 1, Integer::sum);
            System.out.printf("  %d' [%s] %s: %s (reason: %s) pos=(%.1f,%.1f)%n",
                d.minute(), d.tick(), d.carrierName(), d.action(), d.reason(), d.x(), d.y());
        }

        System.out.println("\n--- DECISION DISTRIBUTION ---");
        int totalDecisions = decisionCounts.values().stream().mapToInt(Integer::intValue).sum();
        for (var entry : decisionCounts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).toList()) {
            System.out.printf("  %s: %d (%.1f%%)%n", entry.getKey(), entry.getValue(),
                totalDecisions > 0 ? entry.getValue() * 100.0 / totalDecisions : 0);
        }
    }

    private void printFindings(MatchState state, List<TickSnapshot> tickHistory) {
        List<String> findings = new ArrayList<>();

        findings.add("1. TACTICAL FOLLOWING: " + checkTacticalFollowing(state, tickHistory));
        findings.add("2. BALL CARRIER HOLDING: " + checkBallCarrierHolding(state, tickHistory));
        findings.add("3. DECISION QUALITY: " + checkDecisionQuality(state));
        findings.add("4. PLAYER MOVEMENT: " + checkPlayerMovement(state, tickHistory));
        findings.add("5. PRESS BEHAVIOR: " + checkPressBehavior(state, tickHistory));
        findings.add("6. OFFSIDE TRAP: " + checkOffsideTrap(state, tickHistory));

        for (String f : findings) {
            System.out.println("  " + f);
        }
    }

    private String checkTacticalFollowing(MatchState state, List<TickSnapshot> tickHistory) {
        int totalDeviation = 0;
        int samples = 0;
        int excessiveDeviation = 0;

        for (int i = 0; i < Math.min(500, tickHistory.size()); i++) {
            var tick = tickHistory.get(i);
            for (PlayerSnapshot snap : tick.players()) {
                double desiredX = snap.desiredPosition()[0];
                double desiredY = snap.desiredPosition()[1];
                double dev = Math.sqrt(Math.pow(snap.x() - desiredX, 2) + Math.pow(snap.y() - desiredY, 2));
                totalDeviation += dev;
                samples++;
                if (dev > 10) excessiveDeviation++;
            }
        }

        double avgDev = samples > 0 ? totalDeviation / samples : 0;
        double excessivePct = samples > 0 ? excessiveDeviation * 100.0 / samples : 0;

        if (excessivePct > 30) {
            return String.format("ISSUE - %.1f%% of samples have >10 unit deviation (avg dev=%.1f)", excessivePct, avgDev);
        }
        return String.format("OK - %.1f%% excessive deviation (avg dev=%.1f)", excessivePct, avgDev);
    }

    private String checkBallCarrierHolding(MatchState state, List<TickSnapshot> tickHistory) {
        int maxHold = 0;
        long maxHoldCarrier = -1;
        long currentCarrier = -1;
        int holdTicks = 0;

        for (var tick : tickHistory) {
            Long cid = tick.carrierId();
            if (cid != null) {
                if (!cid.equals(currentCarrier)) {
                    if (holdTicks > maxHold) {
                        maxHold = holdTicks;
                        maxHoldCarrier = currentCarrier;
                    }
                    currentCarrier = cid;
                    holdTicks = 0;
                }
                holdTicks++;
            }
        }

        if (maxHold > 60) {
            return String.format("ISSUE - Max hold %d ticks (~%.0fs) by carrier %d", maxHold, maxHold / 2.0, maxHoldCarrier);
        }
        return String.format("OK - Max hold %d ticks", maxHold);
    }

    private String checkDecisionQuality(MatchState state) {
        List<BallCarrierDecisionEvent> decisions = state.events.stream()
            .filter(e -> e instanceof BallCarrierDecisionEvent)
            .map(e -> (BallCarrierDecisionEvent) e)
            .toList();

        Map<String, Integer> actionCounts = new HashMap<>();
        for (var d : decisions) {
            actionCounts.merge(d.action(), 1, Integer::sum);
        }

        int carryCount = actionCounts.getOrDefault("CARRY", 0);
        int passCount = actionCounts.getOrDefault("SHORT_PASS", 0) + actionCounts.getOrDefault("LONG_PASS", 0);
        int total = decisions.size();

        if (total == 0) return "No decisions recorded";

        double carryPct = carryCount * 100.0 / total;
        double passPct = passCount * 100.0 / total;

        if (carryPct > 60) {
            return String.format("ISSUE - Too much carrying (%d%%). Pass rate only %d%%", (int) carryPct, (int) passPct);
        }
        return String.format("OK - Carry %d%%, Pass %d%%", (int) carryPct, (int) passPct);
    }

    private String checkPlayerMovement(MatchState state, List<TickSnapshot> tickHistory) {
        int totalMoved = 0;
        int samples = 0;

        for (int i = 1; i < Math.min(500, tickHistory.size()); i++) {
            var prev = tickHistory.get(i - 1);
            var curr = tickHistory.get(i);

            for (PlayerSnapshot cs : curr.players()) {
                var ps = prev.players().stream().filter(p -> p.playerId() == cs.playerId()).findFirst().orElse(null);
                if (ps != null) {
                    double dist = Math.sqrt(Math.pow(cs.x() - ps.x(), 2) + Math.pow(cs.y() - ps.y(), 2));
                    if (dist > 0.1) totalMoved++;
                    samples++;
                }
            }
        }

        if (samples == 0) return "No movement data";
        double movedPct = totalMoved * 100.0 / samples;

        if (movedPct < 50) {
            return String.format("ISSUE - Only %.1f%% of samples show movement", movedPct);
        }
        return String.format("OK - %.1f%% of samples show movement", movedPct);
    }

    private String checkPressBehavior(MatchState state, List<TickSnapshot> tickHistory) {
        int pressActions = 0;
        for (var tick : tickHistory) {
            for (PlayerSnapshot snap : tick.players()) {
                if (snap.intent() == PlayerSnapshot.Intent.PRESS) pressActions++;
            }
        }
        return String.format("OK - %d press intents recorded", pressActions);
    }

    private String checkOffsideTrap(MatchState state, List<TickSnapshot> tickHistory) {
        int offsideFlags = state.events.stream().filter(e -> e instanceof OffsideEvent).mapToInt(e -> 1).sum();
        if (offsideFlags > 50) {
            return String.format("ISSUE - %d offside flags (too many!)", offsideFlags);
        }
        return String.format("OK - %d offside flags", offsideFlags);
    }

    private static class BallCarrierStats {
        int holdCount = 0;
        int totalHold = 0;
        int maxHold = 0;

        void recordHold(int ticks) {
            holdCount++;
            totalHold += ticks;
            maxHold = Math.max(maxHold, ticks);
        }

        double avgHold() {
            return holdCount > 0 ? (double) totalHold / holdCount : 0;
        }
    }
}
