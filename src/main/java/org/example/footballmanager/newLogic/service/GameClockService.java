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
    private static final int DAYS_PER_WEEK = 7;

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
        // An explicit counter, the same one advanceHour wraps. It was derived from the game
        // timestamp before, which tied the hour to the wall clock and made a 23:00 job fire or not
        // depending on what time the manager pressed the button. gameTime() is still reported below
        // for the ticking display, but it no longer decides what hour it is.
        int hour = clock.getCurrentHour() == null ? 0 : clock.getCurrentHour();
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
     * Moves the game clock forward one hour (owner, 2026-09-28).
     *
     * <p>The rules, exactly as the owner stated them: the offset on the real clock grows by an hour,
     * and the counters wrap.
     *
     * <pre>
     *   hour  23 -&gt; 0,  and the day moves up by one
     *   day    7 -&gt; 1,  and the week moves up by one
     *   week  12 -&gt; 1,  and the season moves up by one
     * </pre>
     *
     * <p><b>Explicit counters, not derived values.</b> The previous version derived the hour from the
     * game timestamp and worked the day out by measuring the date it moved across. That tied the
     * counters to the wall clock: a job at 23:00 was evaluated or skipped depending on what time of
     * day the manager pressed the button, so week-rollover and season-rollover never ran. Counters
     * that wrap cannot be missed that way.
     */
    @Transactional
    public Map<String, Object> advanceHour() {
        GameClock clock = clock();
        int hour = (clock.getCurrentHour() == null ? 0 : clock.getCurrentHour()) + 1;
        int day = clock.getCurrentDay() == null ? GameDay.FIRST : clock.getCurrentDay();
        int week = clock.getCurrentWeek() == null ? 1 : clock.getCurrentWeek();
        int season = clock.getCurrentSeason() == null ? 1 : clock.getCurrentSeason();

        if (hour > 23) {
            hour = 0;
            day += 1;
            if (day > GameDay.LAST) {
                day = GameDay.FIRST;
                week += 1;
                if (week > SeasonService.WEEKS_PER_SEASON) {
                    week = 1;
                    season += 1;
                }
            }
        }

        clock.setCurrentHour(hour);
        clock.setCurrentDay(day);
        clock.setCurrentWeek(week);
        clock.setCurrentSeason(season);
        clock.setAdvanceOffsetSeconds(offsetOf(clock) + SECONDS_PER_HOUR);
        clock.setCurrentDate(LocalDateTime.ofInstant(gameTime(), ZoneOffset.UTC));
        clocks.save(clock);

        return afterMove(clock, hour);
    }

    /**
     * One whole day (owner, 2026-09-28).
     *
     * <p>This used to walk the remaining hours of the day through the runner <i>and then</i> call
     * {@code advanceHours} over the same range, so every hour of the day was offered twice — 48 runner
     * calls for a day that has 24. The pre-loop was there to make sure a job at 23:00 was evaluated on
     * the day it belonged to, and the worry is not real: {@code advanceHour} dispatches <b>after</b>
     * incrementing the hour but <b>before</b> rolling the day, so the step from 22:00 to 23:00 offers
     * (today, 23) and only the step past 23:00 moves the day. The pre-loop was belt and braces on a
     * belt that was already fastened.
     *
     * <p>So this is now {@code advanceHours(24)}, the same shape as {@link #advanceWeek()}, and a day
     * offers 24 positions rather than 48.
     */
    @Transactional
    public Map<String, Object> advanceDay() {
        return advanceHours(HOURS_PER_DAY);
    }

    /**
     * A whole week, as 168 real hours (owner, 2026-09-28).
     *
     * <p><b>This used to move the week counter and one day of game time, and dispatch jobs once.</b> The
     * consequences were not subtle and none of them were reported:
     *
     * <ul>
     *   <li>the day-of-week never changed, so the clock sat on whatever day it started on for ever;</li>
     *   <li>only that one day was ever offered to the job runner, so every job pinned to day 5 or 7 was
     *       skipped permanently — including the day-7 league matchday and the season rollover;</li>
     *   <li>{@code job_run} held thirteen runs, all of them at day 3, and
     *       {@code season-rollover} had never run at all, so the promotion ladder had never executed
     *       for any country.</li>
     * </ul>
     *
     * <p>It is now {@code advanceHours(168)}, which is the same stepping {@code advanceDay} already used
     * and the one that cannot skip a trigger. The owner's requirement — the week counter goes up by one —
     * still holds, and the end position still does not depend on the hour the button was pressed, because
     * 168 hours lands on the same hour and day of the next week whatever hour that started from.
     */
    @Transactional
    public Map<String, Object> advanceWeek() {
        return advanceHours(HOURS_PER_DAY * DAYS_PER_WEEK);
    }

    private int seasonYearOf(GameClock clock) {
        return clock.getCurrentSeason() == null ? 1 : clock.getCurrentSeason();
    }

    /** Runs whatever is due for the position the clock has just arrived at. */
    private Map<String, Object> afterMove(GameClock clock, int hour) {
        Map<String, Object> result = snapshot();
        result.put("jobs", jobRunner.runDue(seasonYearOf(clock),
                clock.getCurrentWeek() == null ? 1 : clock.getCurrentWeek(),
                clock.getCurrentDay() == null ? GameDay.FIRST : clock.getCurrentDay(),
                hour));
        return result;
    }

    /** N raw hours, one at a time, so no trigger is stepped over. */
    @Transactional
    public Map<String, Object> advanceHours(int hours) {
        if (hours <= 0) {
            throw new IllegalArgumentException("Advance must be a positive number of hours.");
        }
        Map<String, Object> last = null;
        for (int step = 0; step < hours; step++) {
            last = advanceHour();
        }
        return last == null ? snapshot() : last;
    }

    /**
     * Straight to a kickoff hour, e.g. 20 for day 1 internationals.
     *
     * <p>Never moves backwards: rewinding would have to undo job runs, and the done-flags are not
     * reversible. A target already passed is a no-op, not an error.
     */
    @Transactional
    public Map<String, Object> advanceToHour(int targetHour) {
        if (targetHour < 0 || targetHour > 23) {
            throw new IllegalArgumentException("Hour must be between 0 and 23.");
        }
        int current = clock().getCurrentHour() == null ? 0 : clock().getCurrentHour();
        if (targetHour <= current) {
            return snapshot();
        }
        return advanceHours(targetHour - current);
    }
}
