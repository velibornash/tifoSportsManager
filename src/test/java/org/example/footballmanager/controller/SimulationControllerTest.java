package org.example.footballmanager.controller;

import org.example.footballmanager.engines.MatchEngine;
import org.example.footballmanager.engines.MatchStatisticEngine;
import org.example.footballmanager.model.Competition;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.Season;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.model.User;
import org.example.footballmanager.repository.CompetitionRepository;
import org.example.footballmanager.repository.SeasonRepository;
import org.example.footballmanager.repository.TeamRepository;
import org.example.footballmanager.repository.UserRepository;
import org.example.footballmanager.service.SeasonService;
import org.example.footballmanager.service.SimulationService;
import org.example.footballmanager.service.TrainingProgressionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimulationControllerTest {

    @Mock private CompetitionRepository competitionRepository;
    @Mock private SeasonRepository seasonRepository;
    @Mock private SimulationService simulationService;
    @Mock private MatchEngine matchEngine;
    @Mock private MatchStatisticEngine matchStatisticEngine;
    @Mock private SeasonService seasonService;
    @Mock private TrainingProgressionService trainingProgressionService;
    @Mock private UserRepository userRepository;
    @Mock private TeamRepository teamRepository;

    @InjectMocks private SimulationController simulationController;

    @Test
    void startRealisticDemoShowsPlayoffSummaryDuringWeekNineteen() {
        Competition superLiga = new Competition();
        superLiga.setId(1L);
        superLiga.setName("SuperLiga");

        Competition userLeague = new Competition();
        userLeague.setId(2L);
        userLeague.setName("Prva Liga");

        Team team = new Team();
        team.setId(10L);
        team.setName("OFK Omladinac");
        team.setCompetition(userLeague);

        User user = new User();
        user.setTeam(team);

        Season season = new Season();
        season.setSeasonYear(2026);

        Map<String, Object> summary = Map.of("seasonYear", 2026);

        when(teamRepository.findById(10L)).thenReturn(Optional.of(team));
        when(competitionRepository.findById(1L)).thenReturn(Optional.of(superLiga));
        when(seasonService.getActiveSeasonYear()).thenReturn(2026);
        when(seasonRepository.findBySeasonYear(2026)).thenReturn(Optional.of(season));
        when(seasonService.getCurrentWeek()).thenReturn(SeasonService.PLAYOFF_WEEK);
        when(seasonService.buildPlayoffSummary(superLiga, 2026)).thenReturn(summary);

        ResponseEntity<Map<String, Object>> response = simulationController.startRealisticDemo(user);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("SHOW_PLAYOFF_SUMMARY", response.getBody().get("action"));
        assertSame(summary, response.getBody().get("summary"));
        verify(matchEngine).simulateRestOfMatchDay(superLiga, season, null, null);
        verify(seasonService).advanceWeekAndHandleSeasonTransition(superLiga);
        verify(matchEngine, never()).createMatch(team);
        verify(simulationService, never()).startRealisticSimulation(anyLong());
    }

    @Test
    void startRealisticDemoLaunchesMatchAndCompletesWeekFlow() {
        Competition superLiga = new Competition();
        superLiga.setId(1L);
        superLiga.setName("SuperLiga");

        Team team = new Team();
        team.setId(10L);
        team.setName("OFK Omladinac");
        team.setCompetition(superLiga);

        Team opponent = new Team();
        opponent.setId(11L);
        opponent.setName("Rival");
        opponent.setCompetition(superLiga);

        User user = new User();
        user.setTeam(team);

        Season season = new Season();
        season.setSeasonYear(2026);

        Match match = new Match();
        match.setId(99L);
        match.setHomeTeam(team);
        match.setAwayTeam(opponent);
        match.setCompetition(superLiga);

        when(teamRepository.findById(10L)).thenReturn(Optional.of(team));
        when(competitionRepository.findById(1L)).thenReturn(Optional.of(superLiga));
        when(seasonService.getActiveSeasonYear()).thenReturn(2026);
        when(seasonRepository.findBySeasonYear(2026)).thenReturn(Optional.of(season));
        when(seasonService.getCurrentWeek()).thenReturn(SeasonService.FRIENDLY_WEEK);
        when(matchEngine.createMatch(team)).thenReturn(match);
        when(simulationService.startRealisticSimulation(99L)).thenReturn(CompletableFuture.completedFuture(match));

        ResponseEntity<Map<String, Object>> response = simulationController.startRealisticDemo(user);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("START_MATCH", response.getBody().get("action"));
        assertEquals("99", response.getBody().get("matchId"));
        verify(matchEngine).simulateRestOfMatchDay(superLiga, season, team, opponent);
        verify(matchStatisticEngine).updateLeagueTableForMatchDay(superLiga, season);
        verify(trainingProgressionService).runWeeklyTraining(10L);
        verify(seasonService).advanceWeekAndHandleSeasonTransition(superLiga);
    }

    @Test
    void startRealisticDemoSmokePassCoversPlayoffFriendlyAndNextSeasonKickoff() {
        Competition superLiga = new Competition();
        superLiga.setId(1L);
        superLiga.setName("SuperLiga");

        Competition prvaLiga = new Competition();
        prvaLiga.setId(2L);
        prvaLiga.setName("Prva Liga");

        Team team2026 = new Team();
        team2026.setId(10L);
        team2026.setName("OFK Omladinac");
        team2026.setCompetition(prvaLiga);

        Team team2027 = new Team();
        team2027.setId(10L);
        team2027.setName("OFK Omladinac");
        team2027.setCompetition(superLiga);

        Team friendlyOpponent = new Team();
        friendlyOpponent.setId(11L);
        friendlyOpponent.setName("Friendly Rival");
        friendlyOpponent.setCompetition(prvaLiga);

        Team leagueOpponent = new Team();
        leagueOpponent.setId(12L);
        leagueOpponent.setName("SuperLiga Rival");
        leagueOpponent.setCompetition(superLiga);

        User user = new User();
        user.setTeam(team2026);

        Season season2026 = new Season();
        season2026.setSeasonYear(2026);

        Season season2027 = new Season();
        season2027.setSeasonYear(2027);

        Match friendlyMatch = new Match();
        friendlyMatch.setId(201L);
        friendlyMatch.setHomeTeam(team2026);
        friendlyMatch.setAwayTeam(friendlyOpponent);
        friendlyMatch.setCompetition(prvaLiga);

        Match nextSeasonMatch = new Match();
        nextSeasonMatch.setId(301L);
        nextSeasonMatch.setHomeTeam(team2027);
        nextSeasonMatch.setAwayTeam(leagueOpponent);
        nextSeasonMatch.setCompetition(superLiga);

        when(competitionRepository.findById(1L)).thenReturn(Optional.of(superLiga));
        when(teamRepository.findById(10L)).thenReturn(Optional.of(team2026), Optional.of(team2026), Optional.of(team2027));
        when(seasonService.getActiveSeasonYear()).thenReturn(2026, 2026, 2027);
        when(seasonRepository.findBySeasonYear(2026)).thenReturn(Optional.of(season2026));
        when(seasonRepository.findBySeasonYear(2027)).thenReturn(Optional.of(season2027));
        when(seasonService.getCurrentWeek()).thenReturn(SeasonService.PLAYOFF_WEEK, SeasonService.FRIENDLY_WEEK, 1);
        when(seasonService.buildPlayoffSummary(superLiga, 2026)).thenReturn(Map.of("seasonYear", 2026));
        when(matchEngine.createMatch(team2026)).thenReturn(friendlyMatch);
        when(matchEngine.createMatch(team2027)).thenReturn(nextSeasonMatch);
        when(simulationService.startRealisticSimulation(201L)).thenReturn(CompletableFuture.completedFuture(friendlyMatch));
        when(simulationService.startRealisticSimulation(301L)).thenReturn(CompletableFuture.completedFuture(nextSeasonMatch));

        ResponseEntity<Map<String, Object>> playoffResponse = simulationController.startRealisticDemo(user);
        ResponseEntity<Map<String, Object>> friendlyResponse = simulationController.startRealisticDemo(user);
        ResponseEntity<Map<String, Object>> nextSeasonResponse = simulationController.startRealisticDemo(user);

        assertEquals("SHOW_PLAYOFF_SUMMARY", playoffResponse.getBody().get("action"));
        assertEquals("START_MATCH", friendlyResponse.getBody().get("action"));
        assertEquals("201", friendlyResponse.getBody().get("matchId"));
        assertEquals("START_MATCH", nextSeasonResponse.getBody().get("action"));
        assertEquals("301", nextSeasonResponse.getBody().get("matchId"));

        verify(matchEngine).simulateRestOfMatchDay(superLiga, season2026, null, null);
        verify(matchEngine).simulateRestOfMatchDay(prvaLiga, season2026, team2026, friendlyOpponent);
        verify(matchEngine).simulateRestOfMatchDay(superLiga, season2027, team2027, leagueOpponent);
        verify(matchStatisticEngine).updateLeagueTableForMatchDay(prvaLiga, season2026);
        verify(matchStatisticEngine).updateLeagueTableForMatchDay(superLiga, season2027);
        verify(trainingProgressionService, times(2)).runWeeklyTraining(10L);
        verify(seasonService, times(3)).advanceWeekAndHandleSeasonTransition(superLiga);
    }
}