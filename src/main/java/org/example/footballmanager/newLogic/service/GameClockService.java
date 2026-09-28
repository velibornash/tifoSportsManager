package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.GameClock;
import org.example.footballmanager.newLogic.model.GameDay;
import org.example.footballmanager.newLogic.jobs.JobRunner;
import org.example.footballmanager.newLogic.repository.GameClockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The game clock (owner, 2026-09-28).
 *
 * <p>Owns three things that were previously spread across {@code SeasonService} and the browser:
 * what time it is in the game, how to move it forward, and keeping the day, week and season
 * counters consistent when it moves.
 *
 * <h2>Why game time is an offset and not a value</h2>
 *
 * <p>The first cut stored {@code currentHour} as a plain integer and rendered the header from
 * {@code Date.now()}. That is a clock that cannot be moved: advancing the hour writes 10, and the
 * next render reads the real time again, so the jump lasts a frame. Worse, it means the game time
 * and the real time are two different clocks with nothing tying them together, and "what time is it
 * in the game" has no single answer.
 *
 * <p>So game time is <b>real time plus an accumulated offset</b>:
 *
 * <pre>   gameTime = now() + advanceOffsetSeconds</pre>
 *
 * <p>That satisfies both halves of what the owner asked for. The clock ticks 1:1 with real seconds -
 * one real second is one game second - because only the offset is stored and {@code now()} supplies
 * the ticking. And {@code advance hour} moves it forward permanently, because it adds to the offset
 * rather than overwriting a value that the next tick would undo.
 *
 * <h2>Why the day counter is not derived from the clock</h2>
 *
 * <p>{@code currentDay} counts days crossed, not weekdays. Deriving it from the calendar would make
 * the same season behave differently depending on the weekday it was started on, and a cup round
 * could land on the wrong day purely because of the start date.
 */
@Service
public class GameClockService {

    private static final int SECONDS_PER_HOUR = 3600;
    private static final int HOURS_PER_DAY = 24;

    /**
     * The zone the game clock reads its hour in.
     *
     * <p>Must match the zone clock.js formats in, or the header and the API would disagree about
     * what hour it is - the same class of bug as storing the hour twice.
     */
    private static final java.time.ZoneId GAME_ZONE = java.time.ZoneId.of("Europe/Belgrade");

    private final GameClockRepository clocks;
    private final SeasonService seasons;
    private final JobRunner jobRunner;

    public GameClockService(GameClockRepository clocks, SeasonService seasons, JobRunner jobRunner) {
        this.clocks = clocks;
        this.seasons = seasons;
        this.jobRunner = jobRunner;
    }

    private GameClock clock() {
        return seasons.getOrCreateClock();
    }

    /** Game time: real time plus everything the owner has advanced. */
    public Instant gameTime() {
        GameClock clock = clock();
        return Instant.now().plusSeconds(offsetOf(clock));
    }

    /**
     * The advance offset, treating a legacy null as zero.
     *
     * <p>A null offset is a row created before the column existed. An entity field default only
     * applies to rows Hibernate inserts, so it cannot be relied on to backfill.
     */
    private long offsetOf(GameClock clock) {
        Long offset = clock.getAdvanceOffsetSeconds();
        return offset == null ? 0L : offset;
    }

    /**
     * The cycle position implied by the current hour, day, week and season.
     *
     * <p>Derived on read rather than stored, so it can never disagree with the clock it describes.
     */
    public Map<String, Object> snapshot() {
        GameClock clock = clock();
        int day = clock.getCurrentDay() == null ? GameDay.FIRST : clock.getCurrentDay();
        // The hour is DERIVED from the game timestamp, never stored as the authority. Storing it and
        // also computing it from the offset gave two answers for the same question - the header read
        // "hour 20" while the timestamp it was rendered from read 06:22, because the counter and the
        // offset had drifted apart. A derived value cannot drift.
        Instant gameTime = gameTime();
        int hour = gameTime.atZone(GAME_ZONE).getHour();
        GameDay gameDay = GameDay.of(day);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seasonNumber", clock.getCurrentSeason());
        out.put("weekNumber", clock.getCurrentWeek());
        out.put("day", gameDay.number());
        out.put("dayLabel", gameDay.label());
        out.put("dayKind", gameDay.kind().name());
        out.put("matchDay", gameDay.isMatchDay());
        out.put("kickoffHour", gameDay.kickoffHour());
        out.put("hour", hour);
        out.put("gameTime", gameTime().toString());
        out.put("advanceOffsetSeconds", offsetOf(clock));
        return out;
    }

