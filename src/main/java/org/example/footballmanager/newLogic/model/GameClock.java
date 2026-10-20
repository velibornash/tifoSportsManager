package org.example.footballmanager.newLogic.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The game clock (owner, 2026-09-28, rebuilt 2026-10-11).
 *
 * <p><b>This entity stores a knob and an anchor. It does not store the time.</b>
 *
 * <p>It used to store both, which is why the game had two clocks. {@code advanceOffsetSeconds} decided
 * what the header rendered — {@code now() + offset}, ticking in real seconds — while four counters
 * ({@code currentSeason}, {@code currentWeek}, {@code currentDay}, {@code currentHour}) decided what the
 * job runner believed, and only moved when somebody pressed a button. They could not be kept in step:
 * the header read 16:20 while the jobs thought it was 09:00, and nothing anywhere logged a word about it.
 *
 * <p>Now:
 *
 * <pre>
 *   gameTime = now() + advanceOffsetSeconds          // the clock: real seconds, movable either way
 *   position = GameTimeline.positionAt(anchor, gameTime)   // season / week / day / hour
 * </pre>
 *
 * <p>The getters below are <b>derived</b> and keep their old names so the twenty-odd readers across the
 * codebase keep working — but there is no longer a setter for any of them, because a value that cannot
 * be written cannot be the second source of truth. The only way to move the game is
 * {@link #setAdvanceOffsetSeconds(Long)}.
 */
@Entity(name = "GameClock")
@Getter
@Setter
public class GameClock {

    @Id
    private Long id = 1L;

    /**
     * The knob. Seconds to add to real time to get game time (owner, 2026-10-11).
     *
     * <p>Positive moves the world on; negative moves it back. It is the <em>only</em> mutable thing on
     * this row, and it is what "Advance Day", "Advance Hour", the admin offset control and the
     * synchroniser all write.
     *
     * <p>Game time is {@code now() + advanceOffsetSeconds} rather than a stored hour precisely so the
     * clock ticks: a stored hour would be overwritten by the next render, taking the jump with it.
     */
    @Column(name = "advance_offset_seconds")
    private Long advanceOffsetSeconds = 0L;

    /**
     * The day season 1, week 1, day 1 sits on (owner, 2026-10-11).
     *
     * <p>This is not a calendar date the game plays by — a date is not a category in this world. It is
     * the origin the cycle is counted from, and nothing displays it. The one thing it does is make the
     * position reversible: {@link GameTimeline#anchorFor} turns a stored position back into an anchor, so
     * a world that already existed keeps the exact position it had when its counters were retired.
     */
    @Column(name = "anchor_date")
    private LocalDateTime anchorDate;

    /**
     * The game instant the job synchroniser last completed a walk from (owner, 2026-10-11).
     *
     * <p>This is what makes "run everything due <em>or that did not run last time</em>" possible. The
     * synchroniser walks every game hour between this and now, so an app that was down over a kickoff
     * comes back and plays the matchday it missed instead of stepping over it. Without it, a clock that
     * moves on its own and a runner that only looks at the present position lose a day every time
     * anything stops.
     */
    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    private SeasonPhase currentPhase;

    // --- retired columns, read once by the migration in SeasonService and never written again ---
    //
    // They are mapped rather than dropped so the migration can read the position a world was actually
    // on, and left in the table afterwards because dropping a column that a restored backup still
    // carries is not worth a migration failure. Nothing reads them except `legacyPosition`.

    @Column(name = "current_season")
    private Integer legacySeason;

    @Column(name = "current_week")
    private Integer legacyWeek;

    @Column(name = "current_day")
    private Integer legacyDay;

    @Column(name = "current_hour")
    private Integer legacyHour;

    @Column(name = "current_game_date")
    private LocalDateTime legacyGameDate;

    /**
     * The position this world was on before the counters were retired.
     *
     * <p>Falls back to season 1, week 1, day 1 for a world that never had them, which is the same place
     * it would have started. A calendar year in {@code current_season} means the row predates the
     * season-number scheme; every such world was still on its first season.
     */
    @Transient
    public GamePosition legacyPosition() {
        int season = legacySeason == null || legacySeason < 1 || legacySeason > 1000
                ? 1 : legacySeason;
        int week = legacyWeek == null || legacyWeek < 1 || legacyWeek > SeasonCalendar.WEEKS_PER_SEASON
                ? 1 : legacyWeek;
        int day = legacyDay == null || legacyDay < 1 || legacyDay > GameDay.LAST ? 1 : legacyDay;
        int hour = legacyHour == null || legacyHour < 0 || legacyHour > 23 ? 9 : legacyHour;
        return new GamePosition(season, week, day, hour);
    }

    // --- derived. No setters: a value that cannot be written cannot drift. ---

    /**
     * Game time: real time plus everything the owner has moved the clock by.
     *
     * <p>The instant is a parameter rather than {@code Instant.now()} inside, because the arithmetic
     * has to be testable against a clock the test controls. A game clock whose only input is the wall
     * clock cannot be asserted on at a boundary — "advance 168 hours and land on the same hour" passes
     * for 363 days of the year and fails on the two daylight-saving nights, which is the worst kind of
     * test: right almost always, and wrong for reasons nobody can see.
     */
    @Transient
    public Instant gameTimeAt(Instant now) {
        return now.plusSeconds(offset());
    }

    /** Where the game is at a given instant. */
    @Transient
    public GamePosition positionAt(Instant now) {
        return GameTimeline.positionAt(anchor(), gameTimeAt(now));
    }

    @Transient
    public LocalDateTime gameDateTimeAt(Instant now) {
        return LocalDateTime.ofInstant(gameTimeAt(now), GameTimeline.GAME_ZONE);
    }

    /** Game time as of now. */
    @Transient
    public Instant gameTime() {
        return gameTimeAt(Instant.now());
    }

    @Transient
    public LocalDateTime gameDateTime() {
        return gameDateTimeAt(Instant.now());
    }

    @Transient
    public LocalDate gameDate() {
        return gameDateTime().toLocalDate();
    }

    /** Where the game is right now. */
    @Transient
    public GamePosition position() {
        return positionAt(Instant.now());
    }

    @Transient
    public Integer getCurrentSeason() {
        return position().season();
    }

    @Transient
    public Integer getCurrentWeek() {
        return position().week();
    }

    /**
     * Day within the seven-day cycle, 1..7.
     *
     * <p>A <b>game</b> day, not a weekday. It maps positionally onto {@link WeekTemplate.DayKind}:
     * 1 is INTERNATIONAL, 2 FINANCE, 3 LEAGUE, 4 TRAINING, 5 CUP, 6 MORALE, 7 LEAGUE_SECOND.
     */
    @Transient
    public Integer getCurrentDay() {
        return position().day();
    }

    /** Hour of the current game day, 0..23. */
    @Transient
    public Integer getCurrentHour() {
        return position().hour();
    }

    /** Minute of the current game hour. The day-1 international slot is 20:45 and must say so. */
    @Transient
    public Integer getCurrentMinute() {
        return gameDateTime().getMinute();
    }

    /**
     * The game date and time, for the code that needs a timestamp to file things against.
     *
     * <p><b>This is not a cursor and must not be written.</b> It is read, it is derived, and the only
     * legitimate use is "stamp this fixture / this ledger line with where the game is". The old
     * behaviour — {@code plusWeeks(1)} at week rollover, {@code LocalDateTime.now()} at creation — was a
     * second clock that disagreed with the first, which is what it was retired for.
     */
    @Transient
    public LocalDateTime getCurrentDate() {
        return gameDateTime();
    }

    /** A null offset is a row written before the column existed; treat it as no offset at all. */
    @Transient
    public long offset() {
        Long offset = getAdvanceOffsetSeconds();
        return offset == null ? 0L : offset;
    }

    /**
     * The anchor, or a defensible one if the row predates it.
     *
     * <p>{@code null} is a world built before the counters were retired. Defaulting to today would drop
     * that world to season 1 week 1 day 1 and re-run a season it has already played, so
     * {@code SeasonService} migrates those rows once and writes the anchor back; this fallback only
     * covers the window before that write lands.
     */
    @Transient
    public LocalDate anchor() {
        return anchorDate == null ? gameDate() : anchorDate.toLocalDate();
    }
}
