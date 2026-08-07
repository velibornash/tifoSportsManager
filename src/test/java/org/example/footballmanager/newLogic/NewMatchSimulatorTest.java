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

    @Test
    void tenMatchDiagnosticSummary() {
        int totalGoals = 0, totalShots = 0, totalPasses = 0, totalCorners = 0;
        int totalFouls = 0, totalCards = 0, totalSubs = 0, totalDuels = 0;
        int totalShotsOnTarget = 0, totalYelCards = 0, totalReds = 0;

        for (int i = 0; i < 10; i++) {
            MatchStore store = new MatchStore();
            MatchOrchestrator orchestrator = new MatchOrchestrator(store);
            long matchId = orchestrator.startMatch("TeamA" + i, "TeamB" + i);
            MatchResult result = orchestrator.simulate(matchId);

            totalGoals += result.homeGoals() + result.awayGoals();
            totalShots += result.homeShots() + result.awayShots();
            totalShotsOnTarget += result.homeShotsOnTarget() + result.awayShotsOnTarget();
            totalCorners += result.homeCorners() + result.awayCorners();
            totalFouls += result.homeFouls() + result.awayFouls();
            totalYelCards += result.homeYellowCards() + result.awayYellowCards();
            totalReds += result.homeRedCards() + result.awayRedCards();
            totalCards += totalYelCards + totalReds;

            long passes = result.events().stream().filter(e -> e instanceof PassEvent).count();
            totalPasses += passes;
            long duels = result.events().stream()
                .filter(e -> e instanceof DuelEvent || e instanceof TackleEvent).count();
            totalDuels += duels;
            long subs = result.events().stream().filter(e -> e instanceof SubstitutionEvent).count();
            totalSubs += subs;
            long corners = result.events().stream()
                .filter(e -> e instanceof SetPieceEvent spe
                    && spe.setPieceType() == SetPieceEvent.SetPieceType.CORNER)
                .count();
            totalCorners += corners;

            System.out.printf("Match %2d: %d-%d | Passes: %3d | Shots: %2d (OT:%d) | Goals: %d | Corners: %2d | Poss: H=%.0f%% | Events: %d%n",
                i + 1, result.homeGoals(), result.awayGoals(),
                passes, result.homeShots() + result.awayShots(),
                result.homeShotsOnTarget() + result.awayShotsOnTarget(),
                result.homeGoals() + result.awayGoals(),
                corners, result.homePossession(), result.events().size());
        }

        System.out.printf("%n=== AVERAGES (10 simulated matches) ===%n");
        System.out.printf("Goals/match:       %.1f%n", totalGoals / 10.0);
        System.out.printf("Shots/match:       %.1f%n", totalShots / 10.0);
        System.out.printf("Shots on target:   %.1f%n", totalShotsOnTarget / 10.0);
        System.out.printf("Passes/match:      %.0f%n", (double) totalPasses / 10.0);
        System.out.printf("Corners/match:     %.1f%n", totalCorners / 10.0);
        System.out.printf("Fouls/match:       %.1f%n", totalFouls / 10.0);
        System.out.printf("Cards/match:       %.1f%n", totalCards / 10.0);
        System.out.printf("Substitutions/match: %.1f%n", totalSubs / 10.0);
        System.out.printf("Duels/interceptions/match: %.0f%n", (double) totalDuels / 10.0);

        // Diagnostic output only - no assertions required
    }
}
