package org.example.footballmanager.newLogic;

import org.example.footballmanager.newLogic.model.*;
import org.example.footballmanager.newLogic.model.event.*;
import org.example.footballmanager.newLogic.engine.ZonePositionCalculator;
import org.example.footballmanager.newLogic.service.MatchOrchestrator;
import org.example.footballmanager.newLogic.store.MatchStore;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.*;

public class PlayerMovementDiagnosticTest {

    private static final int SAMPLE_INTERVAL = 10; // print every Nth tick
    private static final int DIAGNOSTIC_MINUTES = 5;
    private static final int DIAGNOSTIC_SECONDS = 300;
    private static final double ENGINE_SECONDS_PER_TICK = 1.0;
    private static final double FIELD_LENGTH = 92.0; // 96-4
    private static final double PITCH_WIDTH = 88.0; // 94-6

    @Test
    void diagnosticFirst5Minutes() {
        MatchStore store = new MatchStore();
        MatchOrchestrator orchestrator = new MatchOrchestrator(store);

        long matchId = orchestrator.startMatch("Crvena Zvezda", "Partizan");
        MatchResult result = orchestrator.simulate(matchId);

        List<TickSnapshot> ticks = result.tickHistory();
        List<MatchEvent> events = result.events();

        // Build player registry (name + teamSide by playerId)
        Map<Long, String[]> playerRegistry = new HashMap<>();
        if (!ticks.isEmpty()) {
            for (var ps : ticks.get(0).players()) {
                playerRegistry.put(ps.playerId(), new String[]{ps.name(), ps.teamSide(), ps.position().name()});
            }
        }

        // ─────────────────────────────────────────────────
        // 1. FORMATION ANALYSIS — starting positions
        // ─────────────────────────────────────────────────
        System.out.println("=" .repeat(120));
        System.out.println("  FORMATION ANALYSIS — Starting Positions (tick 0)");
        System.out.println("=" .repeat(120));
        if (!ticks.isEmpty()) {
            TickSnapshot t0 = ticks.get(0);
            printPlayerPositions(t0.players(), t0.tick(), t0.minute(), "STARTING POSITIONS");
        }

        // ─────────────────────────────────────────────────
        // 2. PER-MINUTE ANALYSIS
        // ─────────────────────────────────────────────────
        System.out.println();
        System.out.println("=" .repeat(120));
        System.out.println("  PER-MINUTE MOVEMENT ANALYSIS");
        System.out.println("=" .repeat(120));

        for (int minute = 1; minute <= DIAGNOSTIC_MINUTES; minute++) {
            int finalMinute = minute;
            var minuteTicks = ticks.stream().filter(t -> t.minute() == finalMinute).toList();
            if (minuteTicks.isEmpty()) continue;

            // Stats for this minute
            double homeAvgX = 0, awayAvgX = 0;
            double homeAvgY = 0, awayAvgY = 0;
            double homeSpreadX = 0, awaySpreadX = 0;
            double homeSpreadY = 0, awaySpreadY = 0;
            double totalHomeMovement = 0, totalAwayMovement = 0;
            int movementSamples = 0;

            // Collect player positions at first tick of minute and last tick for movement calc
            var firstTick = minuteTicks.get(0);
            var lastTick = minuteTicks.get(minuteTicks.size() - 1);

            Map<Long, PlayerSnapshot> firstPositions = new HashMap<>();
            for (var ps : firstTick.players()) firstPositions.put(ps.playerId(), ps);

            // Calculate movement: sum distance per tick
            List<Double> homeXList = new ArrayList<>();
            List<Double> awayXList = new ArrayList<>();
            List<Double> homeYList = new ArrayList<>();
            List<Double> awayYList = new ArrayList<>();

            for (var tick : minuteTicks) {
                for (var ps : tick.players()) {
                    if ("HOME".equals(ps.teamSide())) {
                        homeAvgX += ps.x();
                        homeAvgY += ps.y();
                        homeXList.add(ps.x());
                        homeYList.add(ps.y());
                    } else {
                        awayAvgX += ps.x();
                        awayAvgY += ps.y();
                        awayXList.add(ps.x());
                        awayYList.add(ps.y());
                    }
                }
            }

            int homeCount = (int) homeXList.size();
            int awayCount = (int) awayXList.size();
            homeAvgX /= homeCount;
            homeAvgY /= homeCount;
            awayAvgX /= awayCount;
            awayAvgY /= awayCount;

            // Spread
            for (int i = 0; i < homeXList.size(); i++) {
                homeSpreadX += Math.pow(homeXList.get(i) - homeAvgX, 2);
                homeSpreadY += Math.pow(homeYList.get(i) - homeAvgY, 2);
            }
            for (int i = 0; i < awayXList.size(); i++) {
                awaySpreadX += Math.pow(awayXList.get(i) - awayAvgX, 2);
                awaySpreadY += Math.pow(awayYList.get(i) - awayAvgY, 2);
            }
            homeSpreadX = Math.sqrt(homeSpreadX / homeXList.size());
            homeSpreadY = Math.sqrt(homeSpreadY / homeYList.size());
            awaySpreadX = Math.sqrt(awaySpreadX / awayXList.size());
            awaySpreadY = Math.sqrt(awaySpreadY / awayYList.size());

            // Per-player movement across the minute
            var lastPositions = new HashMap<Long, PlayerSnapshot>();
            for (var ps : lastTick.players()) lastPositions.put(ps.playerId(), ps);

            double homeMove = 0, awayMove = 0;
            int homeMoveCount = 0, awayMoveCount = 0;
            for (var e : firstPositions.entrySet()) {
                var last = lastPositions.get(e.getKey());
                if (last != null) {
                    double d = e.getValue().distanceTo(last);
                    if ("HOME".equals(e.getValue().teamSide())) {
                        homeMove += d;
                        homeMoveCount++;
                    } else {
                        awayMove += d;
                        awayMoveCount++;
                    }
                }
            }

            // Ball stats for minute
            double ballAvgX = minuteTicks.stream().mapToDouble(t -> t.ball().x()).average().orElse(50);
            double ballMinX = minuteTicks.stream().mapToDouble(t -> t.ball().x()).min().orElse(50);
            double ballMaxX = minuteTicks.stream().mapToDouble(t -> t.ball().x()).max().orElse(50);
            long homePossTicks = minuteTicks.stream().filter(t -> {
                if (t.carrierId() == null) return false;
                var ps = t.players().stream().filter(p -> p.playerId() == t.carrierId()).findFirst();
                return ps.isPresent() && "HOME".equals(ps.get().teamSide());
            }).count();
            long awayPossTicks = minuteTicks.stream().filter(t -> {
                if (t.carrierId() == null) return false;
                var ps = t.players().stream().filter(p -> p.playerId() == t.carrierId()).findFirst();
                return ps.isPresent() && "AWAY".equals(ps.get().teamSide());
            }).count();

            double homeMovePerPlayer = homeMoveCount > 0 ? homeMove / homeMoveCount : 0;
            double awayMovePerPlayer = awayMoveCount > 0 ? awayMove / awayMoveCount : 0;

            // Events this minute
            var minuteEvents = events.stream()
                .filter(e -> e.minute() == finalMinute)
                .filter(e -> !(e instanceof MatchStartEvent || e instanceof MatchEndEvent))
                .toList();

            System.out.println();
            System.out.println("-" .repeat(120));
            System.out.printf("  MINUTE %d%n", minute);
            System.out.println("-" .repeat(120));
            System.out.printf("  %-30s %15s %15s%n", "Metric", "HOME", "AWAY");
            System.out.printf("  %-30s %15s %15s%n", "  Avg X position", String.format("%.1f", homeAvgX), String.format("%.1f", awayAvgX));
            System.out.printf("  %-30s %15s %15s%n", "  Avg Y position", String.format("%.1f", homeAvgY), String.format("%.1f", awayAvgY));
            System.out.printf("  %-30s %15s %15s%n", "  Spread X (σ)", String.format("%.1f", homeSpreadX), String.format("%.1f", awaySpreadX));
            System.out.printf("  %-30s %15s %15s%n", "  Spread Y (σ)", String.format("%.1f", homeSpreadY), String.format("%.1f", awaySpreadY));
            System.out.printf("  %-30s %15s %15s%n", "  Movement/min/player", String.format("%.1f", homeMovePerPlayer), String.format("%.1f", awayMovePerPlayer));
            System.out.printf("  %-30s %15s %15s%n", "  Possession ticks", homePossTicks, awayPossTicks);
            System.out.printf("  %-30s %15s%n", "  Ball X range", String.format("%.1f-%.1f", ballMinX, ballMaxX));
            System.out.printf("  %-30s %15s%n", "  Ball avg X", String.format("%.1f", ballAvgX));
            System.out.println();

            if (!minuteEvents.isEmpty()) {
                System.out.println("  Events:");
                for (var e : minuteEvents) {
                    System.out.println("    " + formatEvent(e));
                }
            }
        }

        // ─────────────────────────────────────────────────
        // 3. FULL PLAYER DUMP — every N ticks
        // ─────────────────────────────────────────────────
        System.out.println();
        System.out.println("=" .repeat(120));
        System.out.println("  FULL POSITION DUMP (every " + SAMPLE_INTERVAL + " ticks)");
        System.out.println("=" .repeat(120));

        int eventIdx = 0;
        for (int i = 0; i < ticks.size() && ticks.get(i).minute() <= DIAGNOSTIC_MINUTES; i++) {
            var tick = ticks.get(i);
            if (tick.tick() % SAMPLE_INTERVAL != 0) continue;

            // Print events that happened at or before this tick
            while (eventIdx < events.size() && events.get(eventIdx).tick() <= tick.tick()) {
                var e = events.get(eventIdx);
                if (!(e instanceof MatchStartEvent || e instanceof MatchEndEvent)) {
                    System.out.println("    EVENT: " + formatEvent(e));
                }
                eventIdx++;
            }

            System.out.println();
            System.out.printf("  TICK=%-4d  MIN=%d  ball=(%5.1f, %5.1f) carrier=%s transit=%s activeEvent=%s%n",
                tick.tick(), tick.minute(),
                tick.ball().x(), tick.ball().y(),
                tick.carrierId() != null ? tick.carrierId() : "null",
                tick.ballInTransit() ? "YES" : "no",
                tick.activeEventType() != null ? tick.activeEventType() : "-");

            printPlayerPositions(tick.players(), tick.tick(), tick.minute(), "");
        }

        // ─────────────────────────────────────────────────
        // 4. GOAL ANALYSIS — if any goals in first 5 min
        // ─────────────────────────────────────────────────
        System.out.println();
        System.out.println("=" .repeat(120));
        System.out.println("  GOAL/EVENT CONTEXT ANALYSIS");
        System.out.println("=" .repeat(120));

        var first5Events = events.stream()
            .filter(e -> !(e instanceof MatchStartEvent || e instanceof MatchEndEvent))
            .filter(e -> e.minute() <= DIAGNOSTIC_MINUTES)
            .toList();

        for (var event : first5Events) {
            // Find the tick before and after this event
            TickSnapshot beforeTick = null, afterTick = null;
            for (int i = 0; i < ticks.size(); i++) {
                if (ticks.get(i).tick() <= event.tick()) {
                    beforeTick = ticks.get(i);
                }
                if (ticks.get(i).tick() > event.tick()) {
                    afterTick = ticks.get(i);
                    break;
                }
            }

            System.out.println();
            System.out.println("  ── " + formatEvent(event) + " ──");
            if (beforeTick != null) {
                System.out.println("  Before tick " + beforeTick.tick() + ":");
                printPlayerPositions(beforeTick.players(), beforeTick.tick(), beforeTick.minute(), "BEFORE");
            }
            // Find the tick AFTER the event
            int afterIdx = -1;
            if (beforeTick != null) {
                for (int i = 0; i < ticks.size(); i++) {
                    if (ticks.get(i).tick() == beforeTick.tick()) {
                        afterIdx = i;
                        break;
                    }
                }
            }
            if (afterTick != null && afterIdx >= 0 && afterIdx + 1 < ticks.size()) {
                System.out.println("  After tick " + afterTick.tick() + ":");
                printPlayerPositions(afterTick.players(), afterTick.tick(), afterTick.minute(), "AFTER");
            }
        }

        // ─────────────────────────────────────────────────
        // 5. MOVEMENT SANITY CHECK
        // ─────────────────────────────────────────────────
        System.out.println();
        System.out.println("=" .repeat(120));
        System.out.println("  MOVEMENT SANITY CHECK");
        System.out.println("=" .repeat(120));

        // Track per-player total distance over first 5 minutes
        Map<Long, Double> totalDist = new HashMap<>();
        Map<Long, Double> lastX = new HashMap<>();
        Map<Long, Double> lastY = new HashMap<>();
        Map<Long, Double> minX = new HashMap<>();
        Map<Long, Double> maxX = new HashMap<>();
        Map<Long, Double> minY = new HashMap<>();
        Map<Long, Double> maxY = new HashMap<>();
        Map<Long, String> playerInfo = new HashMap<>();
        Map<Long, Boolean> hasMovedToday = new HashMap<>();

        for (var tick : ticks) {
            if (tick.minute() > DIAGNOSTIC_MINUTES) break;
            for (var ps : tick.players()) {
                long pid = ps.playerId();
                playerInfo.put(pid, ps.name() + " (" + ps.teamSide() + ", " + ps.position() + ")");
                minX.merge(pid, ps.x(), Math::min);
                maxX.merge(pid, ps.x(), Math::max);
                minY.merge(pid, ps.y(), Math::min);
                maxY.merge(pid, ps.y(), Math::max);

                if (lastX.containsKey(pid) && lastY.containsKey(pid)) {
                    double dx = ps.x() - lastX.get(pid);
                    double dy = ps.y() - lastY.get(pid);
                    double dist = Math.sqrt(dx*dx + dy*dy);
                    totalDist.merge(pid, dist, Double::sum);
                    if (dist > 0.01) hasMovedToday.put(pid, true);
                }
                lastX.put(pid, ps.x());
                lastY.put(pid, ps.y());
            }
        }

        // Count ticks where player didn't move
        Map<Long, Integer> stationaryTicks = new HashMap<>();
        Map<Long, Double> lastMX = new HashMap<>();
        Map<Long, Double> lastMY = new HashMap<>();

        for (var tick : ticks) {
            if (tick.minute() > DIAGNOSTIC_MINUTES) break;
            for (var ps : tick.players()) {
                long pid = ps.playerId();
                if (lastMX.containsKey(pid) && lastMY.containsKey(pid)) {
                    double dx = ps.x() - lastMX.get(pid);
                    double dy = ps.y() - lastMY.get(pid);
                    if (Math.sqrt(dx*dx + dy*dy) < 0.05) {
                        stationaryTicks.merge(pid, 1, Integer::sum);
                    }
                }
                lastMX.put(pid, ps.x());
                lastMY.put(pid, ps.y());
            }
        }

        int totalTicks = (int) ticks.stream().filter(t -> t.minute() <= DIAGNOSTIC_MINUTES).count();

        System.out.printf("%n  Total ticks in first %d min: %d%n", DIAGNOSTIC_MINUTES, totalTicks);
        System.out.println();
        System.out.printf("  %-5s %-30s %10s %10s %10s %10s %12s %12s%n",
            "ID", "CPlayer", "Dist", "X range", "Y range", "Still%", "HasMoved", "Avg/tick");
        System.out.println("  " + "-".repeat(105));

        List<Long> sortedPlayers = playerInfo.keySet().stream()
            .sorted(Comparator.comparing(pid -> playerInfo.get(pid)))
            .toList();

        for (var pid : sortedPlayers) {
            double dist = totalDist.getOrDefault(pid, 0.0);
            double xr = maxX.getOrDefault(pid, 0.0) - minX.getOrDefault(pid, 0.0);
            double yr = maxY.getOrDefault(pid, 0.0) - minY.getOrDefault(pid, 0.0);
            int still = stationaryTicks.getOrDefault(pid, 0);
            double stillPct = totalTicks > 0 ? 100.0 * still / totalTicks : 0;
            String moved = hasMovedToday.getOrDefault(pid, false) ? "YES" : "NO";
            double avgPerTick = totalTicks > 0 ? dist / totalTicks : 0;
            System.out.printf("  %-5d %-30s %10.1f %10.1f %10.1f %10.1f%% %12s %12.3f%n",
                pid, playerInfo.get(pid), dist, xr, yr, stillPct, moved, avgPerTick);
        }

        // ─────────────────────────────────────────────────
        // 6. SUMMARY / CONCLUSION
        // ─────────────────────────────────────────────────
        System.out.println();
        System.out.println("=" .repeat(120));
        System.out.println("  ANALYSIS SUMMARY");
        System.out.println("=" .repeat(120));

        // Check if players are clumped
        TickSnapshot tAny = null;
        for (var t : ticks) { if (t.minute() <= DIAGNOSTIC_MINUTES) { tAny = t; break; } }
        if (tAny != null) {
            var homePlayers = tAny.players().stream().filter(p -> "HOME".equals(p.teamSide())).toList();
            var awayPlayers = tAny.players().stream().filter(p -> "AWAY".equals(p.teamSide())).toList();
            double homeAvgX2 = homePlayers.stream().mapToDouble(PlayerSnapshot::x).average().orElse(50);
            double awayAvgX2 = awayPlayers.stream().mapToDouble(PlayerSnapshot::x).average().orElse(50);
            double homeAvgY2 = homePlayers.stream().mapToDouble(PlayerSnapshot::y).average().orElse(50);
            double awayAvgY2 = awayPlayers.stream().mapToDouble(PlayerSnapshot::y).average().orElse(50);

            System.out.printf("%n  Average positions across whole period:%n");
            System.out.printf("    HOME: x=%.1f y=%.1f  (defend left, attack right%n", homeAvgX2, homeAvgY2);
            System.out.printf("    AWAY: x=%.1f y=%.1f  (defend right, attack left)%n", awayAvgX2, awayAvgY2);
            System.out.printf("    Separation: %.1f units (field length = %.1f)%n",
                Math.abs(homeAvgX2 - awayAvgX2), FIELD_LENGTH);

            // Check for clustering
            double homeSpreadX2 = 0, awaySpreadX2 = 0;
            for (var p : homePlayers) homeSpreadX2 += Math.pow(p.x() - homeAvgX2, 2);
            for (var p : awayPlayers) awaySpreadX2 += Math.pow(p.x() - awayAvgX2, 2);
            homeSpreadX2 = Math.sqrt(homeSpreadX2 / homePlayers.size());
            awaySpreadX2 = Math.sqrt(awaySpreadX2 / awayPlayers.size());

            System.out.printf("    HOME X spread: %.1f, AWAY X spread: %.1f (higher = more spread out)%n",
                homeSpreadX2, awaySpreadX2);
            if (homeSpreadX2 < 8.0) {
                System.out.println("    ⚠️  HOME players are very tightly clustered!");
            }
            if (awaySpreadX2 < 8.0) {
                System.out.println("    ⚠️  AWAY players are very tightly clustered!");
            }

            // Check GK position
            var homeGk = homePlayers.stream().filter(p -> p.position() == Position.GK).findFirst();
            var awayGk = awayPlayers.stream().filter(p -> p.position() == Position.GK).findFirst();
            if (homeGk.isPresent()) {
                System.out.printf("    HOME GK at x=%.1f (should be ~6.0)%n", homeGk.get().x());
                if (homeGk.get().x() > 40) System.out.println("    ⚠️  HOME GK is way out of position!");
            }
            if (awayGk.isPresent()) {
                System.out.printf("    AWAY GK at x=%.1f (should be ~94.0)%n", awayGk.get().x());
                if (awayGk.get().x() < 60) System.out.println("    ⚠️  AWAY GK is way out of position!");
            }
        }

        // Check if players actually move meaningfully
        double movers = sortedPlayers.stream()
            .mapToDouble(pid -> totalDist.getOrDefault(pid, 0.0))
            .filter(d -> d > 50.0)
            .count();
        double totalPlayers = sortedPlayers.size();
        System.out.printf("%n  Players covering >50 units in 5 min: %.0f/%.0f (%.0f%%)%n",
            movers, totalPlayers, 100.0 * movers / totalPlayers);
        if (movers < totalPlayers * 0.5) {
            System.out.println("  ⚠️  Less than half of players make meaningful movement!");
        }

        double avgDistAll = sortedPlayers.stream()
            .mapToDouble(pid -> totalDist.getOrDefault(pid, 0.0))
            .average().orElse(0);
        System.out.printf("  Average total movement per player: %.1f units%n", avgDistAll);
        if (avgDistAll < 200.0) {
            System.out.println("  ⚠️  Average movement is very low! Players may be standing still.");
        }

        // Check ball movement
        double ballTotalDist = 0;
        Double ballPrevX = null, ballPrevY = null;
        for (var tick : ticks) {
            if (tick.minute() > DIAGNOSTIC_MINUTES) break;
            if (ballPrevX != null && ballPrevY != null) {
                ballTotalDist += Math.sqrt(Math.pow(tick.ball().x() - ballPrevX, 2) + Math.pow(tick.ball().y() - ballPrevY, 2));
            }
            ballPrevX = tick.ball().x();
            ballPrevY = tick.ball().y();
        }
        System.out.printf("  Ball total movement: %.1f units (field diagonal ~127)%n", ballTotalDist);

        System.out.println();
        System.out.println("  ✓ Diagnostic complete.");
    }