    /**
     * Moves the game clock forward by whole hours.
     *
     * <p>Adds to the offset <b>and</b> moves the day counter, carrying into week and season. Both
     * are updated in one transaction because the header shows the ticking time while the day label
     * comes from the counter: if only the offset moved, the clock would show 20:00 on day 1 after
     * advancing a day, which is worse than either being wrong on its own.
     */
    @Transactional
    public Map<String, Object> advanceHours(int hours) {
        if (hours <= 0) {
            throw new IllegalArgumentException("Advance must be a positive number of hours.");
        }
        GameClock clock = clock();

        // The day is derived from the date the clock moves across, NOT from hours/24. Advancing one
        // hour from 23:00 to 00:00 crosses midnight, so the day must advance - and hours/24 said
        // zero, which left day 2 reading as 00:00 and the day counter stuck. That is the bug this
        // now measures before and after instead of dividing.
        java.time.LocalDate dayBefore = gameTime().atZone(GAME_ZONE).toLocalDate();

        clock.setAdvanceOffsetSeconds(offsetOf(clock) + (long) hours * SECONDS_PER_HOUR);

        long carry = java.time.temporal.ChronoUnit.DAYS.between(
                dayBefore, gameTime().atZone(GAME_ZONE).toLocalDate());
        int day = (clock.getCurrentDay() == null ? GameDay.FIRST : clock.getCurrentDay()) + (int) carry;
        while (day > GameDay.LAST) {
            day -= GameDay.LAST;
            clock.setCurrentWeek((clock.getCurrentWeek() == null ? 1 : clock.getCurrentWeek()) + 1);
        }
        clock.setCurrentDay(day);

        rollSeasonIfSeasonEnded(clock);
        clock.setCurrentDate(LocalDateTime.ofInstant(gameTime(), ZoneOffset.UTC));
        clocks.save(clock);

        // Jobs become due because time moved, so they run here. One advance path means there is
        // nowhere else a job could be started from - which is what makes the done-flags meaningful.
        int seasonYear = SeasonService.BASE_SEASON_YEAR
                + ((clock.getCurrentSeason() == null ? 1 : clock.getCurrentSeason()) - 1);
        int week = clock.getCurrentWeek() == null ? 1 : clock.getCurrentWeek();
        int dayNow = clock.getCurrentDay() == null ? GameDay.FIRST : clock.getCurrentDay();
        Map<String, Object> result = snapshot();
        result.put("jobs", jobRunner.runDue(seasonYear, week, dayNow, gameTime().atZone(GAME_ZONE).getHour()));
        return result;
    }

    /**
     * A whole week, as seven day advances (owner, 2026-09-28).
     *
     * <p>The old advance-week did the week's work itself: injuries, fatigue, contracts, finance,
     * training, youth, transfers. If it kept doing that, advancing a day and then advancing a week
     * would apply the same week twice, and the jobs would be a second code path competing with it.
     * So the week is now nothing but seven day advances, and every job fires from inside those.
     */
    @Transactional
    public Map<String, Object> advanceWeek() {
        Map<String, Object> last = null;
        for (int day = 0; day < 7; day++) {
            last = advanceDay();
        }
        return last == null ? snapshot() : last;
    }

    /** One day is twenty-four hours; the counters carry on their own. */
    @Transactional
    public Map<String, Object> advanceDay() {
        return advanceHours(HOURS_PER_DAY);
    }

    /**
     * Moves to a specific hour of the current day, e.g. straight to kickoff.
     *
     * <p>Never moves backwards: asking for an hour already past is a no-op rather than a rewind.
     * Rewinding would have to undo job runs, and the done-flags in P2 are not reversible.
     */
    @Transactional
    public Map<String, Object> advanceToHour(int targetHour) {
        if (targetHour < 0 || targetHour > 23) {
            throw new IllegalArgumentException("Hour must be between 0 and 23.");
        }
        int current = gameTime().atZone(GAME_ZONE).getHour();
        if (targetHour <= current) {
            // Already past it: a no-op returning the current state, not an error. This used to call
            // advanceHours(0) and answer 500, so asking to go to an hour the day had already passed
            // - which is a normal thing to do when positioning a test - blew up.
            return snapshot();
        }
        return advanceHours(targetHour - current);
    }

    /**
     * Ends the season when the last week is done.
     *
     * <p>Twelve weeks, then a new season. The week counter goes back to 1 rather than to 13 so a
     * long-running database cannot accumulate a week number no schedule will ever match.
     */
    private void rollSeasonIfSeasonEnded(GameClock clock) {
        int week = clock.getCurrentWeek() == null ? 1 : clock.getCurrentWeek();
        if (week > SeasonService.WEEKS_PER_SEASON) {
            clock.setCurrentWeek(1);
            clock.setCurrentSeason((clock.getCurrentSeason() == null ? 1 : clock.getCurrentSeason()) + 1);
        }
    }
}
