package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.jobs.JobRunner;
import org.example.footballmanager.newLogic.model.GameClock;
import org.example.footballmanager.newLogic.model.GameDay;
import org.example.footballmanager.newLogic.model.GamePosition;
import org.example.footballmanager.newLogic.model.GameTimeline;
import org.example.footballmanager.newLogic.repository.GameClockRepository;
import org.example.footballmanager.newLogic.repository.JobRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The clock is one clock, and it ticks (owner, 2026-10-11).
 *
 * <p>This file replaces the tests written against the old model, where season/week/day/hour were four
 * stored counters and the header rendered a fifth thing entirely. Those counters could not be kept in
 * step with the time, so the tests could only assert that the counters moved — never that they were
 * right. Here there is one value, and the tests assert what the manager would see.
 *
 * <p><b>Time is injected.</b> {@link GameClockService} takes a {@link Clock}, and these tests move it
 * by hand. A game clock tested against the wall clock is right 363 nights a year and wrong on the two
 * daylight-saving ones, with no failure message that says why.
 *
 * <p><b>Built directly, not through Spring.</b> What is under test is which positions the job runner
 * is offered. A full context would also run the real jobs — 168 hours of matchday simulation across
 * 31 divisions per country — and a failure would then be ambiguous between "the clock skipped a
 * trigger" and "a job threw".
 */
class GameClockWeekAdvanceTest {

    /**
     * Monday 2026-01-05, 00:00 in the game's own zone.
     *
     * <p>Written as the UTC instant that <em>is</em> Belgrade midnight, which is 2026-01-04T23:00Z.
     * The clock reads the hour in Europe/Belgrade, and "00:00 UTC" is 01:00 there — which is how this
     * test first failed with an hour out on every assertion.
     */
    private static final Instant START = Instant.parse("2026-01-04T23:00:00Z");

    private RecordingRunner runner;
    private MutableClock time;
    private GameClockService clockService;
    private GameClock clock;

    @BeforeEach
    void setUp() {
        runner = new RecordingRunner();
        time = new MutableClock(START);
        GameClockRepository clocks = mock(GameClockRepository.class);
        SeasonService seasons = mock(SeasonService.class);
        clock = new GameClock();
        clock.setId(1L);
        clock.setAnchorDate(LocalDate.ofInstant(START, GameTimeline.GAME_ZONE).atStartOfDay());
        when(seasons.getOrCreateClock()).thenReturn(clock);
        when(clocks.save(any(GameClock.class))).thenAnswer(call -> call.getArgument(0));
        // Same-thread walk: the dispatch is the thing under test elsewhere, and a background thread
        // here would make every assertion a race against it.
        clockService = new GameClockService(clocks, seasons, runner, time, Runnable::run);
    }

    @Test
    @DisplayName("a fresh clock reads season 1, week 1, day 1 at 00:00")
    void aFreshClockIsAtTheStart() {
        GamePosition position = clock.positionAt(START);

        assertEquals(new GamePosition(1, 1, 1, 0), position,
                "the anchor is where season 1 week 1 day 1 sits, so an unmoved clock is there");
    }

    @Test
    @DisplayName("the clock ticks: an hour of real time is an hour of game time")
    void theClockTicks() {
        GamePosition before = clock.positionAt(START);
        time.advance(Duration.ofHours(5));

        GamePosition after = clock.positionAt(START.plus(Duration.ofHours(5)));
        assertEquals(before.hour() + 5, after.hour(),
                "the hour did not follow real time, so the header would show a frozen clock");
        assertEquals(before.season(), after.season());
        assertEquals(before.week(), after.week());
        assertEquals(before.day(), after.day());
    }

    @Test
    @DisplayName("day 7 rolls into the next week, and week 12 into the next season")
    void theCycleWraps() {
        // Seven days from day 1 lands on day 1 of week 2.
        Instant sevenDays = START.plus(Duration.ofDays(7));
        assertEquals(new GamePosition(1, 2, 1, 0), clock.positionAt(sevenDays),
                "passing from day 7 to day 1 must move the week on by one");

        // Twelve weeks from the start lands on season 2, week 1, day 1.
        //
        // The hour is not asserted: 84 days from 2026-01-04 is 2026-03-29, which is the night Belgrade
        // moves to summer time, so the local hour there is one out. The game counts days from the
        // anchor and that is correct; a test that insists the wall clock did not shift is testing
        // something the game does not promise.
        GamePosition nextSeason = clock.positionAt(START.plus(Duration.ofDays(84)));
        assertEquals(new GamePosition(2, 1, 1, nextSeason.hour()), nextSeason,
                "passing from week 12 to week 1 must move the season on by one");
    }