    @Test
    void diagnosticFirst5MinutesPerSecond() {
        MatchStore store = new MatchStore();
        MatchOrchestrator orchestrator = new MatchOrchestrator(store);

        long matchId = orchestrator.startMatch("Crvena Zvezda", "Partizan");
        Match match = store.getMatch(matchId);
        MatchResult result = orchestrator.simulate(matchId);

        List<TickSnapshot> ticks = result.tickHistory();
        List<MatchEvent> events = result.events();

        System.out.println("=" .repeat(120));
        System.out.println("  STARTING XI + ATTRIBUTES");
        System.out.println("=" .repeat(120));
        printRosterWithAttributes(match);

        Map<Long, String> homeSlots = buildSlotMap(match.homeTeam());
        Map<Long, String> awaySlots = buildSlotMap(match.awayTeam());

        Map<Long, Integer> towardCounts = new HashMap<>();
        Map<Long, Integer> sampleCounts = new HashMap<>();
        Map<Long, Double> avgDistanceToTarget = new HashMap<>();

        System.out.println();
        System.out.println("=" .repeat(120));
        System.out.println("  PER-SECOND TRACE (0-300s, 1 engine tick = 1 second)");
        System.out.println("=" .repeat(120));

        TickSnapshot prevSecond = null;
        for (int second = 0; second <= DIAGNOSTIC_SECONDS; second++) {
            TickSnapshot current = sampleAtSecond(ticks, second);
            if (current == null) continue;

            System.out.printf("%n  t=%03ds  ball=(%5.1f, %5.1f, %4.1f)  carrier=%s  event=%s%n",
                second, current.ball().x(), current.ball().y(), current.ball().z(),
                current.carrierId() != null ? current.carrierId() : "null",
                current.activeEventType() != null ? current.activeEventType() : "-");

            printSecondTeamLine(match, current, prevSecond, "HOME", homeSlots, towardCounts, sampleCounts, avgDistanceToTarget);
            printSecondTeamLine(match, current, prevSecond, "AWAY", awaySlots, towardCounts, sampleCounts, avgDistanceToTarget);

            prevSecond = current;
        }

        System.out.println();
        System.out.println("=" .repeat(120));
        System.out.println("  PER-SECOND SUMMARY");
        System.out.println("=" .repeat(120));
        for (String side : List.of("HOME", "AWAY")) {
            var ids = sampleCounts.keySet().stream()
                .filter(pid -> side.equals(teamSideOf(ticks, pid)))
                .sorted()
                .toList();
            if (ids.isEmpty()) continue;

            long totalSamples = ids.stream().mapToLong(pid -> sampleCounts.getOrDefault(pid, 0)).sum();
            long towardSamples = ids.stream().mapToLong(pid -> towardCounts.getOrDefault(pid, 0)).sum();
            double towardPct = totalSamples > 0 ? 100.0 * towardSamples / totalSamples : 0.0;
            double meanDist = ids.stream().mapToDouble(pid -> avgDistanceToTarget.getOrDefault(pid, 0.0) / Math.max(1, sampleCounts.getOrDefault(pid, 0))).average().orElse(0.0);
            System.out.printf("  %-4s moving toward target: %d/%d (%.1f%%), avg dist to target: %.2f%n",
                side, towardSamples, totalSamples, towardPct, meanDist);
        }

        System.out.println();
        System.out.println("  Match stats");
        System.out.printf("  Goals=%d Shots=%d Corners=%d Throw-ins=%d Goal-kicks=%d Offsides=%d%n",
            events.stream().filter(e -> e instanceof GoalEvent).count(),
            events.stream().filter(e -> e instanceof ShotEvent).count(),
            events.stream().filter(e -> e instanceof SetPieceEvent sp && sp.setPieceType() == SetPieceEvent.SetPieceType.CORNER).count(),
            events.stream().filter(e -> e instanceof SetPieceEvent sp && sp.setPieceType() == SetPieceEvent.SetPieceType.THROW_IN).count(),
            events.stream().filter(e -> e instanceof SetPieceEvent sp && sp.setPieceType() == SetPieceEvent.SetPieceType.GOAL_KICK).count(),
            events.stream().filter(e -> e instanceof OffsideEvent).count());

        System.out.println();
        System.out.println("  Event log (first 5 minutes)");
        System.out.println("  " + "-".repeat(100));
        events.stream()
            .filter(e -> e.minute() <= DIAGNOSTIC_MINUTES)
            .filter(e -> !(e instanceof MatchStartEvent || e instanceof MatchEndEvent))
            .forEach(e -> System.out.printf("  %3d' tick=%-4d %s%n", e.minute(), e.tick(), formatEvent(e)));

        System.out.println();
        System.out.println("  ✓ Per-second diagnostic complete.");
    }

