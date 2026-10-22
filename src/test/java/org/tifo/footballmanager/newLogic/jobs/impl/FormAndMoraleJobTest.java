package org.tifo.footballmanager.newLogic.jobs.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.tifo.footballmanager.newLogic.jobs.JobContext;
import org.tifo.footballmanager.newLogic.model.CompetitionTeamType;
import org.tifo.footballmanager.newLogic.model.Player;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.model.TrainingIntensity;
import org.tifo.footballmanager.newLogic.repository.MatchPlayerStatsRepository;
import org.tifo.footballmanager.newLogic.repository.MatchRepository;
import org.tifo.footballmanager.newLogic.repository.PlayerRepository;
import org.tifo.footballmanager.newLogic.repository.PlayerTrainingIntensityRepository;
import org.tifo.footballmanager.newLogic.repository.TeamTrainingSetupRepository;
import org.tifo.footballmanager.newLogic.repository.TransferRepository;
import org.tifo.footballmanager.newLogic.service.MoraleService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Day 6: form and morale (owner, 2026-10-22).
 *
 * <p><b>What this guards.</b> The owner's week carries a day for it — *"day 6 · form and morale"* —
 * and nothing ran there. The work already existed and had no caller: {@code
 * MoraleService.applyStanding} ("is he being paid fairly, is he in the side, is he being listed")
 * had never been called by anything, and the weekly settle of the form did not exist at all.
 *
 * <p><b>And the shape of it.</b> The world is fourteen thousand clubs and three hundred and seventy
 * thousand players. The inputs have to be derived — there is no "started" flag on a player and no
 * "listed" flag either — and the sweep has to be <b>set-based at the top and paged at the bottom</b>,
 * or it is fourteen thousand round trips a week. That is the same lesson as the ban-check fix and
 * the rating backfill, and it is the part of this job that a reader has to be able to check.
 */
class FormAndMoraleJobTest {

