package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.repository.JobRunRepository;
import org.example.footballmanager.newLogic.jobs.JobRunner;
import org.example.footballmanager.newLogic.model.GameClock;
import org.example.footballmanager.newLogic.model.GameDay;
import org.example.footballmanager.newLogic.repository.GameClockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Advancing a week has to be a week (owner, 2026-09-30).
 *
 * <p>Every assertion here describes something that was true of the running app. {@code job_run} held
 * thirteen runs, all of them at day 3, none at day 7; {@code season-rollover} had never run; the day-7
 * league matchday had never run; and the promotion ladder had therefore never executed for any country
 * in the world. All of it came from one method that moved a counter, added a day of game time and
 * dispatched the runner once.
 *
 * <p><b>Built directly, not through Spring.</b> The subject is which clock positions the runner is
 * offered, and that is decided entirely inside {@link GameClockService}. A full-context test would also
 * run the real jobs — 168 hours of matchday simulation across 31 divisions per country — and would then
 * be asserting on that, not on the thing that was broken. The runner here records and returns.
 */
class GameClockWeekAdvanceTest {

    private RecordingRunner runner;
    private GameClockService clockService;
    private GameClock clock;

    @BeforeEach
    void setUp() {
        runner = new RecordingRunner();
        GameClockRepository clocks = mock(GameClockRepository.class);
        SeasonService seasons = mock(SeasonService.class);
        clock = new GameClock();
        clock.setId(1L);
        clock.setCurrentWeek(3);
        clock.setCurrentSeason(1);
        clock.setCurrentDay(GameDay.DAY_3.number());
        clock.setCurrentHour(1);
        clock.setCurrentDate(LocalDateTime.of(2026, 2, 1, 12, 0));
        when(seasons.getOrCreateClock()).thenReturn(clock);
        when(clocks.save(any(GameClock.class))).thenAnswer(call -> call.getArgument(0));
        clockService = new GameClockService(clocks, seasons, runner);
    }

    @Test
    @DisplayName("a whole week offers every hour of the week to the runner")
    void everyHourOfTheWeekIsOffered() {
        clockService.advanceWeek();

        assertEquals(168, runner.offers.size(),
                "a whole week is 168 hours and each must be offered to the runner, or a trigger is "
                        + "stepped over. Offered: " + runner.offers.size());
        assertEquals(new TreeSet<>(List.of(1, 2, 3, 4, 5, 6, 7)), runner.daysOffered(),
                "not every day of the week reached the runner — days offered: " + runner.daysOffered());
    }

    @Test
    @DisplayName("the week counter moves by one and the season wraps at the end")
    void theWeekCounterMoves() {
        clockService.advanceWeek();
        assertEquals(4, clock.getCurrentWeek(), "the week counter did not move by one");
        assertEquals(1, clock.getCurrentSeason(), "the season wrapped in week 3");

        // Week 12 is where the season rollover job is pinned, and the season must turn after it.
        clock.setCurrentWeek(SeasonService.WEEKS_PER_SEASON);
        clock.setCurrentDay(GameDay.DAY_3.number());
        clock.setCurrentHour(1);
        runner.offers.clear();

        clockService.advanceWeek();

        assertEquals(1, clock.getCurrentWeek(), "the new season did not start at week 1");
        assertEquals(2, clock.getCurrentSeason(), "the season did not advance after the last week");
        assertTrue(runner.offers.stream().anyMatch(offer -> offer.week() == SeasonService.WEEKS_PER_SEASON
                        && offer.day() == 7),
                "week 12 day 7 was never offered, which is the only moment season-rollover runs");
    }

    @Test
    @DisplayName("a whole week lands on the same day and hour it started from")
    void thePositionIsStable() {
        // The reason the old code moved the counter directly: composing the week from days made the
        // end position depend on the hour the button was pressed. 168 hours is exactly seven days, so
        // it lands on the same day and hour whatever hour that was.
        clock.setCurrentDay(GameDay.DAY_1.number());
        clock.setCurrentHour(23);
        clockService.advanceWeek();

        assertEquals(GameDay.DAY_1.number(), clock.getCurrentDay(), "a whole week moved the day of the week");
        assertEquals(23, clock.getCurrentHour(), "a whole week moved the hour of the day");
    }

    @Test
    @DisplayName("the week is the same from any starting hour")
    void theWeekDoesNotDependOnThePressHour() {
        for (int hour : List.of(0, 5, 12, 23)) {
            clock.setCurrentWeek(2);
            clock.setCurrentDay(GameDay.DAY_2.number());
            clock.setCurrentHour(hour);
            clockService.advanceWeek();
            assertEquals(3, clock.getCurrentWeek(), "pressed at " + hour + ":00, the week landed on "
                    + clock.getCurrentWeek());
            assertEquals(hour, clock.getCurrentHour(), "pressed at " + hour + ":00, the hour moved");
        }
    }

    @Test
    @DisplayName("advancing a day offers the rest of that day, and rolls the day over")
    void advanceDayStillWorks() {
        clock.setCurrentHour(0);
        clockService.advanceDay();

        // It used to be 48: the remaining hours were offered to the runner and then offered again by
        // advanceHours over the same range. The pre-loop existed so a 23:00 job would fire on its own
        // day, which advanceHour already guarantees — it dispatches after the hour increment and before
        // the day rolls.
        assertEquals(24, runner.offers.size(), "a whole day is 24 hours, and each exactly once");
        assertEquals(GameDay.DAY_4.number(), clock.getCurrentDay(), "the day did not roll from 3 to 4");
    }

    /** One clock position the runner was asked about. */
    private record Offer(int week, int day, int hour) {
    }

    /**
     * Records the positions offered and runs nothing.
     *
     * <p>Running nothing is the point: these tests are about <i>which</i> positions get offered. A runner
     * that also simulated 168 hours of football would make the test slow and would make a failure
     * ambiguous between "the clock skipped a trigger" and "a job threw".
     */
    private static final class RecordingRunner extends JobRunner {

        private final List<Offer> offers = new ArrayList<>();

        private RecordingRunner() {
            super(List.of(), mock(JobRunRepository.class), mock(PlatformTransactionManager.class));
        }

        @Override
        public Map<String, Object> runDue(int seasonYear, int week, int day, int hour) {
            offers.add(new Offer(week, day, hour));
            return Map.of();
        }

        private TreeSet<Integer> daysOffered() {
            TreeSet<Integer> days = new TreeSet<>();
            offers.forEach(offer -> days.add(offer.day()));
            return days;
        }
    }
}
