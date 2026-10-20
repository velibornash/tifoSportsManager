package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.sim.SimMatchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The matchday batch is what writes the ranking points and the medals (found 2026-10-08).
 *
 * <p><b>Why this class exists beside the rebuild's own tests.</b> Those tests pass even with the caller
 * removed — which is how they were written the first time, and how the defect survived in the first place.
 * The four services had green tests that called them directly and no caller in the application, so the
 * ledgers stayed empty and nothing was red. This class asserts the <b>caller</b>, which is the thing that
 * was actually missing.
 *
 * <p><b>Built by hand, deliberately.</b> {@code simulateInBackground} is {@code @Async}, but a
 * {@code new AsyncSimulationRunner(...)} is not the async proxy, so the batch runs inline and the
 * assertions can follow it without polling or sleeping on a real thread pool.
 */
class AsyncSimulationRunnerRebuildsRankingTest {

    private final MatchFixtureRepository fixtures = mock(MatchFixtureRepository.class);
    private final SimMatchService simMatchService = mock(SimMatchService.class);
    private final RankingPointsRebuildService rebuild = mock(RankingPointsRebuildService.class);
    private final ClubRatingService clubRatings = mock(ClubRatingService.class);
    private final SeasonService seasons = mock(SeasonService.class);

    private AsyncSimulationRunner runner() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(org.springframework.transaction.TransactionStatus.class));
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        when(seasons.getActiveSeasonYear()).thenReturn(3);
        when(clubRatings.recomputeDurably()).thenReturn(new ClubRatingService.Result(0, 0, 0, 1500, 1500));
        return new AsyncSimulationRunner(fixtures, template, simMatchService, clubRatings, seasons, rebuild);
    }

    private void aPlayableFixture(long id) {
        Team home = new Team();
        home.setId(1000L + id);
        Team away = new Team();
        away.setId(2000L + id);
        MatchFixture fixture = new MatchFixture();
        fixture.setId(id);
        fixture.setPlayed(false);
        fixture.setHomeTeam(home);
        fixture.setAwayTeam(away);
        when(fixtures.findById(id)).thenReturn(Optional.of(fixture));
        when(simMatchService.simulate(any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(mock(SimMatchService.SimMatchOutcome.class));
    }

    @Test
    @DisplayName("a finished batch rebuilds the ranking for the current season")
    void aBatchRebuildsTheRanking() {
        aPlayableFixture(1L);

        runner().simulateInBackground(List.of(1L));

        verify(rebuild).rebuildAfterBatch(3);
    }

    @Test
    @DisplayName("the rebuild happens beside the Elo replay, not instead of it")
    void theEloReplayStillRuns() {
        aPlayableFixture(1L);

        runner().simulateInBackground(List.of(1L));

        verify(clubRatings).recomputeDurably();
    }

    @Test
    @DisplayName("a rebuild that throws does not take the matchday with it")
    void aFailingRebuildDoesNotFailTheBatch() {
        aPlayableFixture(1L);
        org.mockito.Mockito.doThrow(new IllegalStateException("ledger is down"))
                .when(rebuild).rebuildAfterBatch(3);

        AsyncSimulationRunner runner = runner();
        runner.simulateInBackground(List.of(1L));

        assertEquals(1, runner.getSimulatedCount(),
                "the football is saved before the rebuild runs, so a broken rebuild must not undo it");
        assertEquals(0, runner.getFailedCount(),
                "nor may it be reported as a failed fixture — the fixture simulated cleanly");
    }

    @Test
    @DisplayName("an empty batch rebuilds nothing")
    void anEmptyBatchDoesNotRebuild() {
        runner().simulateInBackground(List.of());

        verify(rebuild, never()).rebuildAfterBatch(org.mockito.ArgumentMatchers.anyInt());
    }
}