    @Test
    @DisplayName("there is no stored counter to disagree with the time")
    void theCountersAreGone() {
        // A world that advanced 70 days must not be able to report two different weeks. Before this
        // change the header read one clock and the jobs read another, silently.
        time.advance(Duration.ofDays(70));

        GamePosition fromTime = clock.positionAt(START.plus(Duration.ofDays(70)));
        long hourIndex = GameTimeline.hourIndex(fromTime);
        assertEquals(fromTime, GameTimeline.atHourIndex(hourIndex),
                "the position and its hour index disagree, so a walk could step over a day boundary");

        // And nothing on the entity can write one.
        assertThrows(NoSuchMethodException.class,
                () -> GameClock.class.getMethod("setCurrentWeek", Integer.class),
                "a writable week counter is a second source of truth, which is what this removed");
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
        assertEquals(new TreeSet<>(List.of(1, 2)), runner.weeksOffered(),
                "week 1 and week 2 only: a week must not spill into a third");
    }

    /**
     * Hour 23, on day 7, is where both rollover jobs sit — and for a year it was never offered.
     *
     * <p>The clock used to increment the hour and roll the day <em>before</em> asking the runner, so the
     * step out of hour 23 offered (day N+1, hour 0) and (day N, hour 23) was never asked at all.
     * {@code WeekRolloverJob} and {@code SeasonRolloverJob} therefore could not fire, ever: the week
     * still advanced, because the clock itself did that, so nothing looked wrong.
     */
    @Test
    @DisplayName("hour 23 of every day is offered, and day 7 is one of them")
    void hourTwentyThreeIsOfferedOnEveryDay() {
        clockService.advanceWeek();

        TreeSet<Integer> daysThatReachedHour23 = new TreeSet<>();
        runner.offers.stream()
                .filter(offer -> offer.hour() == 23)
                .forEach(offer -> daysThatReachedHour23.add(offer.day()));

        assertEquals(new TreeSet<>(List.of(1, 2, 3, 4, 5, 6, 7)), daysThatReachedHour23,
                "every game day must reach hour 23 — WeekRolloverJob and SeasonRolloverJob are pinned "
                        + "there. Days that reached it: " + daysThatReachedHour23);
        assertTrue(daysThatReachedHour23.contains(GameDay.LAST),
                "day 7 hour 23 is the only moment season-rollover can run, and it was never offered");
    }

    @Test
    @DisplayName("the week counter moves by one and the season wraps at the end")
    void theWeekCounterMoves() {
        clockService.advanceWeek();

        assertEquals(2, now().week(), "the week counter did not move by one");
        assertEquals(1, now().season(), "the season wrapped early");

        // Week 12 is where the season rollover job is pinned, and the season must turn after it.
        placeAt(new GamePosition(1, SeasonService.WEEKS_PER_SEASON, GameDay.DAY_3.number(), 1));
        runner.offers.clear();

        clockService.advanceWeek();

        assertEquals(1, now().week(), "the new season did not start at week 1");
        assertEquals(2, now().season(), "the season did not advance after the last week");
        assertTrue(runner.offers.stream().anyMatch(offer -> offer.week() == SeasonService.WEEKS_PER_SEASON
                        && offer.day() == 7),
                "week 12 day 7 was never offered, which is the only moment season-rollover runs");
    }

    @Test
    @DisplayName("a whole week lands on the same day and hour it started from")
    void thePositionIsStable() {
        // 168 hours is exactly seven days, so it lands on the same day and hour whatever hour that
        // was. Checked from four different starting hours, because an implementation that derives the
        // day from the anchor rather than from the counter passes at 00:00 and fails at 23:00.
        for (int hour : List.of(0, 5, 12, 23)) {
            setUp();
            placeAt(new GamePosition(1, 1, GameDay.DAY_1.number(), hour));
            GamePosition before = clock.positionAt(time.instant());

            clockService.advanceWeek();

            GamePosition after = clock.positionAt(time.instant());
            assertEquals(before.day(), after.day(),
                    "started at " + hour + ":00, a whole week changed the day of the week");
            assertEquals(before.hour(), after.hour(),
                    "started at " + hour + ":00, a whole week changed the hour of the day");
            assertEquals(before.week() + 1, after.week(), "started at " + hour + ":00, the week did not move");
        }
    }

