package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.dto.training.TrainingWeekReportDTO;
import org.example.footballmanager.newLogic.exception.ApiException;
import org.example.footballmanager.newLogic.model.GameClock;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.model.Skills;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.TrainingWeekReport;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.repository.TeamTrainingSetupRepository;
import org.example.footballmanager.newLogic.repository.TrainingWeekReportRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sprint 0.3 — weekly training idempotency.
 *
 * <p>The bug: runWeeklyTraining had no guard. The TrainingWeekReport row was found-or-new and
 * overwritten, but growth was re-applied on every call, so the UI's "Run Weekly Training" button
 * granted unlimited skill points for free.
 *
 * <p>Also covers the interaction that a naive guard would get wrong: the season-advance path also
 * calls training, and a manager can legitimately advance the week after training manually. That
 * path must be a no-op, not a 409, or the week becomes impossible to advance.
 */
class TrainingProgressionIdempotencyTest {

    private static final long TEAM_ID = 42L;
    private static final int SEASON = 2;
    private static final int WEEK = 7;

    private TrainingWeekReportRepository reportRepository;
    private PlayerRepository playerRepository;
    private TeamRepository teamRepository;
    private TeamTrainingSetupRepository setupRepository;
    private SeasonService seasonService;
    private TrainingProgressionService service;

    private Team team;
    private Player player;

