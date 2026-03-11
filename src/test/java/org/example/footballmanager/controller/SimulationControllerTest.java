package org.example.footballmanager.controller;

import org.example.footballmanager.engines.MatchEngine;
import org.example.footballmanager.engines.MatchStatisticEngine;
import org.example.footballmanager.model.Competition;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.MatchFixture;
import org.example.footballmanager.model.Season;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.model.User;
import org.example.footballmanager.repository.CompetitionRepository;
import org.example.footballmanager.repository.MatchFixtureRepository;
import org.example.footballmanager.repository.MatchRepository;
import org.example.footballmanager.repository.SeasonRepository;
import org.example.footballmanager.repository.TeamRepository;
import org.example.footballmanager.service.SeasonService;
import org.example.footballmanager.service.SimulationService;
import org.example.footballmanager.service.TrainingProgressionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
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
    @Mock private TeamRepository teamRepository;
    @Mock private MatchFixtureRepository matchFixtureRepository;
    @Mock private MatchRepository matchRepository;

    @InjectMocks private SimulationController simulationController;

    @Test
    void startRealisticDemoReturnsWaitingWhenNoFixtureExists() {
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

        when(teamRepository.findById(10L)).thenReturn(Optional.of(team));
        when(competitionRepository.findById(1L)).thenReturn(Optional.of(superLiga));
        when(seasonService.getActiveSeasonYear()).thenReturn(2026);
        when(seasonService.getCurrentWeek()).thenReturn(SeasonService.PLAYOFF_WEEK);
        when(matchRepository.findPreparedMatchesForTeamInRound(2L, 2026, SeasonService.PLAYOFF_WEEK, 10L)).thenReturn(List.of());
        when(matchFixtureRepository.findByCompetitionIdAndSeasonYearAndRoundNumberAndPlayedFalseOrderByMatchDateAsc(2L, 2026, SeasonService.PLAYOFF_WEEK))
                .thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = simulationController.startRealisticDemo(user);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("NO_MATCH_CURRENT_WEEK", response.getBody().get("action"));
        verify(matchEngine, never()).createMatch(team);
        verify(simulationService, never()).startRealisticSimulation(anyLong());
    }

    @Test
    void startRealisticDemoLaunchesOnlyTheUsersMatch() {
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

        Match match = new Match();
        match.setId(99L);
        match.setHomeTeam(team);
        match.setAwayTeam(opponent);
        match.setCompetition(superLiga);

        MatchFixture fixture = new MatchFixture();
        fixture.setHomeTeam(team);
        fixture.setAwayTeam(opponent);

        when(teamRepository.findById(10L)).thenReturn(Optional.of(team));
        when(competitionRepository.findById(1L)).thenReturn(Optional.of(superLiga));
        when(seasonService.getActiveSeasonYear()).thenReturn(2026);
        when(seasonService.getCurrentWeek()).thenReturn(1);
        when(matchRepository.findPreparedMatchesForTeamInRound(1L, 2026, 1, 10L)).thenReturn(List.of());
        when(matchFixtureRepository.findByCompetitionIdAndSeasonYearAndRoundNumberAndPlayedFalseOrderByMatchDateAsc(1L, 2026, 1))
                .thenReturn(List.of(fixture));
        when(matchEngine.createMatch(team)).thenReturn(match);
        when(simulationService.startRealisticSimulation(99L)).thenReturn(CompletableFuture.completedFuture(match));

        ResponseEntity<Map<String, Object>> response = simulationController.startRealisticDemo(user);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("START_MATCH", response.getBody().get("action"));
        assertEquals("99", response.getBody().get("matchId"));
        verify(trainingProgressionService, never()).runWeeklyTraining(10L);
        verify(seasonService, never()).advanceWeekAndHandleSeasonTransition(superLiga);
    }

    @Test
    void simulateCurrentRoundAcrossAllLeaguesExcludesUsersFixtureAndRecalculatesTables() {
        Competition superLiga = new Competition();
        superLiga.setId(1L);
        superLiga.setName("SuperLiga");

        Competition prvaLiga = new Competition();
        prvaLiga.setId(2L);
        prvaLiga.setName("Prva Liga");

        Team team = new Team();
        team.setId(10L);
        team.setName("OFK Omladinac");
        team.setCompetition(prvaLiga);

        Team userOpponent = new Team();
        userOpponent.setId(11L);
        userOpponent.setName("Friendly Rival");
        userOpponent.setCompetition(prvaLiga);

        Team leagueOpponent = new Team();
        leagueOpponent.setId(12L);
        leagueOpponent.setName("SuperLiga Rival");
        leagueOpponent.setCompetition(superLiga);

        User user = new User();
        user.setTeam(team);

        Season season = new Season();
        season.setSeasonYear(2026);

        MatchFixture userFixture = new MatchFixture();
        userFixture.setHomeTeam(team);
        userFixture.setAwayTeam(userOpponent);

        MatchFixture otherPrvaFixture = new MatchFixture();
        otherPrvaFixture.setHomeTeam(userOpponent);
        otherPrvaFixture.setAwayTeam(leagueOpponent);

        MatchFixture superLigaFixture = new MatchFixture();
        superLigaFixture.setHomeTeam(leagueOpponent);
        superLigaFixture.setAwayTeam(team);

        when(teamRepository.findById(10L)).thenReturn(Optional.of(team));
        when(competitionRepository.findById(1L)).thenReturn(Optional.of(superLiga));
        when(seasonService.getActiveSeasonYear()).thenReturn(2026);
        when(seasonService.getCurrentWeek()).thenReturn(1);
        when(seasonService.getSerbianLeaguesInOrder()).thenReturn(List.of(superLiga, prvaLiga));
        when(seasonRepository.findBySeasonYear(2026)).thenReturn(Optional.of(season));
        when(matchRepository.findPreparedMatchesForTeamInRound(2L, 2026, 1, 10L)).thenReturn(List.of());
        when(matchFixtureRepository.findByCompetitionIdAndSeasonYearAndRoundNumberAndPlayedFalseOrderByMatchDateAsc(2L, 2026, 1))
                .thenReturn(List.of(userFixture, otherPrvaFixture), List.of(userFixture, otherPrvaFixture), List.of(userFixture));
        when(matchFixtureRepository.findByCompetitionIdAndSeasonYearAndRoundNumberAndPlayedFalseOrderByMatchDateAsc(1L, 2026, 1))
                .thenReturn(List.of(superLigaFixture), List.of());

        ResponseEntity<Map<String, Object>> response = simulationController.simulateCurrentRoundAcrossAllLeagues(user);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("ROUND_SIMULATED", response.getBody().get("action"));
        assertEquals(2, response.getBody().get("simulatedCount"));
        verify(matchEngine).simulateRestOfMatchDay(prvaLiga, season, team, userOpponent);
        verify(matchEngine).simulateRestOfMatchDay(superLiga, season, null, null);
        verify(matchStatisticEngine).updateLeagueTableForMatchDay(prvaLiga, season);
        verify(matchStatisticEngine).updateLeagueTableForMatchDay(superLiga, season);
    }

    @Test
    void advanceWeekRunsTrainingAfterRoundCompletion() {
        Competition superLiga = new Competition();
        superLiga.setId(1L);
        superLiga.setName("SuperLiga");

        Team team = new Team();
        team.setId(10L);
        team.setName("OFK Omladinac");

        User user = new User();
        user.setTeam(team);

        when(teamRepository.findById(10L)).thenReturn(Optional.of(team));
        when(competitionRepository.findById(1L)).thenReturn(Optional.of(superLiga));
        when(seasonService.getCurrentWeek()).thenReturn(1, 2);
        when(seasonService.getActiveSeasonYear()).thenReturn(2026, 2026);
        when(seasonService.getSerbianLeaguesInOrder()).thenReturn(List.of(superLiga));
        when(matchFixtureRepository.findByCompetitionIdAndSeasonYearAndRoundNumberAndPlayedFalseOrderByMatchDateAsc(1L, 2026, 1))
                .thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = simulationController.advanceWeek(user);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("WEEK_ADVANCED", response.getBody().get("action"));
        verify(trainingProgressionService).runWeeklyTraining(10L);
        verify(seasonService).advanceWeekAndHandleSeasonTransition(superLiga);
    }
}