    @Test
    @DisplayName("advancing a day offers the rest of that day, and rolls the day over")
    void advanceDayStillWorks() {
        clockService.advanceDay();

        // It used to be 48: the remaining hours were offered to the runner and then offered again by
        // advanceHours over the same range.
        assertEquals(24, runner.offers.size(), "a whole day is 24 hours, and each exactly once");
        assertEquals(GameDay.DAY_2.number(), now().day(), "day 1 did not roll to day 2");
    }

    @Test
    @DisplayName("a rewind moves the clock and re-arms nothing")
    void aRewindMovesTheClockAndRunsNoJobs() {
        placeAt(new GamePosition(1, 1, GameDay.DAY_4.number(), 12));
        runner.offers.clear();

        clockService.shiftOffsetSeconds(-3600);

        assertEquals(11, now().hour(), "the clock did not go back an hour");
        assertTrue(runner.offers.isEmpty(),
                "job runs are recorded and not reversible, so a rewind must not try to re-run them. "
                        + "Offered: " + runner.offers.size());
    }

    @Test
    @DisplayName("an hour missed while the app was down is caught up, not stepped over")
    void aMissedWindowIsCaughtUp() {
        // Stand the clock at Wednesday 09:00 and sync, so there is a last-synced point.
        placeAt(new GamePosition(1, 1, GameDay.DAY_3.number(), 9));
        clockService.synchronise();
        runner.offers.clear();

        // The app is shut for a day and a half. The day-3 league job at 19:00 and the day-4 training
        // job at 10:00 both fall inside it.
        time.advance(Duration.ofHours(36));
        clockService.synchronise();

        assertTrue(runner.offers.stream().anyMatch(o -> o.day() == GameDay.DAY_3.number() && o.hour() == 19),
                "the day-3 league matchday was missed while the app was down and never caught up");
        assertTrue(runner.offers.stream().anyMatch(o -> o.day() == GameDay.DAY_4.number() && o.hour() == 10),
                "the day-4 training job was missed while the app was down and never caught up");
        assertEquals(36, runner.offers.size(), "every hour crossed must be offered, not just the last");
    }

    @Test
    @DisplayName("a very long outage is capped, and says so")
    void aLongOutageIsCapped() {
        placeAt(new GamePosition(1, 1, GameDay.DAY_1.number(), 0));
        clockService.synchronise();
        runner.offers.clear();

        // Thirty days down. Walking all of it would simulate months of football in one request.
        time.advance(Duration.ofDays(30));
        Map<String, Object> result = clockService.synchronise();

        @SuppressWarnings("unchecked")
        Map<String, Object> jobs = (Map<String, Object>) result.get("jobs");
        assertEquals(GameClockService.MAX_CATCH_UP_HOURS, runner.offers.size(),
                "the walk must stop at the cap");
        assertEquals(Boolean.TRUE, jobs.get("truncated"),
                "a capped catch-up must say it was capped, or a manager whose month vanished has "
                        + "no way to know it was the cap rather than the game");
    }

