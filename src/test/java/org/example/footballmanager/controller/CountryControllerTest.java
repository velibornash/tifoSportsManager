package org.example.footballmanager.controller;

import org.example.footballmanager.model.Competition;
import org.example.footballmanager.model.CompetitionEntry;
import org.example.footballmanager.model.MatchFixture;
import org.example.footballmanager.model.SeasonCompetition;
import org.example.footballmanager.model.Stadium;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.repository.CompetitionEntryRepository;
import org.example.footballmanager.repository.CompetitionRepository;
import org.example.footballmanager.repository.CountryRepository;
import org.example.footballmanager.repository.MatchFixtureRepository;
import org.example.footballmanager.repository.MatchRepository;
import org.example.footballmanager.repository.PlayerRepository;
import org.example.footballmanager.repository.SeasonCompetitionRepository;
import org.example.footballmanager.repository.SeasonRepository;
import org.example.footballmanager.repository.TeamRepository;
import org.example.footballmanager.service.ScheduleInsightService;
import org.example.footballmanager.service.SeasonService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CountryControllerTest {

    @Mock private CountryRepository countryRepository;
    @Mock private CompetitionRepository competitionRepository;
    @Mock private CompetitionEntryRepository competitionEntryRepository;
    @Mock private TeamRepository teamRepository;
    @Mock private PlayerRepository playerRepository;
    @Mock private SeasonCompetitionRepository seasonCompetitionRepository;
    @Mock private MatchRepository matchRepository;
    @Mock private MatchFixtureRepository matchFixtureRepository;
    @Mock private SeasonRepository seasonRepository;
    @Mock private ScheduleInsightService scheduleInsightService;
    @Mock private SeasonService seasonService;

    @InjectMocks private CountryController countryController;

    @Test
    void getTeamsUsesRequestedSeasonAndReturnsSafePayload() {
        Competition league = new Competition();
        league.setId(1L);
        league.setName("Superliga");

        SeasonCompetition seasonCompetition = new SeasonCompetition();
        seasonCompetition.setCompetition(league);
        seasonCompetition.setSeasonYear(2027);

        Team home = new Team();
        home.setId(10L);
        home.setName("OFK Omladinac");

        Team away = new Team();
        away.setId(20L);
        away.setName("FK Rival");

        CompetitionEntry homeEntry = new CompetitionEntry();
        homeEntry.setSeasonCompetition(seasonCompetition);
        homeEntry.setTeam(home);
        homeEntry.setPosition(1);

        CompetitionEntry awayEntry = new CompetitionEntry();
        awayEntry.setSeasonCompetition(seasonCompetition);
        awayEntry.setTeam(away);
        awayEntry.setPosition(2);

        when(competitionRepository.findById(1L)).thenReturn(Optional.of(league));
        when(seasonCompetitionRepository.findByCompetitionAndSeasonYear(league, 2027)).thenReturn(Optional.of(seasonCompetition));
        when(competitionEntryRepository.findBySeasonCompetition(seasonCompetition)).thenReturn(List.of(homeEntry, awayEntry));

        List<Map<String, Object>> response = countryController.getTeams(1L, 2027);

        assertEquals(2, response.size());
        assertEquals(10L, response.get(0).get("id"));
        assertEquals("OFK Omladinac", response.get(0).get("name"));
        assertEquals(20L, response.get(1).get("id"));
        assertEquals("FK Rival", response.get(1).get("name"));
        verify(seasonService).ensureEntriesForSeasonCompetition(league, 2027);
        verify(competitionEntryRepository).findBySeasonCompetition(seasonCompetition);
    }

    @Test
    void getLeagueScheduleIncludesStrengthPredictionAndTeamIds() {
        Competition league = new Competition();
        league.setId(1L);
        league.setName("Superliga");

        Team home = new Team();
        home.setId(10L);
        home.setName("OFK Omladinac");
        Stadium stadium = new Stadium();
        stadium.setName("Livadice");
        home.setStadium(stadium);

        Team away = new Team();
        away.setId(20L);
        away.setName("FK Rival");

        MatchFixture fixture = new MatchFixture();
        fixture.setId(77L);
        fixture.setCompetition(league);
        fixture.setSeasonYear(2026);
        fixture.setRoundNumber(3);
        fixture.setWeekNumber(3);
        fixture.setMatchDate(LocalDateTime.of(2026, 3, 1, 16, 0));
        fixture.setHomeTeam(home);
        fixture.setAwayTeam(away);
        fixture.setPlayed(false);

        when(competitionRepository.findById(1L)).thenReturn(Optional.of(league));
        when(seasonService.getActiveSeasonYear()).thenReturn(2026);
        when(matchFixtureRepository.findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(1L, 2026))
                .thenReturn(List.of(fixture));
        when(scheduleInsightService.buildTeamSnapshots(anyCollection())).thenReturn(Map.of(
                10L, new ScheduleInsightService.TeamSnapshot(74, 7.2, 4),
                20L, new ScheduleInsightService.TeamSnapshot(69, 6.6, 4)
        ));
        when(scheduleInsightService.buildFixtureInsights(any(Team.class), any(Team.class), any())).thenReturn(
                new ScheduleInsightService.FixtureInsights(
                        74,
                        69,
                        7.2,
                        6.6,
                        new ScheduleInsightService.Prediction(47, 27, 26, 1.79, 1.14, "HOME_WIN", 66, "Home edge · OVR 74:69 · form 7.2:6.6")
                )
        );

        List<Map<String, Object>> response = countryController.getLeagueSchedule(1L, null);

        assertEquals(1, response.size());
        Map<String, Object> row = response.getFirst();
        assertEquals(77L, row.get("fixtureId"));
        assertEquals(10L, row.get("homeTeamId"));
        assertEquals(20L, row.get("awayTeamId"));
        assertEquals("Superliga", row.get("competitionName"));
        assertEquals("Livadice", row.get("stadium"));
        assertEquals(74, row.get("homeTeamStrength"));
        assertEquals(69, row.get("awayTeamStrength"));

        Map<String, Object> prediction = (Map<String, Object>) row.get("prediction");
        assertEquals(47, prediction.get("homeWinProbability"));
        assertEquals(27, prediction.get("drawProbability"));
        assertEquals(26, prediction.get("awayWinProbability"));
        assertEquals(1.79, prediction.get("expectedHomeGoals"));
        assertEquals(1.14, prediction.get("expectedAwayGoals"));
    }

    @Test
    void getLeagueSeasonSummaryUsesRequestedSeason() {
        Competition league = new Competition();
        league.setId(1L);
        league.setName("Superliga");

        Map<String, Object> summary = Map.of(
                "seasonYear", 2026,
                "directPromotions", List.of(Map.of("team", "Jedinstvo", "fromLeague", "Prva liga A")),
                "directRelegations", List.of(Map.of("team", "Rival", "toLeague", "Prva liga B")),
                "playoffResults", List.of(Map.of("homeTeam", "Superliga 7", "awayTeam", "Runner-up", "homeGoals", 1, "awayGoals", 0, "winner", "Superliga 7"))
        );

        when(competitionRepository.findById(1L)).thenReturn(Optional.of(league));
        when(seasonService.buildPlayoffSummary(league, 2026)).thenReturn(summary);

        Map<String, Object> response = countryController.getLeagueSeasonSummary(1L, 2026);

        assertEquals(summary, response);
        verify(seasonService).buildPlayoffSummary(league, 2026);
    }
}