    @Test
    void diagnosticFirst3MinutesEventLog() {
        MatchStore store = new MatchStore();
        MatchOrchestrator orchestrator = new MatchOrchestrator(store);

        long matchId = orchestrator.startMatch("Crvena Zvezda", "Partizan");
        MatchResult result = orchestrator.simulate(matchId);

        List<MatchEvent> events = result.events().stream()
            .filter(e -> !(e instanceof MatchStartEvent || e instanceof MatchEndEvent))
            .filter(e -> e.minute() <= 3)
            .toList();

        System.out.println("=" .repeat(120));
        System.out.println("  FIRST 3 MINUTES EVENT LOG");
        System.out.println("=" .repeat(120));
        System.out.printf("  Events in first 3 minutes: %d%n", events.size());
        for (MatchEvent event : events) {
            System.out.printf("  %3d' tick=%-4d %s%n", event.minute(), event.tick(), formatEvent(event));
        }
        System.out.println();
        System.out.println("  ✓ First-3-minute event log complete.");
    }

    @Test
    void diagnosticFirst45Minutes() {
        MatchStore store = new MatchStore();
        MatchOrchestrator orchestrator = new MatchOrchestrator(store);

        long matchId = orchestrator.startMatch("Crvena Zvezda", "Partizan");
        MatchResult result = orchestrator.simulate(matchId);

        List<TickSnapshot> ticks = result.tickHistory();
        List<MatchEvent> events = result.events();
        int cutoffMinute = 45;

        List<TickSnapshot> windowTicks = ticks.stream()
            .filter(t -> t.minute() <= cutoffMinute)
            .toList();
        List<MatchEvent> windowEvents = events.stream()
            .filter(e -> !(e instanceof MatchStartEvent || e instanceof MatchEndEvent))
            .filter(e -> e.minute() <= cutoffMinute)
            .toList();

        Map<Long, String> homeSlots = new LinkedHashMap<>();
        Map<Long, String> awaySlots = new LinkedHashMap<>();
        if (!windowTicks.isEmpty()) {
            var first = windowTicks.get(0);
            List<String> slotOrder = List.of("GK", "DL", "DCL", "DCR", "DR", "CML", "CM", "CMR", "WL", "ST", "WR");
            var homeStart = first.players().stream().filter(p -> "HOME".equals(p.teamSide())).toList();
            var awayStart = first.players().stream().filter(p -> "AWAY".equals(p.teamSide())).toList();
            for (int i = 0; i < Math.min(slotOrder.size(), homeStart.size()); i++) {
                homeSlots.put(homeStart.get(i).playerId(), slotOrder.get(i));
            }
            for (int i = 0; i < Math.min(slotOrder.size(), awayStart.size()); i++) {
                awaySlots.put(awayStart.get(i).playerId(), slotOrder.get(i));
            }
        }

        System.out.println("=" .repeat(120));
        System.out.println("  45-MINUTE CELL-PRIMARY POSITION REPORT");
        System.out.println("=" .repeat(120));
        System.out.printf("  Ticks analyzed: %d%n", windowTicks.size());
        System.out.printf("  Events analyzed: %d%n", windowEvents.size());

        long goals = windowEvents.stream().filter(e -> e instanceof GoalEvent).count();
        long shots = windowEvents.stream().filter(e -> e instanceof ShotEvent).count();
        long corners = windowEvents.stream().filter(e -> e instanceof SetPieceEvent sp && sp.setPieceType() == SetPieceEvent.SetPieceType.CORNER).count();
        long throwIns = windowEvents.stream().filter(e -> e instanceof SetPieceEvent sp && sp.setPieceType() == SetPieceEvent.SetPieceType.THROW_IN).count();
        long goalKicks = windowEvents.stream().filter(e -> e instanceof SetPieceEvent sp && sp.setPieceType() == SetPieceEvent.SetPieceType.GOAL_KICK).count();
        long offsides = windowEvents.stream().filter(e -> e instanceof OffsideEvent).count();
        System.out.printf("  Score events: goals=%d shots=%d corners=%d throw-ins=%d goal-kicks=%d offsides=%d%n",
            goals, shots, corners, throwIns, goalKicks, offsides);

        System.out.println();
        System.out.println("  5-MIN WINDOW CHECKS");
        System.out.println("  " + "-".repeat(100));
        System.out.printf("  %-11s %-14s %-14s %-14s %-14s%n", "Window", "Home line", "Away line", "Ball X", "Ball Y");
        for (int minute = 5; minute <= cutoffMinute; minute += 5) {
            final int minuteMark = minute;
            var sample = windowTicks.stream().filter(t -> t.minute() <= minuteMark).reduce((a, b) -> b).orElse(null);
            if (sample == null) continue;

            double homeDef = avgLineX(sample, "HOME", Position.DEF);
            double homeMid = avgLineX(sample, "HOME", Position.MID);
            double homeAtt = avgLineX(sample, "HOME", Position.ATT);
            double awayDef = avgLineX(sample, "AWAY", Position.DEF);
            double awayMid = avgLineX(sample, "AWAY", Position.MID);
            double awayAtt = avgLineX(sample, "AWAY", Position.ATT);
            String homeLine = String.format("%.1f < %.1f < %.1f", homeDef, homeMid, homeAtt);
            String awayLine = String.format("%.1f > %.1f > %.1f", awayDef, awayMid, awayAtt);
            System.out.printf("  %4d-%-5d %-14s %-14s %-14.1f %-14.1f%n",
                minute - 4, minute, homeLine, awayLine, sample.ball().x(), sample.ball().y());
        }

        System.out.println();
        System.out.println("  CELL DEVIATION VS PRIMARY SLOT ANCHOR");
        System.out.println("  " + "-".repeat(100));
        System.out.printf("  %-6s %-8s %-20s %10s %10s %10s %10s%n", "Side", "Line", "Expected cell", "Avg X", "Avg Y", "ΔX", "ΔY");
        for (String side : List.of("HOME", "AWAY")) {
            for (Position line : List.of(Position.GK, Position.DEF, Position.MID, Position.ATT)) {
                var linePlayers = windowTicks.stream()
                    .flatMap(t -> t.players().stream())
                    .filter(p -> side.equals(p.teamSide()) && p.position() == line)
                    .toList();
                if (linePlayers.isEmpty()) continue;
                double avgX = linePlayers.stream().mapToDouble(PlayerSnapshot::x).average().orElse(50);
                double avgY = linePlayers.stream().mapToDouble(PlayerSnapshot::y).average().orElse(50);
                String expected = expectedPrimaryCell(side, line);
                double[] expectedCenter = cellCenter(expected, side);
                double dx = avgX - expectedCenter[0];
                double dy = avgY - expectedCenter[1];
                System.out.printf("  %-6s %-8s %-20s %10.1f %10.1f %10.1f %10.1f%n",
                    side, line, expected, avgX, avgY, dx, dy);
            }
        }

        System.out.println();
        System.out.println("  PLAYER-LEVEL ANCHOR ADHERENCE");
        System.out.println("  " + "-".repeat(100));
        System.out.printf("  %-6s %-24s %-8s %10s %10s %10s%n", "Side", "CPlayer", "Slot", "AvgX", "AvgY", "AvgDist");
        for (String side : List.of("HOME", "AWAY")) {
            Map<Long, String> slotMap = "HOME".equals(side) ? homeSlots : awaySlots;
            var ids = slotMap.keySet().stream().toList();
            for (Long pid : ids) {
                String slot = slotMap.get(pid);
                var lineTicks = windowTicks.stream()
                    .flatMap(t -> t.players().stream())
                    .filter(p -> p.playerId() == pid)
                    .toList();
                if (lineTicks.isEmpty()) continue;
                double avgX = lineTicks.stream().mapToDouble(PlayerSnapshot::x).average().orElse(50);
                double avgY = lineTicks.stream().mapToDouble(PlayerSnapshot::y).average().orElse(50);
                double[] expectedCenter = cellCenter(expectedPrimaryCell(side, slot), side);
                double avgDist = lineTicks.stream()
                    .mapToDouble(p -> Math.hypot(p.x() - expectedCenter[0], p.y() - expectedCenter[1]))
                    .average().orElse(0);
                String name = lineTicks.get(0).name();
                System.out.printf("  %-6s %-24s %-8s %10.1f %10.1f %10.1f%n",
                    side, shortName(name), slot, avgX, avgY, avgDist);
            }
        }

        System.out.println();
        System.out.println("  ✓ 45-minute diagnostic complete.");
    }

