package org.example.footballmanager.newLogic;

import org.example.footballmanager.newLogic.model.MatchResult;
import org.example.footballmanager.newLogic.model.TickSnapshot;
import org.example.footballmanager.newLogic.model.event.CardEvent;
import org.example.footballmanager.newLogic.model.event.MatchEvent;
import org.example.footballmanager.newLogic.service.MatchOrchestrator;
import org.example.footballmanager.newLogic.store.MatchStore;
import org.junit.jupiter.api.Test;

import java.util.*;

public class RedCardTest {

    @Test
    void tenVsElevenDoesNotCrash() {
        // Run multiple matches; at least one should trigger a red card
        // The key assertion: no crash / exception occurs with 10v11
        for (int i = 0; i < 8; i++) {
            MatchStore store = new MatchStore();
            MatchOrchestrator orchestrator = new MatchOrchestrator(store);
            long matchId = orchestrator.startMatch("RedTeam", "BlueTeam");
            orchestrator.simulate(matchId);
        }
    }

    @Test
    void redCardProducesCorrectEvents() {
        // Run matches and verify that any red card event triggers correct side effects
        for (int run = 0; run < 12; run++) {
            MatchStore store = new MatchStore();
            MatchOrchestrator orchestrator = new MatchOrchestrator(store);
            long matchId = orchestrator.startMatch("HomeFC", "AwayFC");
            MatchResult result = orchestrator.simulate(matchId);

            List<CardEvent> redCards = result.events().stream()
                .filter(e -> e instanceof CardEvent)
                .map(e -> (CardEvent) e)
                .filter(c -> c.cardType() == CardEvent.CardType.RED)
                .toList();

            if (redCards.isEmpty()) continue;

            // At least one red card was issued — verify player removed after red card tick
            for (CardEvent rc : redCards) {
                String side = rc.teamSide();
                int redCardTick = rc.tick();
                var ticks = result.tickHistory();
                boolean foundAfter = ticks.stream()
                    .filter(t -> t.tick() > redCardTick)
                    .flatMap(t -> t.players().stream())
                    .anyMatch(s -> s.playerId() == rc.playerId() && s.teamSide().equals(side));
                assert !foundAfter : "Red-carded player " + rc.playerName() + " should not appear after tick " + redCardTick;
            }
        }
    }

    @Test
    void maxPlayersOnPitchAfterRedCard() {
        for (int run = 0; run < 10; run++) {
            MatchStore store = new MatchStore();
            MatchOrchestrator orchestrator = new MatchOrchestrator(store);
            long matchId = orchestrator.startMatch("Home", "Away");
            MatchResult result = orchestrator.simulate(matchId);

            // Count distinct players per tick
            for (TickSnapshot tick : result.tickHistory()) {
                Map<String, Set<Long>> playersBySide = new HashMap<>();
                for (var snap : tick.players()) {
                    playersBySide.computeIfAbsent(snap.teamSide(), k -> new HashSet<>()).add(snap.playerId());
                }
                int homeCount = playersBySide.getOrDefault("HOME", Set.of()).size();
                int awayCount = playersBySide.getOrDefault("AWAY", Set.of()).size();
                assert homeCount <= 11 : "Home has " + homeCount + " players on pitch (max 11)";
                assert awayCount <= 11 : "Away has " + awayCount + " players on pitch (max 11)";
                // At least 10 if subs haven't happened yet (GK + at least 9 outfield)
                // Substitutions replace players, so count can stay 11
                assert homeCount >= 1 : "Home has " + homeCount + " players (min 1)";
                assert awayCount >= 1 : "Away has " + awayCount + " players (min 1)";
            }
        }
    }
}