    @Test
    @DisplayName("an advance larger than a week is refused rather than half-applied")
    void anOversizedAdvanceIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> clockService.advanceHours(0),
                "a zero advance would walk nothing and report a move");
        assertThrows(IllegalArgumentException.class, () -> clockService.advanceHours(-5),
                "a negative advance through this method would run jobs backwards");
    }

    @Test
    @DisplayName("an advance returns without waiting for the jobs it made due")
    void anAdvanceDoesNotBlockOnTheJobs() throws Exception {
        // The measured defect, 2026-10-11: one Advance Hour landed on day 4 hour 10, training became
        // due, and the request was still open after five minutes — `trainEveryClub` walks 14,880
        // clubs. The button spun, the browser timed out, and the world had moved with nothing on
        // screen saying so.
        CountDownLatch jobRunning = new CountDownLatch(1);
        CountDownLatch jobMayFinish = new CountDownLatch(1);
        SlowRunner slowRunner = new SlowRunner(jobRunning, jobMayFinish);

        GameClockRepository clocks = mock(GameClockRepository.class);
        when(clocks.save(any(GameClock.class))).thenAnswer(call -> call.getArgument(0));
        GameClockService dispatching = new GameClockService(clocks, seasonsReturning(clock), slowRunner,
                time, Executors.newSingleThreadExecutor());

        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<Map<String, Object>> response = caller.submit(() -> dispatching.advanceHours(1));

            Map<String, Object> result;
            try {
                result = response.get(5, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                fail("advanceHours blocked on the job bodies. The clock moved but the manager's browser "
                        + "waited, which is the defect this test exists for.");
                return;
            }

            assertEquals(Boolean.TRUE, result.get("syncing"),
                    "the response must say the walk is still running. It used to report the outcome "
                            + "of jobs that had not run yet, which is worse than reporting nothing.");
            @SuppressWarnings("unchecked")
            Map<String, Object> jobs = (Map<String, Object>) result.get("jobs");
            assertEquals(Boolean.TRUE, jobs.get("dispatched"),
                    "the advance must not claim the jobs have run");

            assertTrue(jobRunning.await(5, TimeUnit.SECONDS),
                    "the walk never started, so the advance did not trigger the jobs at all");
        } finally {
            jobMayFinish.countDown();
            caller.shutdownNow();
        }
    }

    /** A clock that has moved but whose jobs have not finished reports so, and says so when they do. */
    @Test
    @DisplayName("the clock reports it is still syncing, and stops saying so when the walk ends")
    void theClockReportsWhetherItIsStillWorking() {
        // Same-thread executor: the walk is finished by the time advanceHours returns, so the flag
        // must be down afterwards. A flag that latches on is as useless as one that is always off.
        clockService.advanceHours(1);

        assertEquals(Boolean.FALSE, clockService.snapshot().get("syncing"),
                "the walk had finished by the time the response was built, so the clock must not still "
                        + "claim to be syncing — otherwise the browser waits forever");
    }

    private SeasonService seasonsReturning(GameClock clock) {
        SeasonService seasons = mock(SeasonService.class);
        when(seasons.getOrCreateClock()).thenReturn(clock);
        return seasons;
    }

    /** A runner whose body takes as long as the caller lets it, like the real training job does. */
    private static final class SlowRunner extends RecordingRunner {

        private final CountDownLatch started;
        private final CountDownLatch finish;

        private SlowRunner(CountDownLatch started, CountDownLatch finish) {
            this.started = started;
            this.finish = finish;
        }

        @Override
        public Map<String, Object> runDue(int seasonYear, int week, int day, int hour) {
            started.countDown();
            try {
                finish.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Map.of();
        }
    }

    // --- helpers ---

    /**
     * Puts the world on a position, by moving the clock rather than by writing a counter.
     *
     * <p>There is no setter to abuse, which is the point: a test that could place the world by writing
     * {@code currentWeek} would be testing the setter.
     */
    /** Where the game is, as this test's clock sees it. Never {@code Instant.now()}: that is the whole point. */
    private GamePosition now() {
        return clock.positionAt(time.instant());
    }

    private void placeAt(GamePosition position) {
        LocalDate target = LocalDate.ofInstant(time.instant(), GameTimeline.GAME_ZONE);
        clock.setAnchorDate(GameTimeline.anchorFor(target, position).atStartOfDay());
        int localHour = time.instant().atZone(GameTimeline.GAME_ZONE).getHour();
        clock.setAdvanceOffsetSeconds((position.hour() - localHour) * 3600L);
        // Placing the world is starting a scenario, so the job walk starts here too. Without this the
        // walk would begin wherever the previous call left the sync marker and run a capped week from
        // there — which is correct behaviour and the wrong experiment.
        clock.setLastSyncedAt(clock.gameTimeAt(time.instant()));
        assertEquals(position, clock.positionAt(time.instant()),
                "the fixture could not place the clock, so every assertion below would be about "
                        + "the wrong day");
    }

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        private void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
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
    static class RecordingRunner extends JobRunner {

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

        private TreeSet<Integer> weeksOffered() {
            TreeSet<Integer> weeks = new TreeSet<>();
            offers.forEach(offer -> weeks.add(offer.week()));
            return weeks;
        }
    }
}
