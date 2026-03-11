package org.example.footballmanager.controller;

import org.example.footballmanager.dto.LeagueMilestonesDTO;
import org.example.footballmanager.dto.MatchDTO;
import org.example.footballmanager.dto.PlayerDTO;
import org.example.footballmanager.dto.TacticsEditorDTO;
import org.example.footballmanager.dto.TacticsEditorSaveRequest;
import org.example.footballmanager.dto.TeamMedicalOverviewDTO;
import org.example.footballmanager.model.Competition;
import org.example.footballmanager.model.CompetitionEntry;
import org.example.footballmanager.model.Lineup;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.MatchFixture;
import org.example.footballmanager.model.MatchPlayerStats;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Position;
import org.example.footballmanager.model.SeasonCompetition;
import org.example.footballmanager.model.Skills;
import org.example.footballmanager.model.Stadium;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.model.User;
import org.example.footballmanager.repository.CompetitionEntryRepository;
import org.example.footballmanager.repository.LineupRepository;
import org.example.footballmanager.repository.MatchFixtureRepository;
import org.example.footballmanager.repository.MatchRepository;
import org.example.footballmanager.repository.MatchPlayerStatsRepository;
import org.example.footballmanager.repository.PlayerRepository;
import org.example.footballmanager.repository.TeamRepository;
import org.example.footballmanager.service.LeagueMilestoneService;
import org.example.footballmanager.service.ScheduleInsightService;
import org.example.footballmanager.service.SeasonService;
import org.example.footballmanager.service.TeamMedicalService;
import org.example.footballmanager.service.TeamTacticsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeamControllerTest {

    @Mock private TeamRepository teamRepository;
    @Mock private PlayerRepository playerRepository;
    @Mock private MatchRepository matchRepository;
    @Mock private MatchFixtureRepository matchFixtureRepository;
    @Mock private LineupRepository lineupRepository;
    @Mock private MatchPlayerStatsRepository matchPlayerStatsRepository;
    @Mock private CompetitionEntryRepository competitionEntryRepository;
    @Mock private LeagueMilestoneService leagueMilestoneService;
    @Mock private ScheduleInsightService scheduleInsightService;
    @Mock private SeasonService seasonService;
    @Mock private TeamMedicalService teamMedicalService;
    @Mock private TeamTacticsService teamTacticsService;

    @InjectMocks private TeamController teamController;

    @Test
    void getLineupTemplateMarksMissingTemplateAsUnsaved() {
        when(lineupRepository.findFirstByTeamIdAndMatchIsNullOrderByIdDesc(1L)).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = teamController.getLineupTemplate(1L);

        assertEquals(200, response.getStatusCode().value());
        assertFalse((Boolean) response.getBody().get("saved"));
        assertEquals("4-4-2", response.getBody().get("formation"));
        assertEquals("BALANCED", response.getBody().get("style"));
        assertEquals(List.of(), response.getBody().get("starterIds"));
        assertEquals(List.of(), response.getBody().get("benchIds"));
    }

    @Test
    void saveLineupTemplatePreservesRequestedStarterAndBenchOrder() {
        Team team = new Team();
        team.setId(1L);
        team.setName("Omladinac");

        List<Player> players = new ArrayList<>();
        for (long id = 1; id <= 18; id++) {
            Player player = new Player();
            player.setId(id);
            player.setName("P" + id);
            player.setRating((int) (80 - id));
            player.setInjured(false);
            players.add(player);
        }

        List<Long> starterIds = List.of(11L, 1L, 7L, 5L, 3L, 9L, 2L, 4L, 6L, 8L, 10L);
        List<Long> benchIds = List.of(18L, 17L, 16L, 15L, 14L, 13L, 12L);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("formation", "4-3-3");
        payload.put("style", "HIGH_PRESS");
        payload.put("starterIds", starterIds);
        payload.put("benchIds", benchIds);

        Lineup existingTemplate = new Lineup();
        existingTemplate.setId(77L);
        existingTemplate.setTeam(team);

        when(teamRepository.findById(1L)).thenReturn(Optional.of(team));
        when(playerRepository.findByTeamId(1L)).thenReturn(players);
        when(lineupRepository.findFirstByTeamIdAndMatchIsNullOrderByIdDesc(1L)).thenReturn(Optional.of(existingTemplate));
        when(lineupRepository.save(any(Lineup.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<Map<String, Object>> response = teamController.saveLineupTemplate(1L, payload);

        ArgumentCaptor<Lineup> captor = ArgumentCaptor.forClass(Lineup.class);
        verify(lineupRepository).save(captor.capture());
        Lineup saved = captor.getValue();

        assertEquals(starterIds, saved.getStartingPlayers().stream().map(Player::getId).toList());
        assertEquals(benchIds, saved.getSubstitutes().stream().map(Player::getId).toList());
        assertEquals("4-3-3", saved.getFormation());
        assertEquals("HIGH_PRESS", saved.getStyle());
        assertTrue((Boolean) response.getBody().get("saved"));
        assertEquals(77L, response.getBody().get("id"));
        assertEquals("HIGH_PRESS", response.getBody().get("style"));
        assertEquals(starterIds, response.getBody().get("starterIds"));
        assertEquals(benchIds, response.getBody().get("benchIds"));
    }

    @Test
    void getPlayersIncludesAppsAndAverageRating() {
        Team team = new Team();
        team.setId(1L);
        team.setName("Omladinac");

        Skills skills = new Skills();
        skills.setGoalkeeper(3);
        skills.setPace(11);
        skills.setStriker(14);
        skills.setPassing(10);
        skills.setTechnique(12);
        skills.setDefender(6);
        skills.setStamina(13);
        skills.setPlaymaker(9);
        skills.setFatigue(2);

        Player player = new Player();
        player.setId(5L);
        player.setName("Marko");
        player.setAge(24);
        player.setPosition(Position.ATT);
        player.setForm(7.2);
        player.setRating(74);
        player.setSkills(skills);
        player.setTeam(team);

        MatchPlayerStats first = new MatchPlayerStats();
        first.setPlayer(player);
        first.setRating(78);
        MatchPlayerStats second = new MatchPlayerStats();
        second.setPlayer(player);
        second.setRating(84);

        when(playerRepository.findByTeamId(1L)).thenReturn(List.of(player));
        when(matchPlayerStatsRepository.findByPlayerIdIn(List.of(5L))).thenReturn(List.of(first, second));

        ResponseEntity<List<PlayerDTO>> response = teamController.getPlayers(1L);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        assertEquals(2, response.getBody().getFirst().getMatchesPlayed());
        assertEquals(8.1, response.getBody().getFirst().getAverageRating10());
    }

    @Test
    void getMatchesHidesUnrevealedResultForAuthenticatedUsersTeam() {
        Team team = new Team();
        team.setId(1L);
        team.setName("OFK Omladinac");

        Team opponent = new Team();
        opponent.setId(2L);
        opponent.setName("FK Rival");

        Match match = new Match();
        match.setId(44L);
        match.setHomeTeam(team);
        match.setAwayTeam(opponent);
        match.setHomeGoals(2);
        match.setAwayGoals(1);
        match.setPlayed(true);
        match.setHomeResultRevealed(false);
        match.setAwayResultRevealed(true);

        User user = new User();
        user.setTeam(team);

        when(matchRepository.findByHomeTeamIdOrAwayTeamIdAndPlayedTrueOrderByMatchDateDesc(1L, 1L)).thenReturn(List.of(match));

        ResponseEntity<List<MatchDTO>> response = teamController.getMatches(1L, user);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().getFirst().isResultHidden());
        assertFalse(response.getBody().getFirst().isResultRevealed());
    }

    @Test
    void getScheduleReturnsUpcomingFixtureWithHeadToHeadSummary() {
        Competition competition = new Competition();
        competition.setId(2L);
        competition.setName("Prva Liga");

        Team team = new Team();
        team.setId(1L);
        team.setName("OFK Omladinac");
        team.setCompetition(competition);

        Team opponent = new Team();
        opponent.setId(2L);
        opponent.setName("FK Rival");
        opponent.setCompetition(competition);

        Stadium stadium = new Stadium();
        stadium.setName("Livadice");
        team.setStadium(stadium);

        MatchFixture fixture = new MatchFixture();
        fixture.setId(90L);
        fixture.setCompetition(competition);
        fixture.setSeasonYear(2026);
        fixture.setRoundNumber(4);
        fixture.setWeekNumber(4);
        fixture.setMatchDate(java.time.LocalDateTime.of(2026, 3, 15, 17, 0));
        fixture.setHomeTeam(team);
        fixture.setAwayTeam(opponent);
        fixture.setPlayed(false);

        Match previousWin = new Match();
        previousWin.setPlayed(true);
        previousWin.setHomeTeam(team);
        previousWin.setAwayTeam(opponent);
        previousWin.setHomeGoals(2);
        previousWin.setAwayGoals(1);
        previousWin.setMatchDate(java.time.LocalDateTime.of(2025, 10, 1, 15, 0));

        Match previousDraw = new Match();
        previousDraw.setPlayed(true);
        previousDraw.setHomeTeam(opponent);
        previousDraw.setAwayTeam(team);
        previousDraw.setHomeGoals(1);
        previousDraw.setAwayGoals(1);
        previousDraw.setMatchDate(java.time.LocalDateTime.of(2025, 8, 11, 15, 0));

        when(teamRepository.findById(1L)).thenReturn(Optional.of(team));
        when(seasonService.getActiveSeasonYear()).thenReturn(2026);
        when(matchFixtureRepository.findTeamScheduleByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(2L, 2026, 1L))
                .thenReturn(List.of(fixture));
        when(matchRepository.findByHomeTeamIdOrAwayTeamId(1L, 1L)).thenReturn(List.of(previousWin, previousDraw));
        when(scheduleInsightService.buildTeamSnapshots(any())).thenReturn(Map.of(
                1L, new ScheduleInsightService.TeamSnapshot(73, 7.4, 2),
                2L, new ScheduleInsightService.TeamSnapshot(68, 6.8, 2)
        ));
        when(scheduleInsightService.buildFixtureInsights(any(Team.class), any(Team.class), any())).thenReturn(
                new ScheduleInsightService.FixtureInsights(
                        73,
                        68,
                        7.4,
                        6.8,
                        new ScheduleInsightService.Prediction(46, 28, 26, 1.72, 1.18, "HOME_WIN", 67, "Home edge · OVR 73:68 · form 7.4:6.8")
                )
        );

        ResponseEntity<List<Map<String, Object>>> response = teamController.getSchedule(1L, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());

        Map<String, Object> row = response.getBody().getFirst();
        assertEquals(90L, row.get("fixtureId"));
        assertEquals("FK Rival", row.get("opponentName"));
        assertEquals("Prva Liga", row.get("competitionName"));
        assertEquals("Livadice", row.get("stadium"));
        assertEquals(73, row.get("homeTeamStrength"));
        assertEquals(68, row.get("awayTeamStrength"));
        assertEquals(7.4, row.get("homeTeamForm"));
        assertEquals(6.8, row.get("awayTeamForm"));

        Map<String, Object> prediction = (Map<String, Object>) row.get("prediction");
        assertEquals(46, prediction.get("homeWinProbability"));
        assertEquals(28, prediction.get("drawProbability"));
        assertEquals(26, prediction.get("awayWinProbability"));
        assertEquals(1.72, prediction.get("expectedHomeGoals"));
        assertEquals(1.18, prediction.get("expectedAwayGoals"));
        assertEquals("HOME_WIN", prediction.get("mostLikelyResult"));

        Map<String, Object> h2h = (Map<String, Object>) row.get("h2h");
        assertEquals(2, h2h.get("played"));
        assertEquals(1, h2h.get("wins"));
        assertEquals(1, h2h.get("draws"));
        assertEquals(0, h2h.get("losses"));
        assertEquals("H2H 1-1-0 · Goals 3:2", h2h.get("summary"));
        assertEquals("Last meeting: 2:1 vs FK Rival (at home)", h2h.get("lastMeetingSummary"));
    }

    @Test
    void getScheduleEnsuresFriendlyWeekFixturesForActiveSeason() {
        Competition competition = new Competition();
        competition.setId(2L);
        competition.setName("Prva Liga");

        Team team = new Team();
        team.setId(1L);
        team.setName("OFK Omladinac");
        team.setCompetition(competition);

        Team opponent = new Team();
        opponent.setId(2L);
        opponent.setName("FK Rival");
        opponent.setCompetition(competition);

        MatchFixture friendly = new MatchFixture();
        friendly.setId(190L);
        friendly.setCompetition(competition);
        friendly.setSeasonYear(2026);
        friendly.setRoundNumber(SeasonService.FRIENDLY_WEEK);
        friendly.setWeekNumber(SeasonService.FRIENDLY_WEEK);
        friendly.setMatchDate(java.time.LocalDateTime.of(2026, 6, 1, 18, 0));
        friendly.setHomeTeam(team);
        friendly.setAwayTeam(opponent);
        friendly.setPlayed(false);

        when(teamRepository.findById(1L)).thenReturn(Optional.of(team));
        when(seasonService.getActiveSeasonYear()).thenReturn(2026);
        when(seasonService.getCurrentWeek()).thenReturn(SeasonService.FRIENDLY_WEEK);
        when(matchFixtureRepository.findTeamScheduleByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(2L, 2026, 1L))
                .thenReturn(List.of(friendly));
        when(matchRepository.findByHomeTeamIdOrAwayTeamId(1L, 1L)).thenReturn(List.of());
        when(scheduleInsightService.buildTeamSnapshots(any())).thenReturn(Map.of());
        when(scheduleInsightService.buildFixtureInsights(any(Team.class), any(Team.class), any())).thenReturn(
                new ScheduleInsightService.FixtureInsights(
                        70,
                        66,
                        6.5,
                        6.1,
                        new ScheduleInsightService.Prediction(44, 30, 26, 1.61, 1.09, "HOME_WIN", 61, "Home edge · OVR 70:66 · form 6.5:6.1")
                )
        );

        ResponseEntity<List<Map<String, Object>>> response = teamController.getSchedule(1L, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        verify(seasonService).ensureFriendlyWeekFixtures(competition, 2026);
    }

    @Test
    void getScheduleFallsBackToSeasonFixturesWhenTeamCompetitionIsMissing() {
        Competition fixtureCompetition = new Competition();
        fixtureCompetition.setId(2L);
        fixtureCompetition.setName("Prva Liga");

        Team team = new Team();
        team.setId(1L);
        team.setName("OFK Omladinac");

        Team opponent = new Team();
        opponent.setId(2L);
        opponent.setName("FK Rival");
        opponent.setCompetition(fixtureCompetition);

        MatchFixture fixture = new MatchFixture();
        fixture.setId(91L);
        fixture.setCompetition(fixtureCompetition);
        fixture.setSeasonYear(2026);
        fixture.setRoundNumber(5);
        fixture.setWeekNumber(5);
        fixture.setMatchDate(java.time.LocalDateTime.of(2026, 3, 21, 15, 0));
        fixture.setHomeTeam(team);
        fixture.setAwayTeam(opponent);
        fixture.setPlayed(false);

        when(teamRepository.findById(1L)).thenReturn(Optional.of(team));
        when(seasonService.getActiveSeasonYear()).thenReturn(2026);
        when(competitionEntryRepository.findByTeam(team)).thenReturn(List.of());
        when(matchFixtureRepository.findTeamScheduleBySeasonYearOrderByRoundNumberAscMatchDateAsc(2026, 1L))
                .thenReturn(List.of(fixture));
        when(matchRepository.findByHomeTeamIdOrAwayTeamId(1L, 1L)).thenReturn(List.of());
        when(scheduleInsightService.buildTeamSnapshots(any())).thenReturn(Map.of());
        when(scheduleInsightService.buildFixtureInsights(eq(team), eq(opponent), any())).thenReturn(
                new ScheduleInsightService.FixtureInsights(
                        71,
                        66,
                        6.9,
                        6.3,
                        new ScheduleInsightService.Prediction(45, 29, 26, 1.54, 1.04, "HOME_WIN", 63, "Home edge · OVR 71:66 · form 6.9:6.3")
                )
        );

        ResponseEntity<List<Map<String, Object>>> response = teamController.getSchedule(1L, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        assertEquals(91L, response.getBody().getFirst().get("fixtureId"));
        assertEquals("Prva Liga", response.getBody().getFirst().get("competitionName"));
        verify(matchFixtureRepository).findTeamScheduleBySeasonYearOrderByRoundNumberAscMatchDateAsc(2026, 1L);
    }

    @Test
    void getScheduleInfersCompetitionFromEntryWhenTeamCompetitionIsMissing() {
        Competition inferredCompetition = new Competition();
        inferredCompetition.setId(7L);
        inferredCompetition.setName("Serbian Superliga");

        SeasonCompetition seasonCompetition = new SeasonCompetition();
        seasonCompetition.setSeasonYear(2026);
        seasonCompetition.setCompetition(inferredCompetition);

        Team team = new Team();
        team.setId(1L);
        team.setName("OFK Omladinac");

        Team opponent = new Team();
        opponent.setId(3L);
        opponent.setName("FK Radnik");
        opponent.setCompetition(inferredCompetition);

        CompetitionEntry entry = new CompetitionEntry();
        entry.setTeam(team);
        entry.setSeasonCompetition(seasonCompetition);

        MatchFixture fixture = new MatchFixture();
        fixture.setId(301L);
        fixture.setCompetition(inferredCompetition);
        fixture.setSeasonYear(2026);
        fixture.setRoundNumber(2);
        fixture.setWeekNumber(2);
        fixture.setMatchDate(java.time.LocalDateTime.of(2026, 3, 17, 18, 0));
        fixture.setHomeTeam(team);
        fixture.setAwayTeam(opponent);
        fixture.setPlayed(false);

        when(teamRepository.findById(1L)).thenReturn(Optional.of(team));
        when(seasonService.getActiveSeasonYear()).thenReturn(2026);
        when(competitionEntryRepository.findByTeam(team)).thenReturn(List.of(entry));
        when(matchFixtureRepository.findTeamScheduleByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(7L, 2026, 1L))
                .thenReturn(List.of(fixture));
        when(matchRepository.findByHomeTeamIdOrAwayTeamId(1L, 1L)).thenReturn(List.of());
        when(scheduleInsightService.buildTeamSnapshots(any())).thenReturn(Map.of());
        when(scheduleInsightService.buildFixtureInsights(eq(team), eq(opponent), any())).thenReturn(
                new ScheduleInsightService.FixtureInsights(
                        72,
                        69,
                        7.0,
                        6.6,
                        new ScheduleInsightService.Prediction(43, 30, 27, 1.48, 1.12, "HOME_WIN", 60, "Home edge · OVR 72:69 · form 7.0:6.6")
                )
        );

        ResponseEntity<List<Map<String, Object>>> response = teamController.getSchedule(1L, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        assertEquals(301L, response.getBody().getFirst().get("fixtureId"));
        assertEquals("Serbian Superliga", response.getBody().getFirst().get("competitionName"));
        verify(matchFixtureRepository).findTeamScheduleByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(7L, 2026, 1L);
    }

    @Test
    void getTeamMilestonesUsesActiveSeasonFallback() {
        Team team = new Team();
        team.setId(1L);
        team.setName("Omladinac");

        LeagueMilestonesDTO dto = LeagueMilestonesDTO.builder()
                .seasonYear(2026)
                .build();

        when(teamRepository.findById(1L)).thenReturn(Optional.of(team));
        when(seasonService.getActiveSeasonYear()).thenReturn(2026);
        when(leagueMilestoneService.buildTeamMilestones(team, 2026)).thenReturn(dto);

        ResponseEntity<LeagueMilestonesDTO> response = teamController.getTeamMilestones(1L, null);

        assertEquals(200, response.getStatusCode().value());
        assertSame(dto, response.getBody());
        verify(seasonService).getActiveSeasonYear();
        verify(leagueMilestoneService).buildTeamMilestones(team, 2026);
    }

    @Test
    void getMedicalOverviewDelegatesToMedicalService() {
        TeamMedicalOverviewDTO dto = new TeamMedicalOverviewDTO();
        dto.setTeamId(1L);
        dto.setTeamName("Omladinac");

        when(teamMedicalService.buildOverview(1L)).thenReturn(dto);

        ResponseEntity<TeamMedicalOverviewDTO> response = teamController.getMedicalOverview(1L);

        assertEquals(200, response.getStatusCode().value());
        assertSame(dto, response.getBody());
        verify(teamMedicalService).buildOverview(1L);
    }

    @Test
    void applyMedicalRecoveryDelegatesToMedicalService() {
        TeamMedicalOverviewDTO dto = new TeamMedicalOverviewDTO();
        dto.setTeamId(1L);
        dto.setRehabCount(2);

        when(teamMedicalService.applyRecovery(1L, 5L)).thenReturn(dto);

        ResponseEntity<TeamMedicalOverviewDTO> response = teamController.applyMedicalRecovery(1L, 5L);

        assertEquals(200, response.getStatusCode().value());
        assertSame(dto, response.getBody());
        verify(teamMedicalService).applyRecovery(1L, 5L);
    }

    @Test
    void getTacticsEditorDelegatesToTacticsService() {
        TacticsEditorDTO dto = new TacticsEditorDTO();
        dto.setTeamId(1L);
        dto.setFormation("4-3-3");

        when(teamTacticsService.getTacticsEditor(1L, "4-3-3")).thenReturn(dto);

        ResponseEntity<TacticsEditorDTO> response = teamController.getTacticsEditor(1L, "4-3-3");

        assertEquals(200, response.getStatusCode().value());
        assertSame(dto, response.getBody());
        verify(teamTacticsService).getTacticsEditor(1L, "4-3-3");
    }

    @Test
    void saveTacticsEditorDelegatesToTacticsService() {
        TacticsEditorSaveRequest request = new TacticsEditorSaveRequest();
        request.setFormation("4-2-3-1");

        TacticsEditorDTO dto = new TacticsEditorDTO();
        dto.setTeamId(1L);
        dto.setFormation("4-2-3-1");

        when(teamTacticsService.saveTacticsEditor(1L, request)).thenReturn(dto);

        ResponseEntity<TacticsEditorDTO> response = teamController.saveTacticsEditor(1L, request);

        assertEquals(200, response.getStatusCode().value());
        assertSame(dto, response.getBody());
        verify(teamTacticsService).saveTacticsEditor(1L, request);
    }
}