    @BeforeEach
    void setUp() {
        reportRepository = mock(TrainingWeekReportRepository.class);
        playerRepository = mock(PlayerRepository.class);
        teamRepository = mock(TeamRepository.class);
        setupRepository = mock(TeamTrainingSetupRepository.class);
        seasonService = mock(SeasonService.class);

        ObjectMapper mapper = new ObjectMapper();
        service = new TrainingProgressionService(
                teamRepository, playerRepository, setupRepository,
                reportRepository,
                // Real service, mocked inputs: the training percentage is not stubbed here, because
                // the point of this test is that growth still happens and is still idempotent.
                new TrainingPercentService(
                        mock(org.example.footballmanager.newLogic.repository.StaffMemberRepository.class),
                        mock(org.example.footballmanager.newLogic.repository.MatchPlayerStatsRepository.class)),
                new TrainingIntensityService(
                        mock(org.example.footballmanager.newLogic.repository.PlayerTrainingIntensityRepository.class),
                        mock(org.example.footballmanager.newLogic.repository.PlayerRepository.class),
                        mock(org.example.footballmanager.newLogic.repository.TeamRepository.class)),
                mock(SquadEnvironmentService.class),
                // The morale/personality growth factor (Sprint 5.3). A real MoraleService, not a mock:
                // the point of wiring it in is that it is now consumed, and a mock would let this
                // regress to an unwired field with no test noticing.
                new MoraleService(
                        mock(org.example.footballmanager.newLogic.repository.PlayerRepository.class),
                        mock(org.example.footballmanager.newLogic.repository.MatchPlayerStatsRepository.class)),
                seasonService, mapper);

        GameClock clock = new GameClock();
        clock.setId(1L);
        clock.setCurrentSeason(SEASON);
        clock.setCurrentWeek(WEEK);
        when(seasonService.getOrCreateClock()).thenReturn(clock);

        team = new Team();
        team.setId(TEAM_ID);
        team.setName("Test FC");
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team));

        player = new Player();
        player.setId(100L);
        player.setName("Test Player");
        player.setTeam(team);
        player.setAge(19);
        player.setTalent(8.0);
        Skills skills = new Skills();
        skills.setPassing(10);
        skills.setStriker(10);
        skills.initializeExactFromVisibleIfNeeded();
        player.setSkills(skills);
        when(playerRepository.findByTeamId(TEAM_ID)).thenReturn(List.of(player));
        when(playerRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(setupRepository.findByTeamIdAndSeasonNumberAndWeekNumber(anyLong(), anyInt(), anyInt()))
                .thenReturn(Optional.empty());
        // createDefaultSetup ends with save(); a null return would NPE the growth loop.
        when(setupRepository.save(any(org.example.footballmanager.newLogic.model.TeamTrainingSetup.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(reportRepository.save(any(TrainingWeekReport.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void existingReport() throws Exception {
        TrainingWeekReportDTO stored = new TrainingWeekReportDTO();
        stored.setTeamId(TEAM_ID);
        stored.setSeasonNumber(SEASON);
        stored.setWeekNumber(WEEK);
        stored.setPlayers(new ArrayList<>());
        TrainingWeekReport row = new TrainingWeekReport();
        row.setId(555L);
        row.setTeam(team);
        row.setSeasonNumber(SEASON);
        row.setWeekNumber(WEEK);
        row.setReportJson(new ObjectMapper().writeValueAsString(stored));
        when(reportRepository.findByTeamIdAndSeasonNumberAndWeekNumber(TEAM_ID, SEASON, WEEK))
                .thenReturn(Optional.of(row));
    }

    // ------------------------------------------------------------ the exploit

    @Test
    @DisplayName("S0.3: running training twice in one week is rejected with 409")
    void secondRunInSameWeekIsRejected() throws Exception {
        existingReport();

        ApiException ex = assertThrows(ApiException.class, () -> service.runWeeklyTraining(TEAM_ID));

        assertEquals("TRAINING_ALREADY_RUN", ex.getCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    @DisplayName("S0.3: the rejected second run applies no growth and writes nothing")
    void rejectedRunAppliesNoGrowth() throws Exception {
        existingReport();
        double before = player.getSkills().getExact(SkillName.PASSING);

        assertThrows(ApiException.class, () -> service.runWeeklyTraining(TEAM_ID));

        assertEquals(before, player.getSkills().getExact(SkillName.PASSING), 0.0001,
                "skill value must be unchanged by a rejected run");
        org.mockito.Mockito.verify(playerRepository, org.mockito.Mockito.never()).saveAll(any());
    }

    // ------------------------------------------------------------ the week-advance path must not break

    @Test
    @DisplayName("S0.3: week advance after a manual run is a no-op, not an error")
    void weekAdvanceAfterManualRunIsANoOp() throws Exception {
        existingReport();

        Optional<TrainingWeekReportDTO> result = service.runWeeklyTrainingIfDue(TEAM_ID);

        assertTrue(result.isPresent(), "advance path must receive the stored report");
        assertEquals(TEAM_ID, result.get().getTeamId());
        assertEquals(SEASON, result.get().getSeasonNumber());
        assertEquals(WEEK, result.get().getWeekNumber());
        org.mockito.Mockito.verify(playerRepository, org.mockito.Mockito.never()).saveAll(any());
    }

    @Test
    @DisplayName("S0.3: week advance runs training when the week has not been trained yet")
    void weekAdvanceRunsTrainingWhenNotYetDone() {
        when(reportRepository.findByTeamIdAndSeasonNumberAndWeekNumber(TEAM_ID, SEASON, WEEK))
                .thenReturn(Optional.empty());

        Optional<TrainingWeekReportDTO> result = service.runWeeklyTrainingIfDue(TEAM_ID);

        assertTrue(result.isPresent());
        assertEquals(1, result.get().getPlayers().size(), "growth should have been applied");
        org.mockito.Mockito.verify(playerRepository).saveAll(any());
    }

    // ------------------------------------------------------------ admin override

    @Test
    @DisplayName("S0.3: force=true re-runs and overwrites the stored report")
    void forceRerunIsAllowed() throws Exception {
        existingReport();

        TrainingWeekReportDTO result = service.runWeeklyTraining(TEAM_ID, true);

        assertEquals(1, result.getPlayers().size());
        org.mockito.Mockito.verify(playerRepository).saveAll(any());
        org.mockito.Mockito.verify(reportRepository).save(any(TrainingWeekReport.class));
    }

    @Test
    @DisplayName("S0.3: a first run in a fresh week succeeds and returns a per-player report")
    void firstRunSucceeds() {
        when(reportRepository.findByTeamIdAndSeasonNumberAndWeekNumber(TEAM_ID, SEASON, WEEK))
                .thenReturn(Optional.empty());

        TrainingWeekReportDTO result = service.runWeeklyTraining(TEAM_ID);

        assertEquals(TEAM_ID, result.getTeamId());
        assertEquals(1, result.getPlayers().size());
        assertEquals(100L, result.getPlayers().get(0).getPlayerId());
    }

    @Test
    @DisplayName("S0.3: growth is real - the direct skill must actually move")
    void growthIsApplied() {
        when(reportRepository.findByTeamIdAndSeasonNumberAndWeekNumber(TEAM_ID, SEASON, WEEK))
                .thenReturn(Optional.empty());
        double before = player.getSkills().getExact(SkillName.PASSING);

        service.runWeeklyTraining(TEAM_ID);

        assertTrue(player.getSkills().getExact(SkillName.PASSING) > before,
                "a first run must increase the trained skill (before=" + before
                        + ", after=" + player.getSkills().getExact(SkillName.PASSING) + ")");
        assertTrue(player.getSkills().getExact(SkillName.PASSING) < before + 2.0,
                "growth must stay in a sane weekly range");
    }

    @Test
    @DisplayName("S0.3: SkillName enum is loadable - guards the fixture itself")
    void skillEnumLoads() {
        assertEquals(9, SkillName.values().length, "skill enum should be intact");
    }
}
