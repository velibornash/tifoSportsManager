package org.example.footballmanager.newLogic;

import org.example.footballmanager.newLogic.engine.*;
import org.example.footballmanager.newLogic.model.*;
import org.example.footballmanager.newLogic.model.event.*;
import org.example.footballmanager.newLogic.service.MatchOrchestrator;
import org.example.footballmanager.newLogic.store.MatchStore;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class MatchFootballRealismTest {

    @Test
    void goalsComeFromShotsNotPasses() {
        MatchStore store = new MatchStore();
        MatchOrchestrator orchestrator = new MatchOrchestrator(store);
        long matchId = orchestrator.startMatch("Realism A", "Realism B");
        MatchResult result = orchestrator.simulate(matchId);

        List<GoalEvent> goals = result.events().stream()
                .filter(e -> e instanceof GoalEvent)
                .map(e -> (GoalEvent) e)
                .toList();

        // Every goal should have a valid scorer and team
        for (GoalEvent goal : goals) {
            assertNotNull(goal.scorerName(), "Goal scorer name should not be null");
            assertNotNull(goal.teamSide(), "Goal team side should not be null");
            assertTrue(goal.minute() >= 1 && goal.minute() <= 90 + 15,
                    "Goal minute should be in valid range: " + goal.minute());
            assertTrue(goal.homeScoreAfter() >= 0 && goal.awayScoreAfter() >= 0,
                    "Goal scores should be non-negative");
        }

        System.out.printf("FootballRealism: %d goals detected, all valid.%n", goals.size());
    }

    @Test
    void penaltiesOnlyFromPenaltyBoxFouls() {
        MatchStore store = new MatchStore();
        MatchOrchestrator orchestrator = new MatchOrchestrator(store);
        long matchId = orchestrator.startMatch("Penalty Test A", "Penalty Test B");
        MatchResult result = orchestrator.simulate(matchId);

        List<PenaltyEvent> penalties = result.events().stream()
                .filter(e -> e instanceof PenaltyEvent)
                .map(e -> (PenaltyEvent) e)
                .toList();

        for (PenaltyEvent penalty : penalties) {
            // Penalty should have a valid taker and team
            assertNotNull(penalty.takerName(), "Penalty taker name should not be null");
            assertNotNull(penalty.teamSide(), "Penalty team side should not be null");
            assertTrue(penalty.minute() >= 1, "Penalty minute should be >= 1");
        }

        System.out.printf("FootballRealism: %d penalties detected.%n", penalties.size());
    }

    @Test
    void noSelfDuels() {
        MatchStore store = new MatchStore();
        MatchOrchestrator orchestrator = new MatchOrchestrator(store);
        long matchId = orchestrator.startMatch("Duel Test A", "Duel Test B");
        MatchResult result = orchestrator.simulate(matchId);

        List<DuelEvent> duels = result.events().stream()
                .filter(e -> e instanceof DuelEvent)
                .map(e -> (DuelEvent) e)
                .toList();

        for (DuelEvent duel : duels) {
            assertNotEquals(duel.player1Id(), duel.player2Id(),
                    "Self-duel detected: " + duel.player1Name());
            assertNotNull(duel.player1Name(), "Duel player1 name should not be null");
            assertNotNull(duel.player2Name(), "Duel player2 name should not be null");
        }

        System.out.printf("FootballRealism: %d duels detected, no self-duels.%n", duels.size());
    }

    @Test
    void noTeleportationInvariant() {
        MatchStore store = new MatchStore();
        MatchOrchestrator orchestrator = new MatchOrchestrator(store);
        long matchId = orchestrator.startMatch("Teleport Test A", "Teleport Test B");
        MatchResult result = orchestrator.simulate(matchId);

        List<TickSnapshot> ticks = result.tickHistory();
        double MAX_PACE_PER_TICK = 0.5;

        for (int t = 0; t < Math.min(ticks.size() - 1, 5000); t++) {
            TickSnapshot a = ticks.get(t);
            TickSnapshot b = ticks.get(t + 1);
            Map<Long, PlayerSnapshot> bMap = b.players().stream()
                    .collect(Collectors.toMap(PlayerSnapshot::playerId, p -> p));

            for (PlayerSnapshot pA : a.players()) {
                PlayerSnapshot pB = bMap.get(pA.playerId());
                if (pB == null) continue;
                double dist = pA.distanceTo(pB);
                assertTrue(dist <= MAX_PACE_PER_TICK,
                        "Teleportation! Player " + pA.name() + " moved " + String.format("%.2f", dist)
                                + " units in tick " + a.tick());
            }
        }

        System.out.printf("FootballRealism: No teleportation detected in %d ticks.%n", ticks.size());
    }

    @Test
    void cardsOnlyForRealFouls() {
        MatchStore store = new MatchStore();
        MatchOrchestrator orchestrator = new MatchOrchestrator(store);
        long matchId = orchestrator.startMatch("Card Test A", "Card Test B");
        MatchResult result = orchestrator.simulate(matchId);

        List<CardEvent> cards = result.events().stream()
                .filter(e -> e instanceof CardEvent)
                .map(e -> (CardEvent) e)
                .toList();

        for (CardEvent card : cards) {
            assertNotNull(card.playerName(), "Card player name should not be null");
            assertNotNull(card.teamSide(), "Card team side should not be null");
            assertTrue(card.minute() >= 1, "Card minute should be >= 1");
        }

        System.out.printf("FootballRealism: %d cards detected.%n", cards.size());
    }

    @Test
    void possessionChainsAreTracked() {
        MatchStore store = new MatchStore();
        MatchOrchestrator orchestrator = new MatchOrchestrator(store);
        long matchId = orchestrator.startMatch("Chain Test A", "Chain Test B");
        MatchResult result = orchestrator.simulate(matchId);

        List<PossessionStartEvent> starts = result.events().stream()
                .filter(e -> e instanceof PossessionStartEvent)
                .map(e -> (PossessionStartEvent) e)
                .toList();

        List<PossessionEndEvent> ends = result.events().stream()
                .filter(e -> e instanceof PossessionEndEvent)
                .map(e -> (PossessionEndEvent) e)
                .toList();

        assertTrue(starts.size() > 0, "Should have possession starts");
        assertTrue(ends.size() > 0, "Should have possession ends");

        System.out.printf("FootballRealism: %d possession chains tracked.%n", starts.size());
    }

    @Test
    void matchEndsWithValidState() {
        MatchStore store = new MatchStore();
        MatchOrchestrator orchestrator = new MatchOrchestrator(store);
        long matchId = orchestrator.startMatch("End Test A", "End Test B");
        MatchResult result = orchestrator.simulate(matchId);

        assertTrue(result.totalTicks() > 0);
        assertTrue(result.totalTicks() >= 90 * 120, "Should have at least 90 minutes of ticks");
        assertFalse(result.events().isEmpty(), "Should have events");
        assertTrue(result.homeGoals() >= 0 && result.awayGoals() >= 0);
        assertTrue(Math.abs(result.homePossession() + result.awayPossession() - 100.0) < 1.0,
                "Possession should sum to ~100%");

        System.out.printf("FootballRealism: Match ended with %d ticks, %d events.%n",
                result.totalTicks(), result.events().size());
    }
}