    @Test
    void diagnosticFullMatch() {
        MatchStore store = new MatchStore();
        MatchOrchestrator orchestrator = new MatchOrchestrator(store);

        long matchId = orchestrator.startMatch("Crvena Zvezda", "Partizan");
        MatchResult result = orchestrator.simulate(matchId);

        List<TickSnapshot> ticks = result.tickHistory();
        List<MatchEvent> events = result.events();

        Map<Long, String[]> playerRegistry = new HashMap<>();
        if (!ticks.isEmpty()) {
            for (var ps : ticks.get(0).players()) {
                playerRegistry.put(ps.playerId(), new String[]{ps.name(), ps.teamSide(), ps.position().name()});
            }
        }

        // ─────────────────────────────────────────────────
        // 1. FINAL MATCH STATS
        // ─────────────────────────────────────────────────
        System.out.println("=" .repeat(120));
        System.out.println("  FULL MATCH DIAGNOSTIC (90 min)");
        System.out.println("=" .repeat(120));

        int htGoals = (int) events.stream().filter(e -> e instanceof GoalEvent).filter(e -> e.minute() <= 45).count();
        int ftGoals = (int) events.stream().filter(e -> e instanceof GoalEvent).count();
        int htHome = (int) events.stream().filter(e -> e instanceof GoalEvent && "HOME".equals(((GoalEvent)e).teamSide()) && e.minute() <= 45).count();
        int htAway = htGoals - htHome;
        int ftHome = (int) events.stream().filter(e -> e instanceof GoalEvent && "HOME".equals(((GoalEvent)e).teamSide())).count();
        int ftAway = ftGoals - ftHome;
        long homeShots = events.stream().filter(e -> e instanceof ShotEvent s && "HOME".equals(s.teamSide()) && s.onTarget()).count();
        long awayShots = events.stream().filter(e -> e instanceof ShotEvent s && "AWAY".equals(s.teamSide()) && s.onTarget()).count();
        long homeFouls = events.stream().filter(e -> e instanceof FoulEvent f && "HOME".equals(f.teamSide())).count();
        long awayFouls = events.stream().filter(e -> e instanceof FoulEvent f && "AWAY".equals(f.teamSide())).count();
        long homeYellow = events.stream().filter(e -> e instanceof CardEvent c && "HOME".equals(c.teamSide()) && c.cardType() == CardEvent.CardType.YELLOW).count();
        long awayYellow = events.stream().filter(e -> e instanceof CardEvent c && "AWAY".equals(c.teamSide()) && c.cardType() == CardEvent.CardType.YELLOW).count();
        long homeRed = events.stream().filter(e -> e instanceof CardEvent c && "HOME".equals(c.teamSide()) && c.cardType() == CardEvent.CardType.RED).count();
        long awayRed = events.stream().filter(e -> e instanceof CardEvent c && "AWAY".equals(c.teamSide()) && c.cardType() == CardEvent.CardType.RED).count();

        // Possession
        long homePossTicks = ticks.stream().filter(t -> {
            if (t.carrierId() == null) return false;
            var ps = t.players().stream().filter(p -> p.playerId() == t.carrierId()).findFirst();
            return ps.isPresent() && "HOME".equals(ps.get().teamSide());
        }).count();
        long totalPossTicks = ticks.stream().filter(t -> t.carrierId() != null).count();
        double homePossPct = totalPossTicks > 0 ? 100.0 * homePossTicks / totalPossTicks : 50;

        System.out.println();
        System.out.println("  FINAL SCORE");
        System.out.println("  ───────────");
        System.out.printf("  HT:  HOME %d - %d AWAY%n", htHome, htAway);
        System.out.printf("  FT:  HOME %d - %d AWAY%n", ftHome, ftAway);
        System.out.println();
        System.out.println("  MATCH STATS");
        System.out.println("  ───────────");
        System.out.printf("  %-30s %10s %10s%n", "", "HOME", "AWAY");
        System.out.printf("  %-30s %10d %10d%n", "Shots on target", homeShots, awayShots);
        System.out.printf("  %-30s %10d %10d%n", "Fouls", homeFouls, awayFouls);
        System.out.printf("  %-30s %10d %10d%n", "Yellow cards", homeYellow, awayYellow);
        System.out.printf("  %-30s %10d %10d%n", "Red cards", homeRed, awayRed);
        System.out.printf("  %-30s %10.1f%% %10.1f%%%n", "Possession", homePossPct, 100 - homePossPct);
        System.out.println();

        // Events timeline
        var matchEvents = events.stream()
            .filter(e -> !(e instanceof MatchStartEvent || e instanceof MatchEndEvent))
            .toList();
        System.out.println("  EVENTS (" + matchEvents.size() + " total)");
        System.out.println("  ───────");
        for (var e : matchEvents) {
            System.out.println("    " + formatEvent(e));
        }

        // ─────────────────────────────────────────────────
        // 2. PER-10-MINUTE LINE ANALYSIS
        // ─────────────────────────────────────────────────
        System.out.println();
        System.out.println("=" .repeat(120));
        System.out.println("  POSITION ANALYSIS BY LINE (every 10 min)");
        System.out.println("=" .repeat(120));

        Map<Integer, double[]> ballPosByMinute = new LinkedHashMap<>();
        for (int minute = 0; minute <= 90; minute += 10) {
            int startMin = minute;
            int endMin = Math.min(minute + 10, 90);
            var windowTicks = ticks.stream()
                .filter(t -> t.minute() > startMin && t.minute() <= endMin)
                .toList();
            if (windowTicks.isEmpty()) continue;

            // Ball position in this window
            double ballAvgX = windowTicks.stream().mapToDouble(t -> t.ball().x()).average().orElse(50);

            // Per-line X position
            record LineStat(String side, String line, double avgX, double avgY, double spreadX, double spreadY, int count) {}
            List<LineStat> lineStats = new ArrayList<>();
            for (String side : List.of("HOME", "AWAY")) {
                for (String lineName : List.of("GK", "DEF", "MID", "ATT")) {
                    var positions = windowTicks.stream()
                        .flatMap(t -> t.players().stream())
                        .filter(p -> p.teamSide().equals(side))
                        .filter(p -> p.position().name().equals(lineName))
                        .toList();
                    if (positions.isEmpty()) continue;
                    double avgX = positions.stream().mapToDouble(PlayerSnapshot::x).average().orElse(50);
                    double avgY = positions.stream().mapToDouble(PlayerSnapshot::y).average().orElse(50);
                    double sx = Math.sqrt(positions.stream().mapToDouble(p -> Math.pow(p.x() - avgX, 2)).average().orElse(0));
                    double sy = Math.sqrt(positions.stream().mapToDouble(p -> Math.pow(p.y() - avgY, 2)).average().orElse(0));
                    lineStats.add(new LineStat(side, lineName, avgX, avgY, sx, sy, positions.size()));
                }
            }

            // Calculate line separation
            var homeDef = lineStats.stream().filter(s -> "HOME".equals(s.side) && "DEF".equals(s.line)).findFirst();
            var homeMid = lineStats.stream().filter(s -> "HOME".equals(s.side) && "MID".equals(s.line)).findFirst();
            var homeAtt = lineStats.stream().filter(s -> "HOME".equals(s.side) && "ATT".equals(s.line)).findFirst();
            var awayDef = lineStats.stream().filter(s -> "AWAY".equals(s.side) && "DEF".equals(s.line)).findFirst();
            var awayMid = lineStats.stream().filter(s -> "AWAY".equals(s.side) && "MID".equals(s.line)).findFirst();
            var awayAtt = lineStats.stream().filter(s -> "AWAY".equals(s.side) && "ATT".equals(s.line)).findFirst();

            System.out.println();
            System.out.printf("  MINUTES %2d-%2d  (ball avg X=%.1f)%n", startMin, endMin, ballAvgX);
            System.out.println("  " + "-".repeat(100));
            System.out.printf("  %-6s %-6s %10s %10s %10s %10s %10s%n", "Side", "Line", "Avg X", "Avg Y", "X σ", "Y σ", "Count");
            System.out.println("  " + "-".repeat(100));
            for (var ls : lineStats) {
                System.out.printf("  %-6s %-6s %10.1f %10.1f %10.1f %10.1f %10d%n",
                    ls.side, ls.line, ls.avgX, ls.avgY, ls.spreadX, ls.spreadY, ls.count);
            }

            // Line separation analysis
            System.out.println("  " + "-".repeat(100));
            if (homeDef.isPresent() && homeMid.isPresent()) {
                System.out.printf("  HOME DEF→MID gap: %.1f units%n",
                    homeMid.get().avgX - homeDef.get().avgX);
            }
            if (homeMid.isPresent() && homeAtt.isPresent()) {
                System.out.printf("  HOME MID→ATT gap: %.1f units%n",
                    homeAtt.get().avgX - homeMid.get().avgX);
            }
            if (awayDef.isPresent() && awayMid.isPresent()) {
                System.out.printf("  AWAY DEF→MID gap: %.1f units%n",
                    awayDef.get().avgX - awayMid.get().avgX);
            }
            if (awayMid.isPresent() && awayAtt.isPresent()) {
                System.out.printf("  AWAY MID→ATT gap: %.1f units%n",
                    awayAtt.get().avgX - awayMid.get().avgX);
            }

            // Overall separation between teams
            double homeAvgX = lineStats.stream().filter(s -> "HOME".equals(s.side) && !"GK".equals(s.line))
                .mapToDouble(LineStat::avgX).average().orElse(50);
            double awayAvgX = lineStats.stream().filter(s -> "AWAY".equals(s.side) && !"GK".equals(s.line))
                .mapToDouble(LineStat::avgX).average().orElse(50);
            System.out.printf("  CTeam separation (outfield): %.1f units%n", Math.abs(homeAvgX - awayAvgX));
        }

        // ─────────────────────────────────────────────────
        // 3. BALL MOVEMENT ANALYSIS
        // ─────────────────────────────────────────────────
        System.out.println();
        System.out.println("=" .repeat(120));
        System.out.println("  BALL MOVEMENT ANALYSIS");
        System.out.println("=" .repeat(120));

        double ballTotalDist = 0;
        Double bxPrev = null, byPrev = null;
        double ballMinX = 99, ballMaxX = 0, ballMinY = 99, ballMaxY = 0;
        for (var tick : ticks) {
            if (bxPrev != null && byPrev != null) {
                ballTotalDist += Math.sqrt(Math.pow(tick.ball().x() - bxPrev, 2) + Math.pow(tick.ball().y() - byPrev, 2));
            }
            bxPrev = tick.ball().x();
            byPrev = tick.ball().y();
            ballMinX = Math.min(ballMinX, tick.ball().x());
            ballMaxX = Math.max(ballMaxX, tick.ball().x());
            ballMinY = Math.min(ballMinY, tick.ball().y());
            ballMaxY = Math.max(ballMaxY, tick.ball().y());
        }
        System.out.printf("  Ball total movement: %.1f units%n", ballTotalDist);
        System.out.printf("  Ball X range: %.1f - %.1f (spread: %.1f)%n", ballMinX, ballMaxX, ballMaxX - ballMinX);
        System.out.printf("  Ball Y range: %.1f - %.1f (spread: %.1f)%n", ballMinY, ballMaxY, ballMaxY - ballMinY);
        System.out.printf("  Total ticks: %d%n", ticks.size());

        // ─────────────────────────────────────────────────
        // 4. LINE MOVEMENT SENSIBILITY ANALYSIS
        // ─────────────────────────────────────────────────
        System.out.println();
        System.out.println("=" .repeat(120));
        System.out.println("  LINE MOVEMENT SENSIBILITY ANALYSIS");
        System.out.println("=" .repeat(120));

        // Sample every 60 ticks to check line separation over time
        System.out.println();
        System.out.println("  Sample positions every ~1 minute (60 ticks):");
        for (int i = 0; i < ticks.size(); i += 60) {
            var tick = ticks.get(i);
            if (tick.minute() > 90) break;

            var homePlayers = tick.players().stream().filter(p -> "HOME".equals(p.teamSide())).toList();
            var awayPlayers = tick.players().stream().filter(p -> "AWAY".equals(p.teamSide())).toList();

            double homeDefX = homePlayers.stream().filter(p -> p.position() == Position.DEF).mapToDouble(PlayerSnapshot::x).average().orElse(0);
            double homeMidX = homePlayers.stream().filter(p -> p.position() == Position.MID).mapToDouble(PlayerSnapshot::x).average().orElse(0);
            double homeAttX = homePlayers.stream().filter(p -> p.position() == Position.ATT).mapToDouble(PlayerSnapshot::x).average().orElse(0);
            double homeGkX = homePlayers.stream().filter(p -> p.position() == Position.GK).mapToDouble(PlayerSnapshot::x).average().orElse(0);

            double awayDefX = awayPlayers.stream().filter(p -> p.position() == Position.DEF).mapToDouble(PlayerSnapshot::x).average().orElse(0);
            double awayMidX = awayPlayers.stream().filter(p -> p.position() == Position.MID).mapToDouble(PlayerSnapshot::x).average().orElse(0);
            double awayAttX = awayPlayers.stream().filter(p -> p.position() == Position.ATT).mapToDouble(PlayerSnapshot::x).average().orElse(0);
            double awayGkX = awayPlayers.stream().filter(p -> p.position() == Position.GK).mapToDouble(PlayerSnapshot::x).average().orElse(0);

            boolean homeLinesCorrectOrder = homeDefX < homeMidX && homeMidX < homeAttX;
            boolean awayLinesCorrectOrder = awayDefX > awayMidX && awayMidX > awayAttX;
            boolean homeGkCorrect = homeGkX < homeDefX && homeGkX < 30;
            boolean awayGkCorrect = awayGkX > awayDefX && awayGkX > 70;

            System.out.printf("  %3d' ball=%.0f  HOME: GK=%.0f DEF=%.0f MID=%.0f ATT=%.0f %s | AWAY: GK=%.0f DEF=%.0f MID=%.0f ATT=%.0f %s%n",
                tick.minute(), tick.ball().x(),
                homeGkX, homeDefX, homeMidX, homeAttX,
                homeLinesCorrectOrder ? "✓" : "✗",
                awayGkX, awayDefX, awayMidX, awayAttX,
                awayLinesCorrectOrder ? "✓" : "✗");
        }

        // Overall sensibility
        int sensibleSamples = 0;
        int totalSamples = 0;
        for (int i = 0; i < ticks.size(); i += 60) {
            var tick = ticks.get(i);
            if (tick.minute() > 90) break;

            var homePlayers = tick.players().stream().filter(p -> "HOME".equals(p.teamSide())).toList();
            var awayPlayers = tick.players().stream().filter(p -> "AWAY".equals(p.teamSide())).toList();

            double homeDefX = homePlayers.stream().filter(p -> p.position() == Position.DEF).mapToDouble(PlayerSnapshot::x).average().orElse(0);
            double homeMidX = homePlayers.stream().filter(p -> p.position() == Position.MID).mapToDouble(PlayerSnapshot::x).average().orElse(0);
            double homeAttX = homePlayers.stream().filter(p -> p.position() == Position.ATT).mapToDouble(PlayerSnapshot::x).average().orElse(0);
            double awayDefX = awayPlayers.stream().filter(p -> p.position() == Position.DEF).mapToDouble(PlayerSnapshot::x).average().orElse(0);
            double awayMidX = awayPlayers.stream().filter(p -> p.position() == Position.MID).mapToDouble(PlayerSnapshot::x).average().orElse(0);
            double awayAttX = awayPlayers.stream().filter(p -> p.position() == Position.ATT).mapToDouble(PlayerSnapshot::x).average().orElse(0);

            if (homeDefX < homeMidX && homeMidX < homeAttX && awayDefX > awayMidX && awayMidX > awayAttX) {
                sensibleSamples++;
            }
            totalSamples++;
        }

        System.out.println();
        System.out.println("  SENSIBILITY RESULTS");
        System.out.println("  ──────────────────");
        System.out.printf("  Samples with correct line order (GK<DEF<MID<ATT for HOME, reversed for AWAY): %d/%d (%.0f%%)%n",
            sensibleSamples, totalSamples, 100.0 * sensibleSamples / totalSamples);

        // Check if lines overlap (DEF and MID too close, or MID and ATT too close)
        int defMidOverlap = 0;
        int midAttOverlap = 0;
        for (int i = 0; i < ticks.size(); i += 60) {
            var tick = ticks.get(i);
            if (tick.minute() > 90) break;

            var homePlayers = tick.players().stream().filter(p -> "HOME".equals(p.teamSide())).toList();
            var awayPlayers = tick.players().stream().filter(p -> "AWAY".equals(p.teamSide())).toList();

            double homeDefX = homePlayers.stream().filter(p -> p.position() == Position.DEF).mapToDouble(PlayerSnapshot::x).average().orElse(0);
            double homeMidX = homePlayers.stream().filter(p -> p.position() == Position.MID).mapToDouble(PlayerSnapshot::x).average().orElse(0);
            double homeAttX = homePlayers.stream().filter(p -> p.position() == Position.ATT).mapToDouble(PlayerSnapshot::x).average().orElse(0);
            double awayDefX = awayPlayers.stream().filter(p -> p.position() == Position.DEF).mapToDouble(PlayerSnapshot::x).average().orElse(0);
            double awayMidX = awayPlayers.stream().filter(p -> p.position() == Position.MID).mapToDouble(PlayerSnapshot::x).average().orElse(0);
            double awayAttX = awayPlayers.stream().filter(p -> p.position() == Position.ATT).mapToDouble(PlayerSnapshot::x).average().orElse(0);

            // Overlap if gap < 5 units
            if (Math.abs(homeMidX - homeDefX) < 5 && Math.abs(awayMidX - awayDefX) < 5) defMidOverlap++;
            if (Math.abs(homeAttX - homeMidX) < 5 && Math.abs(awayAttX - awayMidX) < 5) midAttOverlap++;
        }

        System.out.printf("  DEF↔MID overlapping samples: %d/%d (%.0f%%)%n",
            defMidOverlap, totalSamples, 100.0 * defMidOverlap / totalSamples);
        System.out.printf("  MID↔ATT overlapping samples: %d/%d (%.0f%%)%n",
            midAttOverlap, totalSamples, 100.0 * midAttOverlap / totalSamples);

        // Additional GK analysis
        double homeGkAvgFinal = 0;
        double awayGkAvgFinal = 0;
        int gkSamples = 0;
        for (int i = ticks.size() * 2 / 3; i < ticks.size(); i++) {
            var tick = ticks.get(i);
            var homeGk = tick.players().stream().filter(p -> "HOME".equals(p.teamSide()) && p.position() == Position.GK).findFirst();
            var awayGk = tick.players().stream().filter(p -> "AWAY".equals(p.teamSide()) && p.position() == Position.GK).findFirst();
            if (homeGk.isPresent() && awayGk.isPresent()) {
                homeGkAvgFinal += homeGk.get().x();
                awayGkAvgFinal += awayGk.get().x();
                gkSamples++;
            }
        }
        if (gkSamples > 0) {
            System.out.printf("  HOME GK avg X (last 30 min): %.1f (should be ~6-15)%n", homeGkAvgFinal / gkSamples);
            System.out.printf("  AWAY GK avg X (last 30 min): %.1f (should be ~85-94)%n", awayGkAvgFinal / gkSamples);
        }

        System.out.println();
        System.out.println("  ✓ Full match diagnostic complete.");
    }

