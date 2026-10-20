package org.tifo.footballmanager.newLogic.support;

import org.tifo.footballmanager.newLogic.model.GameClock;
import org.tifo.footballmanager.newLogic.model.GamePosition;
import org.tifo.footballmanager.newLogic.model.GameTimeline;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Builds a clock sitting on a chosen position, for tests that only care where the world is.
 *
 * <p><b>Why this exists rather than a setter.</b> The clock used to expose {@code setCurrentWeek},
 * {@code setCurrentDay}, {@code setCurrentHour} and {@code setCurrentSeason}, and a great many tests
 * used them to put the world on week 5 or day 3. Those setters are the defect: four stored counters
 * that could disagree with the time, and the header rendered a fifth thing entirely. Restoring a
 * setter "for tests" would restore the defect with the tests still green.
 *
 * <p>So a test places the world by choosing an anchor and an offset — the only two things the clock
 * actually holds — and everything else falls out of the arithmetic.
 */
public final class GameClocks {

    private GameClocks() {
    }

    /** A clock that reads as the given position right now. */
    public static GameClock at(int season, int week, int day, int hour) {
        return at(new GamePosition(season, week, day, hour));
    }

    /** A clock that reads as the given position right now. */
    public static GameClock at(GamePosition position) {
        GameClock clock = new GameClock();
        clock.setId(1L);
        clock.setAnchorDate(anchorDateFor(position));
        clock.setAdvanceOffsetSeconds(hourOffsetTo(position.hour()));
        return clock;
    }

    /** A clock on season 1, week 1, day 1 at midnight — a world that has never been advanced. */
    public static GameClock atStart() {
        return at(1, 1, 1, 0);
    }

    /**
     * Moves an existing clock onto a position, in place.
     *
     * <p>This is what a test does when the story is "the world is now in week 3". It rewrites the
     * anchor and the offset and touches nothing else, which is the same two things the game's own
     * advance touches.
     *
     * <p>Omitted arguments keep whatever the clock already had, so {@code moveTo(clock, 1, 12)} means
     * "week 12, same day and hour" without the caller having to restate them.
     */
    public static void moveTo(GameClock clock, int season, int week) {
        GamePosition current = clock.position();
        moveTo(clock, season, week, current.day(), current.hour());
    }

    public static void moveTo(GameClock clock, int season, int week, int day) {
        GamePosition current = clock.position();
        moveTo(clock, season, week, day, current.hour());
    }

    public static void moveTo(GameClock clock, int season, int week, int day, int hour) {
        clock.setAnchorDate(GameTimeline.anchorFor(LocalDate.now(GameTimeline.GAME_ZONE),
                new GamePosition(season, week, day, hour)).atStartOfDay());
        int localHour = Instant.now().atZone(GameTimeline.GAME_ZONE).getHour();
        clock.setAdvanceOffsetSeconds((hour - localHour) * 3600L);
    }

    /**
     * Puts the clock's game date on a given day.
     *
     * <p>Re-anchoring rather than storing a date: {@code current_game_date} is now derived and cannot
     * be written, which is the whole point of it no longer being a cursor.
     */
    public static void anchorTo(GameClock clock, LocalDate date) {
        GamePosition current = clock.position();
        clock.setAnchorDate(date.minusDays(
                ((long) current.season() - 1) * GameTimeline.DAYS_PER_SEASON
                        + ((long) current.week() - 1) * 7L
                        + current.day() - 1L).atStartOfDay());
    }

    private static LocalDate anchorFor(GamePosition position) {
        return GameTimeline.anchorFor(LocalDate.now(GameTimeline.GAME_ZONE), position);
    }

    private static LocalDateTime anchorDateFor(GamePosition position) {
        return anchorFor(position).atStartOfDay();
    }

    /**
     * Seconds to add to real time so that the local hour of the day reads as {@code hour}.
     *
     * <p>Negative when the current local hour is already past the wanted one, which is exactly the
     * rewind case and is fine: the clock is only ever asked "what position is it at", and the anchor
     * carries the day.
     */
    private static long hourOffsetTo(int hour) {
        int localHour = Instant.now().atZone(GameTimeline.GAME_ZONE).getHour();
        return (hour - localHour) * 3600L;
    }
}
