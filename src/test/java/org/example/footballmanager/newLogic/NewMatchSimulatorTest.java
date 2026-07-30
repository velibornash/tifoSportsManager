package org.example.footballmanager.newLogic;

import org.example.footballmanager.newLogic.model.MatchResult;
import org.example.footballmanager.newLogic.model.PlayerSnapshot;
import org.example.footballmanager.newLogic.model.TickSnapshot;
import org.example.footballmanager.newLogic.model.event.*;
import org.example.footballmanager.newLogic.service.MatchOrchestrator;
import org.example.footballmanager.newLogic.store.MatchStore;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class NewMatchSimulatorTest {

    @Test
    void fullMatchProducesValidResult() {
        MatchStore store = new MatchStore();
        MatchOrchestrator orchestrator = new MatchOrchestrator(store);

        long matchId = orchestrator.startMatch("Crvena Zvezda", "Partizan");
        MatchResult result = orchestrator.simulate(matchId);

        // ── BASIC INTEGRITY ──
        assertTrue(result.totalTicks() > 0, "Should have ticks");
        assertFalse(result.events().isEmpty(), "Should have events");
        assertTrue(result.homeGoals() >= 0, "Home goals >= 0");
        assertTrue(result.awayGoals() >= 0, "Away goals >= 0");
        assertTrue(Math.abs(result.homePossession() + result.awayPossession() - 100.0) < 1.0,
            "Possession should sum to ~100%");

        // ── EVENTS BY TYPE ──
        Map<String, Long> eventCounts = result.events().stream()
            .filter(e -> !(e instanceof MatchStartEvent || e instanceof MatchEndEvent))
            .collect(Collectors.groupingBy(e -> e.type().name(), Collectors.counting()));

        System.out.println("\n=== EVENT DISTRIBUTION ===");
        eventCounts.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .forEach(e -> System.out.printf("  %-25s %4d%n", e.getKey(), e.getValue()));

        long passCount = eventCounts.getOrDefault("PASS", 0L);
        long shotCount = eventCounts.getOrDefault("SHOT_MISSED", 0L)
            + eventCounts.getOrDefault("SHOT_ON_TARGET", 0L)
            + eventCounts.getOrDefault("SHOT_SAVED", 0L);
        long goalCount = result.homeGoals() + result.awayGoals();
        long tackleCount = eventCounts.getOrDefault("TACKLE", 0L);

        System.out.printf("%n  Passes: %d, Shots: %d, Goals: %d, Tackles: %d%n",
            passCount, shotCount, goalCount, tackleCount);

        assertTrue(passCount >= 10, "Should have at least 10 passes (got " + passCount + ")");
        assertTrue(result.tickHistory().size() > 100, "Should have >100 ticks recorded");

        // ── NO TELEPORTATION ──
        double MAX_PACE_PER_TICK = 0.5;
        var th = result.tickHistory();
        for (int t = 0; t < Math.min(th.size() - 1, 5000); t++) {
            var tickA = th.get(t);
            var tickB = th.get(t + 1);
            for (var snapA : tickA.players()) {
                long pid = snapA.playerId();
                var snapB = tickB.players().stream()
                    .filter(s -> s.playerId() == pid)
                    .findFirst().orElse(null);
                if (snapB == null) continue;
                double dist = snapA.distanceTo(snapB);
                assertTrue(dist <= MAX_PACE_PER_TICK,
                    "Teleportation! Player " + snapA.name() + " moved " + String.format("%.2f", dist)
                    + " units in tick " + tickA.tick());
            }
        }

        // ── DUEL INTEGRITY ──
        for (var e : result.events()) {
            if (e instanceof DuelEvent d) {
                assertNotEquals(d.player1Id(), d.player2Id(),
                    "Self-duel: " + d.player1Name());
            }
        }

        System.out.println("\n  All assertions passed.");
    }

    @Test
    void matchSimulatesMultipleRunsWithoutCrash() {
        for (int run = 0; run < 3; run++) {
            MatchStore store = new MatchStore();
            MatchOrchestrator orchestrator = new MatchOrchestrator(store);
            long matchId = orchestrator.startMatch("Team A", "Team B");
            MatchResult result = orchestrator.simulate(matchId);

            assertNotNull(result, "Result should not be null on run " + run);
            assertTrue(result.totalTicks() > 0, "Should have ticks on run " + run);
            assertFalse(result.events().isEmpty(), "Should have events on run " + run);
        }
    }
}