    private void printPlayerPositions(List<PlayerSnapshot> players, int tick, int minute, String label) {
        var home = players.stream().filter(p -> "HOME".equals(p.teamSide()))
            .sorted(Comparator.comparing(p -> p.position().name()))
            .toList();
        var away = players.stream().filter(p -> "AWAY".equals(p.teamSide()))
            .sorted(Comparator.comparing(p -> p.position().name()))
            .toList();

        System.out.printf("  %-6s %-36s %-36s%n", "", "HOME (defend ←, attack →)", "AWAY (defend →, attack ←)");
        System.out.printf("  %-6s %-36s %-36s%n", "", "─" .repeat(35), "─".repeat(35));
        int max = Math.max(home.size(), away.size());
        for (int i = 0; i < max; i++) {
            String h = i < home.size() ? formatPlayer(home.get(i)) : "";
            String a = i < away.size() ? formatPlayer(away.get(i)) : "";
            System.out.printf("  %-6s %-36s %-36s%n", "", h, a);
        }
        if (!label.isEmpty()) {
            System.out.printf("  ↑ %s (tick=%d min=%d)%n", label, tick, minute);
        }
    }

    private void printRosterWithAttributes(Match match) {
        printTeamRoster(match.homeTeam(), "HOME");
        printTeamRoster(match.awayTeam(), "AWAY");
    }