    private final MoraleService morale = mock(MoraleService.class);
    private final PlayerRepository players = mock(PlayerRepository.class);
    private final MatchPlayerStatsRepository stats = mock(MatchPlayerStatsRepository.class);
    private final TransferRepository transfers = mock(TransferRepository.class);
    private final MatchRepository matches = mock(MatchRepository.class);
    private final PlayerTrainingIntensityRepository intensityOverrides =
            mock(PlayerTrainingIntensityRepository.class);
    private final TeamTrainingSetupRepository trainingSetups = mock(TeamTrainingSetupRepository.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

    @Test
    @DisplayName("it runs on day 6, at the hour the week says")
    void itRunsOnDaySix() {
        FormAndMoraleJob job = job();

        assertEquals(6, job.day(), "the owner's week gives day 6 to form and morale");
        assertEquals(15, job.hour(), "at 15:00 — the day's league round is over and the day-7 side is "
                + "not picked yet");
        assertEquals(FormAndMoraleJob.KEY, job.key(), "and it has its own key, so the done-flag "
                + "cannot be claimed by another job");
    }

    @Test
    @DisplayName("it asks the world three questions, not one per club")
    void itAsksTheWorldOnce() {
        when(players.findByIdGreaterThanOrderByIdAsc(anyLong(), any())).thenReturn(List.of());
        when(stats.findPlayerIdsWithAppearancesInWeek(1, 2)).thenReturn(List.of());
        when(transfers.findActiveListedPlayerIds()).thenReturn(List.of());
        when(matches.findHomeClubIdsWithFixturesInWeek(1, 2)).thenReturn(List.of());
        when(matches.findAwayClubIdsWithFixturesInWeek(1, 2)).thenReturn(List.of());
        when(intensityOverrides.findBySeasonAndWeek(1, 2)).thenReturn(List.of());
        when(trainingSetups.findBySeasonNumberAndWeekNumber(1, 2)).thenReturn(List.of());

        job().run(new JobContext(1, 2, 6, 15));

        // **One query for the whole world, each.** The old lesson: the ban check was asked twice per
        // player per match and cost two hundred and sixteen thousand derived-query executions a
        // matchday. This sweep asks the world three times a week, whatever the world's size.
        verify(stats, times(1)).findPlayerIdsWithAppearancesInWeek(1, 2);
        verify(transfers, times(1)).findActiveListedPlayerIds();
        verify(matches, times(1)).findHomeClubIdsWithFixturesInWeek(1, 2);
        verify(matches, times(1)).findAwayClubIdsWithFixturesInWeek(1, 2);
    }

    @Test
    @DisplayName("a player who played is in his side, one who did not is not")
    void theTwoInputsAreDerived() {
        Team club = club(10L);
        Player played = player(1L, club);
        Player benched = player(2L, club);

        when(players.findByIdGreaterThanOrderByIdAsc(anyLong(), any()))
                .thenReturn(List.of(played, benched))
                .thenReturn(List.of());
        when(stats.findPlayerIdsWithAppearancesInWeek(1, 2)).thenReturn(List.of(1L));
        when(transfers.findActiveListedPlayerIds()).thenReturn(List.of());
        when(matches.findHomeClubIdsWithFixturesInWeek(1, 2)).thenReturn(List.of(10L));
        when(matches.findAwayClubIdsWithFixturesInWeek(1, 2)).thenReturn(List.of());
        when(intensityOverrides.findBySeasonAndWeek(1, 2)).thenReturn(List.of());
        when(trainingSetups.findBySeasonNumberAndWeekNumber(1, 2)).thenReturn(List.of());

        job().run(new JobContext(1, 2, 6, 15));

        verify(morale).settleWeek(eq(played), eq(true), eq(false), eq(1), eq(2), any(TrainingIntensity.class));
        verify(morale).settleWeek(eq(benched), eq(false), eq(false), eq(1), eq(2), any(TrainingIntensity.class));
    }

    @Test
    @DisplayName("a player on the market is told so")
    void theMarketIsAskedOnce() {
        Team club = club(10L);
        Player listed = player(1L, club);

        when(players.findByIdGreaterThanOrderByIdAsc(anyLong(), any()))
                .thenReturn(List.of(listed))
                .thenReturn(List.of());
        when(stats.findPlayerIdsWithAppearancesInWeek(1, 2)).thenReturn(List.of(1L));
        when(transfers.findActiveListedPlayerIds()).thenReturn(List.of(1L));
        when(matches.findHomeClubIdsWithFixturesInWeek(1, 2)).thenReturn(List.of(10L));
        when(matches.findAwayClubIdsWithFixturesInWeek(1, 2)).thenReturn(List.of());
        when(intensityOverrides.findBySeasonAndWeek(1, 2)).thenReturn(List.of());
        when(trainingSetups.findBySeasonNumberAndWeekNumber(1, 2)).thenReturn(List.of());

        job().run(new JobContext(1, 2, 6, 15));

        verify(morale).settleWeek(eq(listed), eq(true), eq(true), eq(1), eq(2), any(TrainingIntensity.class));
    }

    @Test
    @DisplayName("a player whose club did not play is not out of his side")
    void aClubWithoutAFixtureIsNotJudged() {
        // The club had no fixture this week, so it has nobody to be disappointed in. Penalising its
        // whole squad for a week the club did not play would be the sweep inventing a grievance.
        Team idle = club(10L);
        Player player = player(1L, idle);

        when(players.findByIdGreaterThanOrderByIdAsc(anyLong(), any()))
                .thenReturn(List.of(player))
                .thenReturn(List.of());
        when(stats.findPlayerIdsWithAppearancesInWeek(1, 2)).thenReturn(List.of());
        when(transfers.findActiveListedPlayerIds()).thenReturn(List.of());
        when(matches.findHomeClubIdsWithFixturesInWeek(1, 2)).thenReturn(List.of());
        when(matches.findAwayClubIdsWithFixturesInWeek(1, 2)).thenReturn(List.of());
        when(intensityOverrides.findBySeasonAndWeek(1, 2)).thenReturn(List.of());
        when(trainingSetups.findBySeasonNumberAndWeekNumber(1, 2)).thenReturn(List.of());

        job().run(new JobContext(1, 2, 6, 15));

        verify(morale).settleWeek(eq(player), eq(true), eq(false), eq(1), eq(2), any(TrainingIntensity.class));
    }

    @Test
    @DisplayName("a national squad's rows are not settled")
    void nationalSquadRowsAreNotSettled() {
        // A national squad's rows are copies of club players' rows. Their form is written by the
        // copy that made them, and settling them here would make the two drift apart.
        Team national = new Team();
        national.setId(50L);
        national.setType(CompetitionTeamType.NATIONAL_TEAM);
        Player copy = player(1L, national);

        when(players.findByIdGreaterThanOrderByIdAsc(anyLong(), any()))
                .thenReturn(List.of(copy))
                .thenReturn(List.of());
        when(stats.findPlayerIdsWithAppearancesInWeek(1, 2)).thenReturn(List.of(1L));
        when(transfers.findActiveListedPlayerIds()).thenReturn(List.of());
        when(matches.findHomeClubIdsWithFixturesInWeek(1, 2)).thenReturn(List.of(50L));
        when(matches.findAwayClubIdsWithFixturesInWeek(1, 2)).thenReturn(List.of());
        when(intensityOverrides.findBySeasonAndWeek(1, 2)).thenReturn(List.of());
        when(trainingSetups.findBySeasonNumberAndWeekNumber(1, 2)).thenReturn(List.of());

        job().run(new JobContext(1, 2, 6, 15));

        verify(morale, times(0)).settleWeek(any(), any(Boolean.class), any(Boolean.class), anyInt(), anyInt(), any(TrainingIntensity.class));
    }

    @Test
    @DisplayName("the sweep reads the players in pages, not all at once")
    void theSweepIsPaged() {
        Team club = club(10L);
        Player first = player(1L, club);
        Player second = player(2L, club);

        when(players.findByIdGreaterThanOrderByIdAsc(anyLong(), any()))
                .thenReturn(List.of(first))
                .thenReturn(List.of(second))
                .thenReturn(List.of());
        when(stats.findPlayerIdsWithAppearancesInWeek(1, 2)).thenReturn(List.of(1L, 2L));
        when(transfers.findActiveListedPlayerIds()).thenReturn(List.of());
        when(matches.findHomeClubIdsWithFixturesInWeek(1, 2)).thenReturn(List.of(10L));
        when(matches.findAwayClubIdsWithFixturesInWeek(1, 2)).thenReturn(List.of());
        when(intensityOverrides.findBySeasonAndWeek(1, 2)).thenReturn(List.of());
        when(trainingSetups.findBySeasonNumberAndWeekNumber(1, 2)).thenReturn(List.of());

        job().run(new JobContext(1, 2, 6, 15));

        // The cursor moves by id, so the second page starts after the first one's last player.
        verify(players).findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class));
        verify(players).findByIdGreaterThanOrderByIdAsc(eq(1L), any(Pageable.class));
        verify(morale).settleWeek(eq(first), eq(true), eq(false), eq(1), eq(2), any(TrainingIntensity.class));
        verify(morale).settleWeek(eq(second), eq(true), eq(false), eq(1), eq(2), any(TrainingIntensity.class));
    }

    @Test
    @DisplayName("it runs outside the framework's transaction, a page at a time")
    void itOptsOutOfTheFrameworksTransaction() {
        assertTrue(job().transactionalRun() == false,
                "a page of five hundred players is a unit of work, and the framework's transaction is "
                        + "one transaction for the whole world");
    }

    private FormAndMoraleJob job() {
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        return new FormAndMoraleJob(morale, players, stats, transfers, matches, intensityOverrides,
                trainingSetups, transactionManager);
    }

    private Team club(Long id) {
        Team club = new Team();
        club.setId(id);
        // PyramidBuilder never sets a club's type, so a club's type is null.
        club.setType(null);
        return club;
    }

    private Player player(Long id, Team club) {
        Player player = new Player();
        player.setId(id);
        player.setTeam(club);
        player.setForm(6.0);
        player.setMorale(60.0);
        return player;
    }
}
