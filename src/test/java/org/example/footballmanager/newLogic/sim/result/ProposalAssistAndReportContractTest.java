package org.example.footballmanager.newLogic.sim.result;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.footballmanager.newLogic.dto.MatchEventFlatDTO;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.service.MatchDetailService;
import org.example.footballmanager.newLogic.sim.SimMatchRunner;
import org.example.footballmanager.newLogic.sim.engine.BallResultHandler;
import org.example.footballmanager.newLogic.sim.engine.MatchOrchestrator;
import org.example.footballmanager.newLogic.sim.model.BallStepResult;
import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.model.PlayerSkills;
import org.example.footballmanager.newLogic.sim.model.Position;
import org.example.footballmanager.newLogic.sim.recording.MatchEvent;
import org.example.footballmanager.newLogic.sim.recording.MatchRecorder;
import org.example.footballmanager.newLogic.sim.restarts.RestartManager;
import org.example.footballmanager.newLogic.sim.util.SimulationRandom;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProposalAssistAndReportContractTest {

    @Test
    void completedPassProducesExactlyOneAssistForTheScorer() {
        MatchState state = new MatchState();
        Player passer = player("A", "HOME", "STL");
        Player scorer = player("B", "HOME", "STR");
        state.getPlayers().add(passer);
        state.getPlayers().add(scorer);

        state.beginPass(passer, scorer);
        assertEquals("A", state.completePass(scorer));
        assertEquals("A", state.assistIdFor(scorer));
        assertEquals("A", state.assistNameFor(scorer));

        state.clearPassContext();
        assertEquals(null, state.assistIdFor(scorer));
    }

    @Test
    void failedOrGoalkeeperPassCannotProduceAnAssist() {
        MatchState state = new MatchState();
        Player passer = player("A", "HOME", "GK");
        Player receiver = player("B", "HOME", "STR");
        state.getPlayers().add(passer);
        state.getPlayers().add(receiver);

        state.beginPass(passer, receiver);
        state.completePass(player("C", "HOME", "STR"));
        assertEquals(null, state.assistIdFor(receiver));

        state.beginPass(passer, receiver);
        state.completePass(receiver);
        assertEquals(null, state.assistIdFor(receiver));
    }

    @Test
    void stoppedPassCannotLeaveAnAssistForALaterGoal() {
        MatchState state = new MatchState();
        Player passer = player("A", "HOME", "STL");
        Player scorer = player("B", "HOME", "STR");
        state.getPlayers().add(passer);
        state.getPlayers().add(scorer);
        state.beginPass(passer, scorer);
        state.completePass(scorer);
        assertNotNull(state.assistIdFor(scorer));

        BallResultHandler handler = new BallResultHandler(
                state, new MatchRecorder(), new ProposalStatsCollector("Home", "Away"),
                new RestartManager());
        handler.handle(BallStepResult.stopped());

        assertNull(state.assistIdFor(scorer));

        state.beginPass(passer, scorer);
        state.setPendingReceiver(scorer);
        handler.handle(BallStepResult.stopped());
        assertEquals("A", state.getPendingPasserId());
    }

    @Test
    void reportJsonCarriesGoalAssistCardPenaltyAndVarMetadata() throws Exception {
        MatchState state = new MatchState();
        Player scorer = player("B", "HOME", "STR");
        Player assist = player("A", "HOME", "STL");
        state.getPlayers().add(scorer);
        state.getPlayers().add(assist);
        state.beginPass(assist, scorer);
        state.completePass(scorer);

        MatchRecorder recorder = new MatchRecorder();
        recorder.appendEvent(40, "GOAL", "Goal", "HOME", scorer, null,
                "A", "Assister", 1, 0, null, null, null, null, null, null);
        recorder.appendEvent(80, "YELLOW_CARD", "Yellow", "AWAY",
                player("C", "AWAY", "DCL"), null, null, null, null, null,
                "YELLOW", null, null, null, null, null);
        recorder.appendEvent(120, "PENALTY_AWARDED", "Penalty", "HOME",
                scorer, assist, null, null, null, null, null, true,
                "B", "B", null, null);
        recorder.appendEvent(160, "VAR", "VAR confirmed", "HOME",
                scorer, null, null, null, null, null, null, null, null, null,
                "PENALTY", "PENALTY_CONFIRMED");

        ProposalMatchOutcome.EventEntry goal = entry(recorder.getEvents().get(0));
        ProposalMatchOutcome.EventEntry card = entry(recorder.getEvents().get(1));
        ProposalMatchOutcome.EventEntry penalty = entry(recorder.getEvents().get(2));
        ProposalMatchOutcome.EventEntry var = entry(recorder.getEvents().get(3));
        ProposalMatchOutcome outcome = outcome(goal, card, penalty, var);

        JsonNode json = new ObjectMapper().readTree(SimReportMapper.eventJson(new ObjectMapper(), outcome));
        assertEquals("Assister", json.get(0).get("assistantName").asText());
        assertEquals(1, json.get(0).get("homeScoreAfter").asInt());
        assertEquals("YELLOW", json.get(1).get("cardType").asText());
        assertTrue(json.get(2).get("penaltyFoul").asBoolean());
        assertEquals("PENALTY_CONFIRMED", json.get(3).get("varDecision").asText());
        assertFalse(json.get(0).has("assistantId") && json.get(0).get("assistantId").isNull());
    }

    @Test
    void sameSeedReproducesTheSameMatchMetrics() {
        SimulationRandom.seed(778L);
        MatchOrchestrator first = SimMatchRunner.run("Home FC", "Away United", 600);
        SimulationRandom.seed(778L);
        MatchOrchestrator second = SimMatchRunner.run("Home FC", "Away United", 600);

        assertEquals(first.getState().getHomeGoals(), second.getState().getHomeGoals());
        assertEquals(first.getState().getAwayGoals(), second.getState().getAwayGoals());
        assertEquals(first.getState().getPassAttempts(), second.getState().getPassAttempts());
        assertEquals(first.getState().getPassesCompleted(), second.getState().getPassesCompleted());
        assertEquals(first.getRecorder().getEvents().size(), second.getRecorder().getEvents().size());
        assertNotEquals(first.getState().getMatchId(), second.getState().getMatchId());
    }

    @Test
    void possessionChainsHaveStableIdsAndCompletedPassCounts() {
        ProposalStatsCollector stats = new ProposalStatsCollector("Home", "Away");
        stats.onPossessionTick("HOME");
        stats.onPossessionTick("HOME");
        stats.onPassCompleted("HOME", null, null);
        stats.onPassCompleted("HOME", null, null);
        stats.onPossessionTick("AWAY");
        stats.closePossessionChains();

        List<ProposalStatsCollector.PossessionChain> chains = stats.getPossessionChains();
        assertEquals(2, chains.size());
        assertEquals(1L, chains.get(0).chainId());
        assertEquals("HOME", chains.get(0).team());
        assertEquals(2, chains.get(0).passCount());
        assertEquals(2L, chains.get(1).chainId());
        assertEquals("AWAY", chains.get(1).team());
        assertEquals(0, chains.get(1).passCount());
    }

    @Test
    void detailMappingUsesEventTypesAndDoesNotReportOrdinaryEventsAsInjuries() {
        Team home = new Team();
        home.setName("Home FC");
        Team away = new Team();
        away.setName("Away United");
        Match match = new Match();
        match.setHomeTeam(home);
        match.setAwayTeam(away);
        match.setHomeGoals(1);
        match.setAwayGoals(0);
        match.setEventJson("""
                [
                  {"type":"RECEIVE","teamSide":"HOME","playerName":"Receiver","minute":1},
                  {"type":"GOAL","teamSide":"HOME","playerName":"Scorer","scorerName":"Scorer","assistantName":"Assister","homeScoreAfter":1,"awayScoreAfter":0,"minute":2},
                  {"type":"YELLOW_CARD","teamSide":"AWAY","playerName":"Defender","cardType":"YELLOW","minute":3},
                  {"type":"PENALTY_AWARDED","teamSide":"HOME","takerName":"Taker","playerName":"Taker","minute":4},
                  {"type":"INJURY","teamSide":"HOME","playerName":"Injured","minute":5}
                ]
                """);

        MatchRepository repository = mock(MatchRepository.class);
        when(repository.findById(9L)).thenReturn(Optional.of(match));
        MatchDetailService service = new MatchDetailService(repository, new ObjectMapper());

        List<MatchEventFlatDTO> events = service.getMatchEventsFlat(9L);

        assertEquals(List.of("GoalEvent", "YellowCardEvent", "PenaltyEvent", "InjuryEvent"),
                events.stream().map(MatchEventFlatDTO::getEventType).toList());
        assertEquals("Assister", events.get(0).getAssistant());
        assertEquals("1-0", events.get(0).getScoreAfterGoal());
        assertEquals("Taker", events.get(2).getPenaltyTaker());
        assertEquals(4, events.size());
    }

    private static ProposalMatchOutcome.EventEntry entry(MatchEvent event) {
        return new ProposalMatchOutcome.EventEntry(
                event.getTick(), (int) (event.getTick() / 40), event.getType(), event.getTeam(),
                event.getPlayerId(), event.getPlayerName(), event.getTargetPlayerId(), event.getDescription(),
                event.getAssistantId(), event.getAssistantName(), event.getHomeScoreAfter(), event.getAwayScoreAfter(),
                event.getCardType(), event.getPenaltyFoul(), event.getTakerId(), event.getTakerName(),
                event.getVarType(), event.getVarDecision());
    }

    private static ProposalMatchOutcome outcome(ProposalMatchOutcome.EventEntry... events) {
        return new ProposalMatchOutcome(
                "Home", "Away", 1, 0, 160, 4, "4-4-2", "4-4-2", 50, 50, 1, 0,
                null, null, List.of(), List.of(events), null, null, null);
    }

    private static Player player(String id, String team, String role) {
        Position position = new Position(4.5, 4.0);
        return new Player(id, id, team, role, position, position, PlayerSkills.neutral());
    }
}