    private void printTeamRoster(Team team, String side) {
        System.out.printf("%n  %s: %s  formation=%s%n", side, team.name(), team.formation());
        System.out.printf("  %-4s %-22s %-5s %-37s%n", "Slot", "CPlayer", "Pos", "Attributes");
        System.out.println("  " + "-".repeat(78));
        Map<Long, String> slotMap = buildSlotMap(team);
        for (var p : team.startingXI()) {
            String slot = slotMap.getOrDefault(p.id(), expectedPrimaryCell(side, p.position()));
            System.out.printf("  %-4s %-22s %-5s %s%n",
                slot, shortName(p.name()), p.position(), formatSkills(p.skills()));
        }
    }

    private Map<Long, String> buildSlotMap(Team team) {
        List<String> slotOrder = team.slotKeys() != null && team.slotKeys().size() == team.startingXI().size()
            ? team.slotKeys()
            : List.of("GK", "DL", "DCL", "DCR", "DR", "CML", "CM", "CMR", "WL", "ST", "WR");
        Map<Long, String> slots = new LinkedHashMap<>();
        var starters = team.startingXI();
        for (int i = 0; i < Math.min(slotOrder.size(), starters.size()); i++) {
            slots.put(starters.get(i).id(), slotOrder.get(i));
        }
        return slots;
    }

    private void printSecondTeamLine(Match match, TickSnapshot current, TickSnapshot previous, String side,
                                     Map<Long, String> slotMap, Map<Long, Integer> towardCounts,
                                     Map<Long, Integer> sampleCounts, Map<Long, Double> avgDistanceToTarget) {
        var team = "HOME".equals(side) ? match.homeTeam() : match.awayTeam();
        var players = current.players().stream()
            .filter(p -> side.equals(p.teamSide()))
            .sorted(Comparator.comparingInt(p -> slotSortKey(slotMap.getOrDefault(p.playerId(), expectedPrimaryCell(side, p.position())))))
            .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("  ").append(side).append(": ");
        boolean first = true;
        for (var snap : players) {
            Player player = team.startingXI().stream().filter(p -> p.id() == snap.playerId()).findFirst().orElse(null);
            if (player == null) continue;

            String slotKey = slotMap.getOrDefault(snap.playerId(), expectedPrimaryCell(side, snap.position()));
            double[] target = tacticalTargetForSnapshot(match, current, snap, player, slotKey);
            double dist = Math.hypot(snap.x() - target[0], snap.y() - target[1]);
            avgDistanceToTarget.merge(snap.playerId(), dist, Double::sum);
            sampleCounts.merge(snap.playerId(), 1, Integer::sum);

            boolean movingToward = previous != null && isMovingTowardTarget(previous, current, snap.playerId(), target);
            if (movingToward) {
                towardCounts.merge(snap.playerId(), 1, Integer::sum);
            }

            if (!first) sb.append(" | ");
            first = false;
            sb.append(formatSecondPlayer(snap, slotKey, target, movingToward));
        }
        System.out.println(sb);
    }

    private String formatSecondPlayer(PlayerSnapshot snap, String slotKey, double[] target, boolean movingToward) {
        String carrier = snap.hasBall() ? "*" : "";
        return String.format("%s%s@(%.1f,%.1f)->%s d=%.1f %s",
            shortName(snap.name()), carrier, snap.x(), snap.y(), slotKey,
            Math.hypot(snap.x() - target[0], snap.y() - target[1]),
            movingToward ? "toward" : "away");
    }

    private boolean isMovingTowardTarget(TickSnapshot previous, TickSnapshot current, long playerId, double[] target) {
        var prev = previous.players().stream().filter(p -> p.playerId() == playerId).findFirst().orElse(null);
        var now = current.players().stream().filter(p -> p.playerId() == playerId).findFirst().orElse(null);
        if (prev == null || now == null) return false;
        double stepX = now.x() - prev.x();
        double stepY = now.y() - prev.y();
        double toTargetX = target[0] - prev.x();
        double toTargetY = target[1] - prev.y();
        return (stepX * toTargetX + stepY * toTargetY) > 0.01;
    }

    private double[] tacticalTargetForSnapshot(Match match, TickSnapshot current, PlayerSnapshot snap, Player player, String slotKey) {
        boolean inPossession = current.carrierId() != null
            && current.players().stream().anyMatch(p -> p.playerId() == current.carrierId() && snap.teamSide().equals(p.teamSide()));
        int[] zone = ZonePositionCalculator.ballZone(current.ball().x(), current.ball().y());
        Team team = "HOME".equals(snap.teamSide()) ? match.homeTeam() : match.awayTeam();
        return ZonePositionCalculator.tacticalTarget(player, snap.teamSide(), inPossession, zone[0], zone[1], slotKey, team.tacticRules());
    }

    private TickSnapshot sampleAtSecond(List<TickSnapshot> ticks, int second) {
        if (ticks.isEmpty()) return null;
        double tickIndex = second / ENGINE_SECONDS_PER_TICK;
        int leftIndex = Math.max(0, Math.min((int) Math.floor(tickIndex), ticks.size() - 1));
        int rightIndex = Math.max(0, Math.min(leftIndex + 1, ticks.size() - 1));
        double alpha = Math.max(0.0, Math.min(1.0, tickIndex - leftIndex));
        return interpolateTick(ticks.get(leftIndex), ticks.get(rightIndex), alpha);
    }

    private TickSnapshot interpolateTick(TickSnapshot left, TickSnapshot right, double alpha) {
        if (left == null) return right;
        if (right == null || left.tick() == right.tick() || alpha <= 0.0) return left;
        if (alpha >= 1.0) return right;

        Map<Long, PlayerSnapshot> rightById = right.players().stream().collect(Collectors.toMap(PlayerSnapshot::playerId, p -> p, (a, b) -> a));
        List<PlayerSnapshot> interpolatedPlayers = new ArrayList<>();
        for (var lp : left.players()) {
            var rp = rightById.get(lp.playerId());
            if (rp == null) {
                interpolatedPlayers.add(lp);
                continue;
            }
            interpolatedPlayers.add(new PlayerSnapshot(
                lp.playerId(), lp.name(), lp.teamSide(), lp.position(),
                lerp(lp.x(), rp.x(), alpha),
                lerp(lp.y(), rp.y(), alpha),
                alpha < 0.5 ? lp.state() : rp.state(),
                alpha < 0.5 ? lp.hasBall() : rp.hasBall()
            ));
        }

        BallState ball = new BallState(
            lerp(left.ball().x(), right.ball().x(), alpha),
            lerp(left.ball().y(), right.ball().y(), alpha),
            lerp(left.ball().z(), right.ball().z(), alpha)
        );
        Long carrierId = alpha < 0.5 ? left.carrierId() : right.carrierId();
        Long pendingReceiverId = alpha < 0.5 ? left.pendingReceiverId() : right.pendingReceiverId();
        boolean ballInTransit = alpha < 0.5 ? left.ballInTransit() : right.ballInTransit();
        String activeEvent = alpha < 0.5 ? left.activeEventType() : right.activeEventType();
        int minute = alpha < 0.5 ? left.minute() : right.minute();
        int tick = alpha < 0.5 ? left.tick() : right.tick();

        return new TickSnapshot(tick, minute, List.copyOf(interpolatedPlayers), ball, carrierId, pendingReceiverId, ballInTransit, activeEvent);
    }

