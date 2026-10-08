package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.service.RankingPointsRebuildService;
import org.example.footballmanager.newLogic.service.SeasonService;
import org.example.footballmanager.newLogic.sim.SimMatchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A background fixture that fails is counted and named, not only logged.
 *
 * <p><b>The defect.</b> The runner caught a failed fixture, logged it at ERROR and moved on. So a pass
 * finished reporting {@code simulatedCount} against {@code totalCount} and the owner had to infer the
 * difference: {@code 148/154} never says <i>six failed</i>. Those six are matches that will never be
 * played, and a season that quietly loses a few percent of its football is found a long time afterwards,
 * if at all.
 *
 * <p><b>Why the ids and not just a count.</b> A count says something is wrong; the ids say what. The
 * fixture ids are the only handle a caller has on a match that was never simulated.
 *
 * <p><b>Why a real transaction manager.</b> The runner wraps each fixture in its own transaction, and
 * {@code TransactionTemplate} needs a real one to hand back a callback. Mocking the template would have
 * meant the loop under test never ran.
 */
class AsyncSimulationRunnerCountsFailuresTest {

    private final MatchFixtureRepository fixtures = mock(MatchFixtureRepository.class);
    private final SimMatchService simMatchService = mock(SimMatchService.class);

    private AsyncSimulationRunner runner() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(org.springframework.transaction.TransactionStatus.class));
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // The post-batch ranking rebuild is a collaborator now, and this class is about failure
        // counting, so it is mocked: the rebuild has its own test and its own transaction.
        RankingPointsRebuildService rebuild = mock(RankingPointsRebuildService.class);
        SeasonService seasons = mock(SeasonService.class);
        when(seasons.getActiveSeasonYear()).thenReturn(1);
        return new AsyncSimulationRunner(fixtures, template, simMatchService,
                mock(ClubRatingService.class), seasons, rebuild);
    }

    /**
     * A fixture that is present, unplayed and has both teams, so the runner actually simulates it.
     *
     * <p>The teams matter: the runner returns early — <b>and still counts the fixture as simulated</b> —
     * when either side is null. Leaving them out made this test report four simulations for a batch where
     * two threw, which is how that separate finding was noticed.
     */
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
    }

    private SimMatchService.SimMatchOutcome anOutcome() {
        return mock(SimMatchService.SimMatchOutcome.class);
    }

    @Test
    @DisplayName("two failures in a batch of four are counted and their fixture ids named")
    void failuresAreCountedAndNamed() throws Exception {
        for (long id = 1; id <= 4; id++) {
            aPlayableFixture(id);
        }
        // Two of the four blow up when simulated.
        when(simMatchService.simulate(any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(invocation -> {
                    long id = ((MatchFixture) invocation.getArgument(0)).getId();
                    if (id == 2 || id == 3) {
                        throw new IllegalStateException("engine fell over on fixture " + id);
                    }
                    // An outcome, not null: the runner calls sim.outcome() on the next line, and a null
                    // there throws inside the loop and lands in the very catch under test.
                    return anOutcome();
                });

        AsyncSimulationRunner runner = runner();
        runner.simulateInBackground(List.of(1L, 2L, 3L, 4L));
        // The method is @Async; called directly it runs inline, which is what this test needs.
        Thread.sleep(200);

        assertEquals(4, runner.getTotalCount());
        assertEquals(2, runner.getSimulatedCount(), "two fixtures simulated cleanly");
        assertEquals(2, runner.getFailedCount(),
                "the two that threw were logged and skipped, and the count must say so");
        assertEquals(List.of(2L, 3L), runner.getFailedIds(),
                "the ids are the only handle a caller has on a match that was never played");
    }

    @Test
    @DisplayName("a clean batch reports no failures at all")
    void aCleanBatchReportsNoFailures() throws Exception {
        for (long id = 1; id <= 3; id++) {
            aPlayableFixture(id);
        }
        when(simMatchService.simulate(any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(anOutcome());

        AsyncSimulationRunner runner = runner();
        runner.simulateInBackground(List.of(1L, 2L, 3L));
        Thread.sleep(200);

        assertEquals(3, runner.getSimulatedCount());
        assertEquals(0, runner.getFailedCount());
        assertTrue(runner.getFailedIds().isEmpty());
    }

    @Test
    @DisplayName("the failed ids cannot be mutated by a caller")
    void theFailedIdsCannotBeMutated() throws Exception {
        for (long id = 1; id <= 2; id++) {
            aPlayableFixture(id);
        }
        when(simMatchService.simulate(any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenThrow(new IllegalStateException("engine fell over"));

        AsyncSimulationRunner runner = runner();
        runner.simulateInBackground(List.of(1L, 2L));
        Thread.sleep(200);

        assertEquals(2, runner.getFailedIds().size());
        assertThrows(UnsupportedOperationException.class, () -> runner.getFailedIds().clear(),
                "the runner hands out its own mutable list, so a caller can erase the record of what "
                        + "failed — which is the only record there is");
    }
}
