package org.tifo.footballmanager.newLogic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.tifo.footballmanager.newLogic.jobs.JobRunner;
import org.tifo.footballmanager.newLogic.model.GameClock;
import org.tifo.footballmanager.newLogic.model.MatchFixture;
import org.tifo.footballmanager.newLogic.repository.GameClockRepository;
import org.tifo.footballmanager.newLogic.repository.JobRunRepository;
import org.tifo.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The sync has to check that nothing in the past was left behind, and say so (owner, 2026-10-19).
 *
 * <p><b>The ruling, verbatim:</b> <em>"every match whose start is in the past must be played — the game
 * looks only at its own season/week/day/hour against the current s/w/d/h — <b>and the sync has to
 * check it</b>."</em>
 *
 * <p>The first half of that was already true: the clock's walk offers every game hour it crosses to
 * the job runner, so a matchday missed while the app was down is replayed when it comes back. **What
 * was missing was the check** — nothing ever asked whether the walk had actually caught up. A
 * matchday whose publish job failed, a publish job that found nothing to publish, a catch-up window
 * longer than the cap: each of them leaves a fixture in the past that no job will ever play, and
 * each of them looked exactly like a healthy world.
 *
 * <p><b>So the walk asks after every hour it crosses, and the answer is in the response.</b> The
 * response is what the admin panel reads, so this is where the world says it is broken.
 */
class GameClockReportsStrandedFixturesTest {

    @Test
    @DisplayName("the sync says how many fixtures were left behind, and names them")
    void theSyncSaysWhatWasLeftBehind() {
        MatchFixtureRepository fixtureRepository = mock(MatchFixtureRepository.class);
        when(fixtureRepository.countUnplayedBefore(any(), any(), any())).thenReturn(3L);
        MatchFixture stranded = new MatchFixture();
        stranded.setId(77L);
        stranded.setSeasonYear(1);
        stranded.setWeekNumber(1);
        stranded.setDayNumber(3);
        when(fixtureRepository.findUnplayedBefore(any(), any(), any(), any()))
                .thenReturn(List.of(stranded));

        GameClock clock = new GameClock();
        clock.setId(1L);
        clock.setAnchorDate(LocalDate.of(2026, 1, 5).atStartOfDay());

        GameClockRepository clocks = mock(GameClockRepository.class);
        when(clocks.save(any(GameClock.class))).thenAnswer(call -> call.getArgument(0));
        SeasonService seasons = mock(SeasonService.class);
        when(seasons.getOrCreateClock()).thenReturn(clock);

        JobRunner runner = new JobRunner(List.of(), mock(JobRunRepository.class),
                mock(PlatformTransactionManager.class));
        GameClockService service = new GameClockService(clocks, seasons, runner,
                new StrandedFixtureService(fixtureRepository), Clock.systemUTC(), Runnable::run);

        Map<String, Object> result = service.synchronise();
        @SuppressWarnings("unchecked")
        Map<String, Object> jobs = (Map<String, Object>) result.get("jobs");

        assertEquals(3, jobs.get("stranded"),
                "the sync has to say how many fixtures are in the past and were never played. It said "
                        + "nothing, and a world with a matchday missing looks exactly like a healthy "
                        + "one.");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> named = (List<Map<String, Object>>) jobs.get("strandedFixtures");
        assertEquals(1, named.size(), "and it names them");
        assertEquals(77L, named.get(0).get("fixtureId"), "so that the fixture can be found");
    }

    @Test
    @DisplayName("a world with nothing left behind says nothing")
    void aWholeWorldSaysNothing() {
        MatchFixtureRepository fixtureRepository = mock(MatchFixtureRepository.class);
        when(fixtureRepository.countUnplayedBefore(any(), any(), any())).thenReturn(0L);

        GameClock clock = new GameClock();
        clock.setId(1L);
        clock.setAnchorDate(LocalDate.of(2026, 1, 5).atStartOfDay());

        GameClockRepository clocks = mock(GameClockRepository.class);
        when(clocks.save(any(GameClock.class))).thenAnswer(call -> call.getArgument(0));
        SeasonService seasons = mock(SeasonService.class);
        when(seasons.getOrCreateClock()).thenReturn(clock);

        JobRunner runner = new JobRunner(List.of(), mock(JobRunRepository.class),
                mock(PlatformTransactionManager.class));
        GameClockService service = new GameClockService(clocks, seasons, runner,
                new StrandedFixtureService(fixtureRepository), Clock.systemUTC(), Runnable::run);

        Map<String, Object> result = service.synchronise();
        @SuppressWarnings("unchecked")
        Map<String, Object> jobs = (Map<String, Object>) result.get("jobs");

        assertEquals(0, jobs.get("stranded"),
                "a world with nothing left behind must say so, or the warning becomes noise and the "
                        + "real one is not read");
        assertTrue(jobs.get("strandedFixtures") instanceof List, "and it carries the list too");
    }
}
