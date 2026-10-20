package org.tifo.footballmanager.newLogic.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * The arithmetic from a point in time to a position in the game cycle.
 *
 * <p><b>Why this exists (owner, 2026-10-11).</b> The clock used to keep two things and call them one:
 *
 * <ul>
 *   <li>{@code advanceOffsetSeconds}, from which {@code now() + offset} gave a time that ticked in real
 *       seconds and was rendered in the header;</li>
 *   <li>{@code currentHour} / {@code currentDay} / {@code currentWeek} / {@code currentSeason}, four
 *       stored counters that only moved when somebody pressed a button, and which every job read.</li>
 * </ul>
 *
 * <p>Those two disagreed, and no amount of care in either kept them in step: the header could read
 * {@code 16:20} while the jobs believed it was 09:00 of day 4. Nothing throws when they diverge — the
 * header is simply wrong and the jobs fire at the wrong time.
 *
 * <p>So there is now one clock and one position. The clock is real time plus an offset the owner can
 * move either way; the position is a pure function of it. There is nothing to reconcile, because there
 * is nothing stored to reconcile.
 *
 * <h2>How the position is read off the clock</h2>
 *
 * <p>Days are counted from {@code anchor}, the day season 1 / week 1 / day 1 sits on. A day rolls at
 * <b>local midnight</b>, so the day on screen and the time on screen always agree — the alternative,
 * counting fixed 24-hour blocks, drifts by an hour across a daylight-saving change and then the header
 * claims midnight while the day counter has not moved.
 *
 * <pre>
 *   season = days / 84 + 1
 *   week   = (days / 7) % 12 + 1
 *   day    = days % 7 + 1
 *   hour   = the hour of day in Europe/Belgrade
 * </pre>
 */
public final class GameTimeline {

    private GameTimeline() {
    }

    /**
     * The zone the game clock reads its time in.
     *
     * <p>Belgrade, because that is the game's time and not the server's. One definition: the renderer,
     * the API and this arithmetic all use it, so a header that disagrees with the job runner is not
     * something that can happen.
     */
    public static final ZoneId GAME_ZONE = ZoneId.of("Europe/Belgrade");

    /** A season is twelve weeks of seven days. Read from the calendar rather than restated. */
    public static final int DAYS_PER_SEASON = SeasonCalendar.WEEKS_PER_SEASON * GameDay.LAST;

    /** Game hours in a day. */
    public static final int HOURS_PER_DAY = 24;

    /** Where the game is at a given instant. */
    public static GamePosition positionAt(LocalDate anchor, Instant instant) {
        LocalDate today = instant.atZone(GAME_ZONE).toLocalDate();
        long days = ChronoUnit.DAYS.between(anchor, today);
        int season = (int) Math.floorDiv(days, DAYS_PER_SEASON) + 1;
        int week = (int) Math.floorMod(days / GameDay.LAST, SeasonCalendar.WEEKS_PER_SEASON) + 1;
        int day = (int) Math.floorMod(days, GameDay.LAST) + 1;
        int hour = instant.atZone(GAME_ZONE).getHour();
        return new GamePosition(season, week, day, hour);
    }

    /**
     * The position as a single running hour count, so positions can be added to and compared.
     *
     * <p>This is what makes the job catch-up walk honest: "how many game hours have we not looked at"
     * is one subtraction rather than a walk through the day/week/season wrap rules.
     */
    public static long hourIndex(GamePosition position) {
        long days = ((long) position.season() - 1) * DAYS_PER_SEASON
                + ((long) position.week() - 1) * GameDay.LAST
                + (position.day() - 1);
        return days * HOURS_PER_DAY + position.hour();
    }

    /** The position a running hour count lands on. The inverse of {@link #hourIndex}. */
    public static GamePosition atHourIndex(long index) {
        long days = Math.floorDiv(index, HOURS_PER_DAY);
        int hour = (int) Math.floorMod(index, HOURS_PER_DAY);
        int season = (int) Math.floorDiv(days, DAYS_PER_SEASON) + 1;
        int week = (int) Math.floorMod(days / GameDay.LAST, SeasonCalendar.WEEKS_PER_SEASON) + 1;
        int day = (int) Math.floorMod(days, GameDay.LAST) + 1;
        return new GamePosition(season, week, day, hour);
    }

    /**
     * The anchor that puts {@code position} on {@code today}.
     *
     * <p>Used once, to give an existing world its anchor without moving it: the position the counters
     * already held becomes the position the clock reports, so replacing the counters with the anchor is
     * a change of representation and not a change of state.
     */
    public static LocalDate anchorFor(LocalDate today, GamePosition position) {
        long days = ((long) position.season() - 1) * DAYS_PER_SEASON
                + ((long) position.week() - 1) * GameDay.LAST
                + (position.day() - 1);
        return today.minusDays(days);
    }

    /**
     * When a fixture in a given week and day is kicked off (owner, 2026-10-11).
     *
     * <p><b>One answer to "when does this slot play", read from the template.</b> Three writers were
     * each answering it for themselves and getting different answers:
     *
     * <ul>
     *   <li>the league read {@link WeekTemplate} and got it right;</li>
     *   <li>the national cup hardcoded day 5 at 18:00;</li>
     *   <li>the fifteen continental cups wrote {@code dayNumber = 1} but took their <i>date</i> from
     *       the national cup's rule — so every one of them reported day 1 and carried a day-5,
     *       18:00 timestamp. The manager saw a tie at 18:00 and nothing happened at 18:00.</li>
     * </ul>
     *
     * <p>The day and the hour have to come from the same place, because a fixture whose day column says
     * 1 and whose date says day 5 is not a fixture the game can schedule — and the mismatch is invisible
     * until a manager watches a kickoff time come and go with nothing played.
     *
     * <p>A day with no fixture in the template (finance, training, morale) has no kickoff and returns
     * noon. That is a fallback, not a decision: nothing schedules on those days.
     */
    public static LocalDateTime kickoffAt(LocalDate anchor, int week, int day) {
        LocalDate weekStart = anchor.plusWeeks(week - 1L);
        LocalDate date = weekStart.plusDays(day - 1L);
        GameDay gameDay = GameDay.of(day);
        Integer hour = gameDay.kickoffHour();
        Integer minute = gameDay.kickoffMinute();
        return LocalDateTime.of(date,
                LocalTime.of(hour == null ? 12 : hour, minute == null ? 0 : minute));
    }
}