    private static double lerp(double a, double b, double alpha) {
        return a + (b - a) * alpha;
    }

    private static int slotSortKey(String slot) {
        return switch (slot) {
            case "GK" -> 0;
            case "DL" -> 1;
            case "DCL" -> 2;
            case "DCR" -> 3;
            case "DR" -> 4;
            case "ML" -> 5;
            case "CML" -> 6;
            case "CM" -> 7;
            case "CMR" -> 8;
            case "MR" -> 9;
            case "STL" -> 10;
            case "STR" -> 11;
            default -> 99;
        };
    }

    private static String formatSkills(Skills skills) {
        return String.format("pace=%02d shoot=%02d pass=%02d tech=%02d def=%02d play=%02d gk=%02d sta=%02d",
            skills.pace(), skills.shooting(), skills.passing(), skills.technique(),
            skills.defending(), skills.playmaking(), skills.goalkeeping(), skills.stamina());
    }

    private static String teamSideOf(List<TickSnapshot> ticks, long playerId) {
        return ticks.stream()
            .flatMap(t -> t.players().stream())
            .filter(p -> p.playerId() == playerId)
            .map(PlayerSnapshot::teamSide)
            .findFirst()
            .orElse("UNKNOWN");
    }

    private static double avgLineX(TickSnapshot tick, String side, Position position) {
        return tick.players().stream()
            .filter(p -> side.equals(p.teamSide()) && p.position() == position)
            .mapToDouble(PlayerSnapshot::x)
            .average().orElse(50);
    }

    private static String expectedPrimaryCell(String side, Position position) {
        return mirrorForAway(side, switch (position) {
            case GK -> "CELL_0_2";
            case DEF -> "CELL_1_2";
            case MID -> "CELL_2_2";
            case ATT -> "CELL_4_2";
            case WNG -> "CELL_3_2";
        });
    }

    private static String expectedPrimaryCell(String side, String slotKey) {
        return mirrorForAway(side, switch (slotKey) {
            case "GK" -> "CELL_0_2";
            case "DL" -> "CELL_1_0";
            case "DCL" -> "CELL_1_1";
            case "DCR" -> "CELL_1_3";
            case "DR" -> "CELL_1_4";
            case "ML" -> "CELL_2_0";
            case "CML" -> "CELL_2_1";
            case "CM" -> "CELL_2_2";
            case "CMR" -> "CELL_2_3";
            case "MR" -> "CELL_2_4";
            case "STL" -> "CELL_4_1";
            case "STR" -> "CELL_4_3";
            default -> expectedPrimaryCell(side, Position.MID);
        });
    }

    private static String mirrorForAway(String side, String cellKey) {
        if ("HOME".equals(side)) return cellKey;
        int[] cell = parseCellKey(cellKey);
        return "CELL_" + (4 - cell[0]) + "_" + cell[1];
    }

    private static double[] cellCenter(String cellKey, String side) {
        int[] cell = parseCellKey(cellKey);
        double x = ZonePositionCalculator.zoneCenterX(cell[0], "HOME".equals(side));
        double y = ZonePositionCalculator.zoneCenterY(cell[1]);
        return new double[]{x, y};
    }

    private static String shortName(String name) {
        if (name == null || name.isBlank()) return "N/A";
        String[] parts = name.split(" ");
        return parts.length > 1 ? parts[0].charAt(0) + ". " + String.join(" ", Arrays.copyOfRange(parts, 1, parts.length)) : name;
    }

    private static int[] parseCellKey(String cellKey) {
        if (cellKey == null || !cellKey.startsWith("CELL_")) return new int[]{2, 2};
        String[] parts = cellKey.split("_");
        if (parts.length != 3) return new int[]{2, 2};
        try {
            return new int[]{Math.max(0, Math.min(4, Integer.parseInt(parts[1]))),
                Math.max(0, Math.min(4, Integer.parseInt(parts[2])))};
        } catch (NumberFormatException e) {
            return new int[]{2, 2};
        }
    }

    private String formatPlayer(PlayerSnapshot ps) {
        String ball = ps.hasBall() ? "●" : " ";
        String state = ps.state() != null && !ps.state().isEmpty() ? "[" + ps.state() + "]" : "";
        String pos = switch (ps.position()) {
            case GK -> "GK";
            case DEF -> "DEF";
            case MID -> "MID";
            case WNG -> "WNG";
            case ATT -> "ATT";
        };
        return String.format("%s %-3s %s (%.1f,%.1f) %s", ball, pos, ps.name(), ps.x(), ps.y(), state);
    }

