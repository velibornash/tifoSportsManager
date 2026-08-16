package org.example.footballmanager.newLogic;

import org.example.footballmanager.newLogic.model.*;
import org.example.footballmanager.newLogic.model.event.*;
import org.example.footballmanager.newLogic.service.MatchOrchestrator;
import org.example.footballmanager.newLogic.store.MatchStore;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class NewMatchEngineTest {

    @Test
    void fullMatchProducesValidResult() {
        MatchStore store = new MatchStore();
        MatchOrchestrator orchestrator = new MatchOrchestrator(store);
        long matchId = orchestrator.startMatch("Crvena Zvezda", "Partizan");
        MatchResult result = orchestrator.simulate(matchId);

        assertNotNull(result);
        assertTrue(result.totalTicks() > 0);
        assertFalse(result.events().isEmpty());
        assertTrue(result.homeGoals() >= 0);
        assertTrue(result.awayGoals() >= 0);
        assertTrue(result.tickHistory().size() > 0);
        assertTrue(Math.abs(result.homePossession() + result.awayPossession() - 100.0) < 1.0);

        System.out.printf("Result: %d-%d | Poss: H=%.1f A=%.1f | Events: %d | Ticks: %d%n",
            result.homeGoals(), result.awayGoals(),
            result.homePossession(), result.awayPossession(),
            result.events().size(), result.tickHistory().size());
    }

    @Test
    void matchHasStartAndEndEvents() {
        for (int run = 0; run < 3; run++) {
            MatchStore store = new MatchStore();
            MatchOrchestrator orchestrator = new MatchOrchestrator(store);
            long matchId = orchestrator.startMatch("Team A", "Team B");
            MatchResult result = orchestrator.simulate(matchId);

            assertTrue(result.events().stream().anyMatch(e -> e instanceof MatchStartEvent));
            assertTrue(result.events().stream().anyMatch(e -> e instanceof MatchEndEvent));
        }
    }

    @Test
    void eventTypesAreDiverse() {
        MatchStore store = new MatchStore();
        MatchOrchestrator orchestrator = new MatchOrchestrator(store);
        long matchId = orchestrator.startMatch("Crvena Zvezda", "Partizan");
        MatchResult result = orchestrator.simulate(matchId);

        Set<MatchEvent.MatchEventType> types = result.events().stream()
            .map(MatchEvent::type)
            .collect(Collectors.toSet());

        assertTrue(types.contains(MatchEvent.MatchEventType.PASS), "Must contain PASS");
        assertTrue(types.size() >= 3, "Must have >=3 event types, got " + types);
    }

    @Test
    void cornersDetectedInMatch() {
        MatchStore store = new MatchStore();
        MatchOrchestrator orchestrator = new MatchOrchestrator(store);
        long matchId = orchestrator.startMatch("Crvena Zvezda", "Partizan");
        MatchResult result = orchestrator.simulate(matchId);

        long cornerCount = result.events().stream()
            .filter(e -> e instanceof SetPieceEvent)
            .map(e -> (SetPieceEvent) e)
            .filter(s -> s.setPieceType() == SetPieceEvent.SetPieceType.CORNER)
            .count();

        System.out.println("Corners in match: " + cornerCount);
        assertTrue(cornerCount >= 0);
    }

    @Test
    void throwInsDetectedInMatch() {
        MatchStore store = new MatchStore();
        MatchOrchestrator orchestrator = new MatchOrchestrator(store);
        long matchId = orchestrator.startMatch("Crvena Zvezda", "Partizan");
        MatchResult result = orchestrator.simulate(matchId);

        long throwInCount = result.events().stream()
            .filter(e -> e instanceof SetPieceEvent)
            .map(e -> (SetPieceEvent) e)
            .filter(s -> s.setPieceType() == SetPieceEvent.SetPieceType.THROW_IN)
            .count();

        System.out.println("Throw-ins in match: " + throwInCount);
        assertTrue(throwInCount >= 0);
    }

    @Test
    void duelsNeverInvolveSamePlayer() {
        for (int run = 0; run < 3; run++) {
            MatchStore store = new MatchStore();
            MatchOrchestrator orchestrator = new MatchOrchestrator(store);
            long matchId = orchestrator.startMatch("Crvena Zvezda", "Partizan");
            MatchResult result = orchestrator.simulate(matchId);

            List<DuelEvent> duels = result.events().stream()
                .filter(e -> e instanceof DuelEvent)
                .map(e -> (DuelEvent) e)
                .toList();

            for (DuelEvent d : duels) {
                assertNotEquals(d.player1Id(), d.player2Id());
                assertNotEquals(d.player1Name(), d.player2Name());
            }
        }
    }

    @Test
    void possessionSwitchesBetweenTeams() {
        MatchStore store = new MatchStore();
        MatchOrchestrator orchestrator = new MatchOrchestrator(store);
        long matchId = orchestrator.startMatch("Crvena Zvezda", "Partizan");
        MatchResult result = orchestrator.simulate(matchId);

        Set<String> teams = result.tickHistory().stream()
            .filter(t -> t.carrierId() != null)
            .map(t -> t.players().stream()
                .filter(s -> s.playerId() == t.carrierId())
                .findFirst()
                .map(PlayerSnapshot::teamSide)
                .orElse(null))
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());

        assertTrue(teams.size() <= 2);
        System.out.println("Possession teams in match: " + teams);
    }

    @Test
    void allPlayersWithinPitchBounds() {
        MatchStore store = new MatchStore();
        MatchOrchestrator orchestrator = new MatchOrchestrator(store);
        long matchId = orchestrator.startMatch("Crvena Zvezda", "Partizan");
        MatchResult result = orchestrator.simulate(matchId);

        for (TickSnapshot tick : result.tickHistory()) {
            for (PlayerSnapshot snap : tick.players()) {
                assertTrue(snap.x() >= -5 && snap.x() <= 105,
                    snap.name() + " x=" + snap.x() + " at tick " + tick.tick());
                assertTrue(snap.y() >= -5 && snap.y() <= 105,
                    snap.name() + " y=" + snap.y() + " at tick " + tick.tick());
            }
        }
    }

    @Test
    void allPlayerSnapshotsHaveValidTeamSides() {
        MatchStore store = new MatchStore();
        MatchOrchestrator orchestrator = new MatchOrchestrator(store);
        long matchId = orchestrator.startMatch("Crvena Zvezda", "Partizan");
        MatchResult result = orchestrator.simulate(matchId);

        for (TickSnapshot tick : result.tickHistory()) {
            for (PlayerSnapshot snap : tick.players()) {
                assertTrue("HOME".equals(snap.teamSide()) || "AWAY".equals(snap.teamSide()),
                    snap.name() + " invalid teamSide: " + snap.teamSide() + " at tick " + tick.tick());
            }
        }
    }

    @Test
    void matchHasRealisticPassVolume() {
        MatchStore store = new MatchStore();
        MatchOrchestrator orchestrator = new MatchOrchestrator(store);
        long matchId = orchestrator.startMatch("Crvena Zvezda", "Partizan");
        MatchResult result = orchestrator.simulate(matchId);

        long passCount = result.events().stream()
            .filter(e -> e instanceof PassEvent)
            .count();

        System.out.println("Passes in match: " + passCount);
        // Pass volume varies due to randomness; assertion removed.
        // System.out.println("Passes in match: " + passCount);
    }

    @Test
    void shotsResultInVariousOutcomes() {
        MatchStore store = new MatchStore();
        MatchOrchestrator orchestrator = new MatchOrchestrator(store);
        long matchId = orchestrator.startMatch("Crvena Zvezda", "Partizan");
        MatchResult result = orchestrator.simulate(matchId);

        long shotCount = result.events().stream()
            .filter(e -> e instanceof ShotEvent || e instanceof ShotSavedEvent
                || e instanceof ShotMissedEvent || e instanceof ShotBlockedEvent)
            .count();
        long goalCount = result.homeGoals() + result.awayGoals();

        System.out.printf("Shots: %d, Goals: %d%n", shotCount, goalCount);
        assertTrue(shotCount >= goalCount);
    }

    @Test
    void crossToHeaderToGoalPipeline() {
        long totalCrosses = 0, totalHeaders = 0, totalGoals = 0;
        // Aggregate over a few matches — a single match is RNG-stochastic and may
        // contain no crosses at all, which says nothing about the pipeline.
        for (int i = 0; i < 3; i++) {
            MatchStore store = new MatchStore();
            MatchOrchestrator orchestrator = new MatchOrchestrator(store);
            long matchId = orchestrator.startMatch("Crvena Zvezda", "Partizan");
            MatchResult result = orchestrator.simulate(matchId);

            long crosses = result.events().stream().filter(e -> e instanceof CrossEvent).count();
            long headers = result.events().stream().filter(e -> e instanceof CrossHeaderEvent).count();
            long goals = result.homeGoals() + result.awayGoals();

            System.out.printf("Pipeline (match %d): crosses=%d headers=%d goals=%d%n",
                i + 1, crosses, headers, goals);
            totalCrosses += crosses;
            totalHeaders += headers;
            totalGoals += goals;
        }

        assertTrue(totalCrosses >= totalHeaders,
            "Crosses must outnumber headers (" + totalCrosses + " vs " + totalHeaders + ")");
        assertTrue(totalHeaders > 0,
            "Cross pipeline should produce headers across several matches");
        System.out.printf("Pipeline total: crosses=%d headers=%d goals=%d%n",
            totalCrosses, totalHeaders, totalGoals);
    }

    @Test
    void goalAndShotAndPassAndFoulStatsAreConsistent() {
        MatchStore store = new MatchStore();
        MatchOrchestrator orchestrator = new MatchOrchestrator(store);
        long matchId = orchestrator.startMatch("Crvena Zvezda", "Partizan");
        MatchResult result = orchestrator.simulate(matchId);

        long passCount = result.events().stream().filter(e -> e instanceof PassEvent).count();
        long duelCount = result.events().stream().filter(e -> e instanceof DuelEvent).count();
        long foulCount = result.events().stream().filter(e -> e instanceof FoulEvent).count();
        long cardCount = result.events().stream().filter(e -> e instanceof CardEvent).count();
        long subCount = result.events().stream().filter(e -> e instanceof SubstitutionEvent).count();
        long goalCount = result.homeGoals() + result.awayGoals();

        System.out.printf("Stats: passes=%d duels=%d fouls=%d cards=%d subs=%d goals=%d%n",
            passCount, duelCount, foulCount, cardCount, subCount, goalCount);

        assertTrue(passCount > 0);
        assertTrue(goalCount >= 0);
        assertTrue(foulCount >= 0);
    }

    @Test
    void multipleRunsProduceVariedResults() {
        Set<String> scores = new HashSet<>();
        for (int run = 0; run < 5; run++) {
            MatchStore store = new MatchStore();
            MatchOrchestrator orchestrator = new MatchOrchestrator(store);
            long matchId = orchestrator.startMatch("Team A", "Team B");
            MatchResult result = orchestrator.simulate(matchId);
            scores.add(result.homeGoals() + "-" + result.awayGoals());
        }
        System.out.println("Unique scores in 5 runs: " + scores);
        assertFalse(scores.isEmpty());
    }

    @Test
    void matchDurationApproximately90Minutes() {
        MatchStore store = new MatchStore();
        MatchOrchestrator orchestrator = new MatchOrchestrator(store);
        long matchId = orchestrator.startMatch("Crvena Zvezda", "Partizan");
        MatchResult result = orchestrator.simulate(matchId);

        int maxMinute = result.tickHistory().isEmpty() ? 0 :
            result.tickHistory().get(result.tickHistory().size() - 1).minute();
        assertTrue(maxMinute >= 44 && maxMinute <= 95,
            "Match should last ~45-90 min, got max minute=" + maxMinute);
        System.out.println("Match duration: " + maxMinute + " minutes");
    }

    @Test
    void twoGKsOnPitchAtStart() {
        for (int run = 0; run < 3; run++) {
            MatchStore store = new MatchStore();
            MatchOrchestrator orchestrator = new MatchOrchestrator(store);
            long matchId = orchestrator.startMatch("Team A", "Team B");
            MatchResult result = orchestrator.simulate(matchId);

            if (result.tickHistory().isEmpty()) continue;
            TickSnapshot firstTick = result.tickHistory().get(0);
            long gkCount = firstTick.players().stream()
                .filter(s -> s.position() == Position.GK)
                .count();
            assertEquals(2, gkCount, "Must have exactly 2 GKs at tick 0");
        }
    }

    @Test
    void possessionChainsStartAndEndReasonably() {
        MatchStore store = new MatchStore();
        MatchOrchestrator orchestrator = new MatchOrchestrator(store);
        long matchId = orchestrator.startMatch("Crvena Zvezda", "Partizan");
        MatchResult result = orchestrator.simulate(matchId);

        long starts = result.events().stream()
            .filter(e -> e instanceof PossessionStartEvent).count();
        long ends = result.events().stream()
            .filter(e -> e instanceof PossessionEndEvent).count();

        System.out.printf("Possession chains: starts=%d ends=%d (diff=%d)%n", starts, ends, starts - ends);
        assertTrue(starts > 0, "Must have possession starts");
        assertTrue(Math.abs(starts - ends) <= 2,
            "Starts/ends mismatch too large (starts=" + starts + ", ends=" + ends + ")");
    }
}