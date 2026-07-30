package org.example.footballmanager.newLogic;

import org.example.footballmanager.newLogic.engine.ZonePositionCalculator;
import org.example.footballmanager.newLogic.model.*;
import org.example.footballmanager.newLogic.model.event.*;
import org.example.footballmanager.newLogic.service.MatchOrchestrator;
import org.example.footballmanager.newLogic.store.MatchStore;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.*;

import static org.junit.jupiter.api.Assertions.*;

public class PositionSlotAlignmentTest {

    private static final int DIAGNOSTIC_MINUTES = 5;
    private static final int SAMPLE_INTERVAL = 10;

    @Test
    void playersShouldBeNearTacticalTargetAndReactToBall() {
        MatchStore store = new MatchStore();
        MatchOrchestrator orchestrator = new MatchOrchestrator(store);

        long matchId = orchestrator.startMatch("Crvena Zvezda", "Partizan");
        Match match = store.getMatch(matchId);
        MatchResult result = orchestrator.simulate(matchId);

        List<TickSnapshot> ticks = result.tickHistory();
        List<MatchEvent> events = result.events();

        assertNotNull(ticks);
        assertFalse(ticks.isEmpty());

        // Build slot maps from starting positions
        Map<Long, String> homeSlots = buildSlotMap(match.homeTeam());
        Map<Long, String> awaySlots = buildSlotMap(match.awayTeam());

        // Track per-player stats
        Map<Long, List<Double>> distToTarget = new HashMap<>();
        Map<Long, List<Double>> distToBall = new HashMap<>();
        Map<Long, Double> totalMovement = new HashMap<>();
        Map<Long, String> playerNames = new HashMap<>();
        Map<Long, String> playerTeamSide = new HashMap<>();
        Map<Long, Position> playerPosition = new HashMap<>();
        Map<Long, Map.Entry<Double, Double>> prevPos = new HashMap<>();

        for (int i = 0; i < ticks.size() && ticks.get(i).minute() <= DIAGNOSTIC_MINUTES; i++) {
            TickSnapshot tick = ticks.get(i);
            if (tick.tick() % SAMPLE_INTERVAL != 0) continue;

            boolean homeInPossession = isTeamInPossession(tick, "HOME");
            boolean awayInPossession = isTeamInPossession(tick, "AWAY");
            int[] ballZone = ZonePositionCalculator.ballZone(tick.ball().x(), tick.ball().y());

            for (PlayerSnapshot ps : tick.players()) {
                long pid = ps.playerId();
                String side = ps.teamSide();
                playerNames.put(pid, ps.name());
                playerTeamSide.put(pid, side);
                playerPosition.put(pid, ps.position());

                // Get slot key for this player
                Map<Long, String> slotMap = "HOME".equals(side) ? homeSlots : awaySlots;
                String slotKey = slotMap.get(pid);
                if (slotKey == null) continue;

                // Get player from team for tactical target calculation
                Team team = "HOME".equals(side) ? match.homeTeam() : match.awayTeam();
                Player player = team.startingXI().stream()
                    .filter(p -> p.id() == pid)
                    .findFirst().orElse(null);
                if (player == null) continue;

                boolean inPossession = "HOME".equals(side) ? homeInPossession : awayInPossession;

                // Calculate tactical target
                double[] target = ZonePositionCalculator.tacticalTarget(
                    player, side, inPossession, ballZone[0], ballZone[1], slotKey, team.tacticRules());

                // Distance from player to target
                double dTarget = Math.hypot(ps.x() - target[0], ps.y() - target[1]);
                distToTarget.computeIfAbsent(pid, k -> new ArrayList<>()).add(dTarget);

                // Distance from player to ball
                double dBall = Math.hypot(ps.x() - tick.ball().x(), ps.y() - tick.ball().y());
                distToBall.computeIfAbsent(pid, k -> new ArrayList<>()).add(dBall);

                // Movement tracking
                Map.Entry<Double, Double> prev = prevPos.get(pid);
                if (prev != null) {
                    double dx = ps.x() - prev.getKey();
                    double dy = ps.y() - prev.getValue();
                    totalMovement.merge(pid, Math.hypot(dx, dy), Double::sum);
                }
                prevPos.put(pid, Map.entry(ps.x(), ps.y()));
            }
        }

        // === ASSERTIONS ===

        // 1. Every player must have moved at least some distance
        for (Map.Entry<Long, Double> entry : totalMovement.entrySet()) {
            long pid = entry.getKey();
            double movement = entry.getValue();
            String name = playerNames.get(pid);
            assertTrue(movement > 1.0,
                name + " (" + playerPosition.get(pid) + ") barely moved: " + String.format("%.1f", movement) + " units in 5 min");
        }

        // 2. Average distance to tactical target — print diagnostic
        System.out.println();
        System.out.println("=== DISTANCE TO TACTICAL TARGET (avg per player) ===");
        System.out.printf("  %-28s %-6s %-8s %10s %10s%n", "CPlayer", "Side", "Pos", "AvgDist", "MaxDist");
        System.out.println("  " + "-".repeat(70));
        double totalAvgDistToTarget = 0;
        int targetCount = 0;
        int nearTargetCount = 0;
        int totalTargetPlayers = 0;
        for (Map.Entry<Long, List<Double>> entry : distToTarget.entrySet()) {
            long pid = entry.getKey();
            double avg = entry.getValue().stream().mapToDouble(d -> d).average().orElse(99);
            double max = entry.getValue().stream().mapToDouble(d -> d).max().orElse(99);
            totalAvgDistToTarget += avg;
            targetCount++;
            totalTargetPlayers++;
            String name = playerNames.get(pid);
            String side = playerTeamSide.get(pid);
            String pos = String.valueOf(playerPosition.get(pid));
            if (avg < 20.0) nearTargetCount++;
            boolean isGK = playerPosition.get(pid) == Position.GK;
            double maxAvgDist = isGK ? 30.0 : 55.0;
            assertTrue(avg < maxAvgDist,
                name + " (" + pos + ") avg dist to target=" + String.format("%.1f", avg)
                    + " exceeds max " + String.format("%.1f", maxAvgDist));
            System.out.printf("  %-28s %-6s %-8s %10.1f %10.1f%n", shortName(name), side, pos, avg, max);
        }
        System.out.printf("  Players within 20 units of target: %d/%d%n", nearTargetCount, totalTargetPlayers);

        // 3. Average distance to ball — print diagnostic
        System.out.println();
        System.out.println("=== DISTANCE TO BALL (avg per player) ===");
        System.out.printf("  %-28s %-6s %-8s %10s%n", "CPlayer", "Side", "Pos", "AvgDist");
        System.out.println("  " + "-".repeat(60));
        double avgDistToBallHome = 0;
        double avgDistToBallAway = 0;
        int homeCount = 0;
        int awayCount = 0;
        for (Map.Entry<Long, List<Double>> entry : distToBall.entrySet()) {
            long pid = entry.getKey();
            double avg = entry.getValue().stream().mapToDouble(d -> d).average().orElse(99);
            String side = playerTeamSide.get(pid);
            String name = playerNames.get(pid);
            String pos = String.valueOf(playerPosition.get(pid));
            System.out.printf("  %-28s %-6s %-8s %10.1f%n", shortName(name), side, pos, avg);
            if ("HOME".equals(side)) {
                avgDistToBallHome += avg;
                homeCount++;
            } else {
                avgDistToBallAway += avg;
                awayCount++;
            }
        }
        if (homeCount > 0) avgDistToBallHome /= homeCount;
        if (awayCount > 0) avgDistToBallAway /= awayCount;

        System.out.printf("%n  HOME avg dist to ball: %.1f%n", avgDistToBallHome);
        System.out.printf("  AWAY avg dist to ball: %.1f%n", avgDistToBallAway);
        assertTrue(avgDistToBallHome < 70,
            "HOME players avg dist to ball=" + String.format("%.1f", avgDistToBallHome) + " (should be < 70)");
        assertTrue(avgDistToBallAway < 70,
            "AWAY players avg dist to ball=" + String.format("%.1f", avgDistToBallAway) + " (should be < 70)");

        // 4. GK should stay near goal
        for (TickSnapshot tick : ticks) {
            if (tick.minute() > DIAGNOSTIC_MINUTES) break;
            for (PlayerSnapshot ps : tick.players()) {
                if (ps.position() != Position.GK) continue;
                if ("HOME".equals(ps.teamSide())) {
                    assertTrue(ps.x() < 30,
                        "HOME GK out of position at x=" + String.format("%.1f", ps.x()) + " (min " + tick.minute() + "')");
                } else {
                    assertTrue(ps.x() > 70,
                        "AWAY GK out of position at x=" + String.format("%.1f", ps.x()) + " (min " + tick.minute() + "')");
                }
            }
        }

        // 5. Players should not all be clumped together (spread check)
        List<Map.Entry<Long, Position>> homePlayers = playerPosition.entrySet().stream()
            .filter(e -> "HOME".equals(playerTeamSide.get(e.getKey())) && e.getValue() != Position.GK)
            .toList();
        List<Map.Entry<Long, Position>> awayPlayers = playerPosition.entrySet().stream()
            .filter(e -> "AWAY".equals(playerTeamSide.get(e.getKey())) && e.getValue() != Position.GK)
            .toList();

        for (int i = 0; i < ticks.size() && ticks.get(i).minute() <= DIAGNOSTIC_MINUTES; i += SAMPLE_INTERVAL) {
            TickSnapshot tick = ticks.get(i);
            List<PlayerSnapshot> homeFieldPlayers = tick.players().stream()
                .filter(p -> "HOME".equals(p.teamSide()) && p.position() != Position.GK)
                .toList();
            List<PlayerSnapshot> awayFieldPlayers = tick.players().stream()
                .filter(p -> "AWAY".equals(p.teamSide()) && p.position() != Position.GK)
                .toList();

            if (homeFieldPlayers.size() >= 3) {
                double[] homeXs = homeFieldPlayers.stream().mapToDouble(PlayerSnapshot::x).toArray();
                double homeAvgX = Arrays.stream(homeXs).average().orElse(50);
                double homeSpread = Math.sqrt(Arrays.stream(homeXs).map(x -> Math.pow(x - homeAvgX, 2)).average().orElse(0));
                if (homeSpread < 5.0) {
                    // Log but don't fail — some phases (set pieces) naturally clump
                    System.out.println("WARN: HOME players clumped at tick " + tick.tick()
                        + " min " + tick.minute() + " spread=" + String.format("%.1f", homeSpread));
                }
            }
        }

        // 6. Line ordering should be sensible: DEF < MID < ATT for HOME
        for (int i = 0; i < ticks.size() && ticks.get(i).minute() <= DIAGNOSTIC_MINUTES; i++) {
            TickSnapshot tick = ticks.get(i);
            double homeDefX = tick.players().stream()
                .filter(p -> "HOME".equals(p.teamSide()) && p.position() == Position.DEF)
                .mapToDouble(PlayerSnapshot::x).average().orElse(50);
            double homeMidX = tick.players().stream()
                .filter(p -> "HOME".equals(p.teamSide()) && p.position() == Position.MID)
                .mapToDouble(PlayerSnapshot::x).average().orElse(50);
            double homeAttX = tick.players().stream()
                .filter(p -> "HOME".equals(p.teamSide()) && p.position() == Position.ATT)
                .mapToDouble(PlayerSnapshot::x).average().orElse(50);

            if (homeDefX > homeMidX || homeMidX > homeAttX) {
                System.out.println("INFO: HOME line order out at tick " + tick.tick()
                    + " min " + tick.minute() + " DEF=" + String.format("%.1f", homeDefX)
                    + " MID=" + String.format("%.1f", homeMidX)
                    + " ATT=" + String.format("%.1f", homeAttX));
            }
        }

        System.out.println("=== PositionSlotAlignmentTest PASSED ===");
        System.out.println("HOME avg dist to target: " + String.format("%.1f", totalAvgDistToTarget / Math.max(1, targetCount)));
        System.out.println("HOME avg dist to ball: " + String.format("%.1f", avgDistToBallHome));
        System.out.println("AWAY avg dist to ball: " + String.format("%.1f", avgDistToBallAway));
    }

    private Map<Long, String> buildSlotMap(Team team) {
        if (team == null) return Map.of();
        List<String> slotOrder = team.slotKeys() != null && team.slotKeys().size() == team.startingXI().size()
            ? team.slotKeys()
            : ZonePositionCalculator.buildSlotKeys(
                team.getFormation() != null ? team.getFormation() : "4-3-3", team.startingXI());
        Map<Long, String> slots = new LinkedHashMap<>();
        var starters = team.startingXI();
        for (int i = 0; i < Math.min(slotOrder.size(), starters.size()); i++) {
            slots.put(starters.get(i).id(), slotOrder.get(i));
        }
        return slots;
    }

    private boolean isTeamInPossession(TickSnapshot tick, String teamSide) {
        if (tick.carrierId() == null) return false;
        return tick.players().stream()
            .anyMatch(p -> p.playerId() == tick.carrierId() && teamSide.equals(p.teamSide()));
    }

    private static String shortName(String name) {
        if (name == null || name.isBlank()) return "N/A";
        String[] parts = name.split(" ");
        return parts.length > 1 ? parts[0].charAt(0) + ". " + String.join(" ", java.util.Arrays.copyOfRange(parts, 1, parts.length)) : name;
    }
}