    @Test
    void diagnosticOffsideAndTacticalMovement() {
        MatchStore store = new MatchStore();
        MatchOrchestrator orchestrator = new MatchOrchestrator(store);

        long matchId = orchestrator.startMatch("Crvena Zvezda", "Partizan");
        MatchResult result = orchestrator.simulate(matchId);

        List<TickSnapshot> ticks = result.tickHistory();
        List<MatchEvent> events = result.events();

        Map<Long, String[]> playerInfo = new HashMap<>();
        if (!ticks.isEmpty()) {
            for (var ps : ticks.get(0).players()) {
                playerInfo.put(ps.playerId(), new String[]{ps.name(), ps.teamSide(), ps.position().name()});
            }
        }

        System.out.println("=" .repeat(120));
        System.out.println("  OFFSIDE & TACTICAL MOVEMENT ANALYSIS");
        System.out.println("=" .repeat(120));

        // ─────────────────────────────────────────────────────
        // 1. OFFSIDE EVENTS — full context
        // ─────────────────────────────────────────────────────
        List<OffsideEvent> offsides = events.stream()
            .filter(e -> e instanceof OffsideEvent)
            .map(e -> (OffsideEvent) e)
            .toList();

        System.out.println();
        System.out.printf("  OFFSIDE EVENTS: %d total%n", offsides.size());
        System.out.println("  " + "-".repeat(100));

        if (!offsides.isEmpty()) {
            System.out.printf("  %-5s %-20s %-6s %10s %10s %10s %10s%n",
                "Min", "CPlayer", "CTeam", "Pos X", "Offside Ln", "Beyond", "Def#1 X");
            System.out.println("  " + "-".repeat(100));

            for (var off : offsides) {
                // Find tick around event time
                TickSnapshot nearTick = null;
                for (var t : ticks) {
                    if (t.tick() >= off.tick()) { nearTick = t; break; }
                }
                if (nearTick == null) continue;

                var playerSnap = nearTick.players().stream()
                    .filter(p -> p.playerId() == off.playerId()).findFirst();
                if (playerSnap.isEmpty()) continue;

                double offsideLine = calcOffsideLine(nearTick, off.teamSide());
                double beyond = Math.abs(playerSnap.get().x() - offsideLine);
                String team = off.teamSide();

                // Find the defender closest to the offside line
                var defTeam = "HOME".equals(team) ? "AWAY" : "HOME";
                double closestDefX = nearTick.players().stream()
                    .filter(p -> p.teamSide().equals(defTeam) && p.position() != Position.GK)
                    .mapToDouble(PlayerSnapshot::x)
                    .min().orElse(50);
                if ("HOME".equals(team)) {
                    // For HOME attacking right, last defender is max X
                    closestDefX = nearTick.players().stream()
                        .filter(p -> p.teamSide().equals(defTeam) && p.position() != Position.GK)
                        .mapToDouble(PlayerSnapshot::x)
                        .max().orElse(50);
                }

                System.out.printf("  %-5d %-20s %-6s %10.1f %10.1f %10.1f %10.1f%n",
                    off.minute(), off.playerName(), team,
                    playerSnap.get().x(), offsideLine, beyond, closestDefX);
            }
        } else {
            System.out.println("  (no offside events in this match)");
        }

        // ─────────────────────────────────────────────────────
        // 2. PER-ATTACKER TRACKING — offside line vs position
        // ─────────────────────────────────────────────────────
        System.out.println();
        System.out.println("=" .repeat(120));
        System.out.println("  ATTACKER MOVEMENT TRACKING (every 60 ticks = ~1 min)");
        System.out.println("=" .repeat(120));

        var attackers = ticks.get(0).players().stream()
            .filter(p -> p.position() == Position.ATT)
            .toList();

        for (var att : attackers) {
            String[] info = playerInfo.get(att.playerId());
            System.out.println();
            System.out.printf("  ── %s (%s, %s) ──%n", info[0], info[1], info[2]);
            System.out.printf("  %-5s %7s %12s %10s %8s %8s %8s %10s%n",
                "Min", "X", "Offside Ln", "Off?", "Y", "Ball X", "GoalDist", "In Opp½?");
            System.out.println("  " + "-".repeat(90));

            for (int i = 0; i < ticks.size(); i += 60) {
                var tick = ticks.get(i);
                if (tick.minute() > 90) break;

                var snap = tick.players().stream()
                    .filter(p -> p.playerId() == att.playerId()).findFirst();
                if (snap.isEmpty()) continue;

                String teamSide = snap.get().teamSide();
                double offsideLine = calcOffsideLine(tick, teamSide);
                boolean isOff = isSnapOffside(tick, snap.get(), teamSide);
                double goalDist = calcGoalDistance(snap.get(), teamSide);
                boolean inOppHalf = "HOME".equals(teamSide) ? snap.get().x() > 50.0 : snap.get().x() < 50.0;

                System.out.printf("  %-5d %7.1f %12.1f %10s %8.1f %8.1f %8.1f %10s%n",
                    tick.minute(), snap.get().x(), offsideLine,
                    isOff ? "⬆️OFF" : "OK",
                    snap.get().y(), tick.ball().x(), goalDist,
                    inOppHalf ? "YES" : "NO");
            }
        }

        // ─────────────────────────────────────────────────────
        // 3. DEFENSIVE LINE COORDINATION
        // ─────────────────────────────────────────────────────
        System.out.println();
        System.out.println("=" .repeat(120));
        System.out.println("  DEFENSIVE LINE ANALYSIS (every 30 ticks = ~30 sec)");
        System.out.println("=" .repeat(120));

        for (String side : List.of("HOME", "AWAY")) {
            String opp = "HOME".equals(side) ? "AWAY" : "HOME";
            System.out.println();
            System.out.printf("  ── %s DEFENSIVE LINE ──%n", side);
            System.out.printf("  %-5s %10s %10s %10s %10s %10s %10s %10s%n",
                "Min", "DEF avg X", "Last Def", "DEF σ", "MID avg X", "ATT avg X", "DEF-MID", "Coord?");
            System.out.println("  " + "-".repeat(100));

            int coordCount = 0;
            int totalCoordSamples = 0;
            double[] prevDefXs = null;

            for (int i = 0; i < ticks.size(); i += 30) {
                var tick = ticks.get(i);
                if (tick.minute() > 90) break;

                var defSnaps = tick.players().stream()
                    .filter(p -> p.teamSide().equals(side) && p.position() == Position.DEF)
                    .toList();
                var midSnaps = tick.players().stream()
                    .filter(p -> p.teamSide().equals(side) && p.position() == Position.MID)
                    .toList();
                var attSnaps = tick.players().stream()
                    .filter(p -> p.teamSide().equals(side) && p.position() == Position.ATT)
                    .toList();
                if (defSnaps.isEmpty()) continue;

                double[] defXs = defSnaps.stream().mapToDouble(PlayerSnapshot::x).toArray();
                double defAvg = Arrays.stream(defXs).average().orElse(50);
                double defSigma = Math.sqrt(Arrays.stream(defXs).map(x -> Math.pow(x - defAvg, 2)).average().orElse(0));
                double lastDef = "HOME".equals(side)
                    ? Arrays.stream(defXs).max().orElse(50)  // furthest forward for HOME (attacking right)
                    : Arrays.stream(defXs).min().orElse(50); // furthest forward for AWAY (attacking left)
                double midAvg = midSnaps.stream().mapToDouble(PlayerSnapshot::x).average().orElse(0);
                double attAvg = attSnaps.stream().mapToDouble(PlayerSnapshot::x).average().orElse(0);

                double defMidGap = "HOME".equals(side) ? midAvg - defAvg : defAvg - midAvg;

                // Coordination: all defenders within 15 units of each other
                boolean coordinated = defSigma < 10.0;

                // Also detect rapid coordinated push (>5 units forward in 30 ticks)
                boolean rapidPush = false;
                if (prevDefXs != null && defXs.length == prevDefXs.length) {
                    double push = 0;
                    for (int j = 0; j < defXs.length; j++) {
                        push += "HOME".equals(side)
                            ? defXs[j] - prevDefXs[j]
                            : prevDefXs[j] - defXs[j];
                    }
                    rapidPush = push / defXs.length > 5.0;
                }
                prevDefXs = defXs;

                if (coordinated) coordCount++;
                totalCoordSamples++;

                System.out.printf("  %-5d %10.1f %10.1f %10.1f %10.1f %10.1f %10.1f %8s%n",
                    tick.minute(), defAvg, lastDef, defSigma, midAvg, attAvg, defMidGap,
                    coordinated ? (rapidPush ? "⬆️PUSH!" : "✓") : "✗");
            }

            System.out.printf("%n  %s defense coordination rate: %d/%d (%.0f%%)%n",
                side, coordCount, totalCoordSamples, 100.0 * coordCount / totalCoordSamples);
        }

        // ─────────────────────────────────────────────────────
        // 4. OVERALL SUMMARY
        // ─────────────────────────────────────────────────────
        System.out.println();
        System.out.println("=" .repeat(120));
        System.out.println("  SUMMARY");
        System.out.println("=" .repeat(120));

        System.out.printf("  Total offside events: %d%n", offsides.size());

        // Per-team offsides
        long homeOffsides = offsides.stream().filter(o -> "HOME".equals(o.teamSide())).count();
        long awayOffsides = offsides.stream().filter(o -> "AWAY".equals(o.teamSide())).count();
        System.out.printf("  HOME offsides: %d, AWAY offsides: %d%n", homeOffsides, awayOffsides);

        // Count times attackers were positioned beyond the offside line (potential offside positions)
        int homeAttBeyondLine = 0;
        int awayAttBeyondLine = 0;
        int totalAttSamples = 0;
        for (int i = 0; i < ticks.size(); i += 60) {
            var tick = ticks.get(i);
            if (tick.minute() > 90) break;
            for (var ps : tick.players()) {
                if (ps.position() != Position.ATT) continue;
                double offsideLine = calcOffsideLine(tick, ps.teamSide());
                double offX = "HOME".equals(ps.teamSide()) ? ps.x() - offsideLine : offsideLine - ps.x();
                if (offX > 1.0) {
                    if ("HOME".equals(ps.teamSide())) homeAttBeyondLine++;
                    else awayAttBeyondLine++;
                }
                totalAttSamples++;
            }
        }
        System.out.printf("  Times attacker beyond offside line: HOME=%d, AWAY=%d (total samples=%d)%n",
            homeAttBeyondLine, awayAttBeyondLine, totalAttSamples);

        // Average gap between ATT and offside line
        double homeAttGap = 0;
        double awayAttGap = 0;
        int homeGapSamples = 0, awayGapSamples = 0;
        for (int i = 0; i < ticks.size(); i += 60) {
            var tick = ticks.get(i);
            if (tick.minute() > 90) break;
            for (var ps : tick.players()) {
                if (ps.position() != Position.ATT) continue;
                double offsideLine = calcOffsideLine(tick, ps.teamSide());
                if ("HOME".equals(ps.teamSide())) {
                    homeAttGap += offsideLine - ps.x();
                    homeGapSamples++;
                } else {
                    awayAttGap += ps.x() - offsideLine;
                    awayGapSamples++;
                }
            }
        }
        if (homeGapSamples > 0) homeAttGap /= homeGapSamples;
        if (awayGapSamples > 0) awayAttGap /= awayGapSamples;
        System.out.printf("  Average ATT distance behind offside line: HOME=%.1f, AWAY=%.1f%n",
            homeAttGap, awayAttGap);
        System.out.printf("  (positive = attacker is onside, negative = attacker is offside)%n");

        System.out.println();
        System.out.println("  ✓ Tactical movement diagnostic complete.");
    }

    private double calcOffsideLine(TickSnapshot tick, String attackingTeam) {
        String defTeam = "HOME".equals(attackingTeam) ? "AWAY" : "HOME";
        var defXs = tick.players().stream()
            .filter(p -> p.teamSide().equals(defTeam) && p.position() != Position.GK)
            .mapToDouble(PlayerSnapshot::x)
            .sorted()
            .toArray();
        if (defXs.length == 0) return "HOME".equals(attackingTeam) ? 100.0 : 0.0;
        return "HOME".equals(attackingTeam) ? defXs[defXs.length - 1] : defXs[0];
    }

    private boolean isSnapOffside(TickSnapshot tick, PlayerSnapshot ps, String teamSide) {
        double offsideLine = calcOffsideLine(tick, teamSide);
        boolean inOppHalf = "HOME".equals(teamSide) ? ps.x() > 50.0 : ps.x() < 50.0;
        if (!inOppHalf) return false;
        return "HOME".equals(teamSide) ? ps.x() > offsideLine + 0.5 : ps.x() < offsideLine - 0.5;
    }

    private double calcGoalDistance(PlayerSnapshot ps, String teamSide) {
        if ("HOME".equals(teamSide)) return 100.0 - ps.x();
        return ps.x() - 0.0;
    }

    private String formatEvent(MatchEvent e) {
        return switch (e) {
            case GoalEvent g ->
                "⚽ GOAL! " + g.scorerName() + " (" + g.teamSide() + ")  [" + g.homeScoreAfter() + "-" + g.awayScoreAfter() + "]  xG=" + String.format("%.3f", g.xG());
            case ShotEvent s -> {
                String kind = s.saved() ? "SAVED" : (s.onTarget() ? "ON TARGET" : "MISSED");
                yield "🔴 SHOT: " + s.shooterName() + " (" + s.teamSide() + ")  " + kind + "  xG=" + String.format("%.3f", s.xG());
            }
            case PassEvent p -> {
                String kind = p.intercepted() ? "INTERCEPTED" : "PASS";
                yield "🔵 " + kind + ": " + p.passerName() + " → " + (p.receiverName() != null ? p.receiverName() : "?") + " (" + p.teamSide() + ")";
            }
            case DuelEvent d ->
                "💥 DUEL: " + d.player1Name() + " vs " + d.player2Name() + " (" + d.duelType() + ")  winner=" + (d.attackerWon() ? d.player1Name() : d.player2Name());
            case FoulEvent f -> {
                String where = f.penaltyFoul() ? "PENALTY!" : "FK";
                yield "🟡 FOUL: " + f.takerName() + " on " + f.victimName() + " (" + f.teamSide() + ")  " + where;
            }
            case CardEvent c -> {
                String card = c.cardType() == CardEvent.CardType.YELLOW ? "YELLOW" : "RED";
                yield "🟨 " + card + " CARD: " + c.playerName() + " (" + c.teamSide() + ")";
            }
            case OffsideEvent o ->
                "🚩 OFFSIDE: " + o.playerName() + " (" + o.teamSide() + ")";
            case SetPieceEvent sp ->
                "📐 " + sp.setPieceType().name() + ": " + (sp.takerName() != null ? sp.takerName() : "?") + " (" + sp.teamSide() + ")";
            case PenaltyEvent p ->
                "🎯 PENALTY: " + p.takerName() + " (" + p.teamSide() + ")  scored=" + p.scored();
            case InjuryEvent i ->
                "🆘 INJURY: " + i.playerName() + " (" + i.teamSide() + ")";
            case SubstitutionEvent s ->
                "🔄 SUB: " + s.playerOutName() + " ⬅️ " + s.playerInName() + " (" + s.teamSide() + ")";
            default -> e.type().name() + " (tick=" + e.tick() + ")";
        };
    }
}
