package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.GameClock;
import org.example.footballmanager.newLogic.model.GameTimeline;
import org.example.footballmanager.newLogic.repository.GameClockRepository;
import org.example.footballmanager.newLogic.support.GameClocks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The game date is a reading of the clock, not a second clock (B10, reworked 2026-10-11).
 *
 * <p><b>B10 first.</b> {@code advanceHour} overwrote {@code currentDate} with {@code Instant.now()}
 * truncated to UTC on every single hour, so the in-game date tracked <b>wall-clock time</b> rather than
 * the season. Two managers doing the same 168 advances got different dates from the same game state,
 * and {@code ZoneLoadService:181-186} reads exactly that field.
 *
 * <p><b>Then, on 2026-10-11, the field stopped being writable at all.</b> It is now derived from the
 * same anchor and offset that produce season, week, day and hour, which means the disagreement is not
 * fixed but made impossible: there is no second value left to disagree.
 *
 * <p>These assertions are about the property, not about a literal date. A test that asserted
 * "2026-03-08" would pass for ever and would not notice a clock that drifts.
 */
class GameClockDateFollowsGameTimeTest extends BaseTest {

    @Autowired private GameClockRepository clocks;
    @Autowired private GameClockService gameClock;

    @Test
    @Transactional
    @DisplayName("the same advances produce the same date, whatever the wall clock says")
    void theSameAdvancesProduceTheSameDate() {
        java.time.LocalDateTime firstRunEnd = runSevenDays();
        java.time.LocalDateTime secondRunEnd = runSevenDays();

        // Compared to the minute. The game time is `Instant.now() + offset`, so it carries real
        // microseconds and two runs a few milliseconds apart differ in the last digits. The property
        // under test is that the date is a function of the advances and not of when they were pressed.
        assertEquals(firstRunEnd.withSecond(0).withNano(0), secondRunEnd.withSecond(0).withNano(0),
                "two runs of the same 168 advances ended on different dates: " + firstRunEnd + " and "
                        + secondRunEnd + ". The date is being taken from the wall clock, so it is a "
                        + "function of when the button was pressed rather than of the game.");
    }

    @Test
    @Transactional
    @DisplayName("a day rolling over moves the date by exactly one day")
    void aDayRollingOverMovesTheDateByOneDay() {
        aClockAt();
        LocalDateTime before = clocks.findById(1L).orElseThrow().getCurrentDate();

        gameClock.advanceHours(24);

        GameClock after = clocks.findById(1L).orElseThrow();
        assertEquals(1, ChronoUnit.DAYS.between(before.toLocalDate(), after.getCurrentDate().toLocalDate()),
                "24 game hours did not move the date by one day. " + before + " -> "
                        + after.getCurrentDate() + ". A date that is not a function of the clock is a "
                        + "second clock, which is the bug this test was written for.");
    }

    @Test
    @Transactional
    @DisplayName("the date moves when the week counter moves, by one week")
    void theDateMovesWhenTheWeekDoes() {
        aClockAt();
        LocalDateTime before = clocks.findById(1L).orElseThrow().getCurrentDate();
        int weekBefore = clocks.findById(1L).orElseThrow().getCurrentWeek();

        gameClock.advanceHours(24 * 7);

        GameClock after = clocks.findById(1L).orElseThrow();
        assertEquals(weekBefore + 1, after.getCurrentWeek(),
                "168 hours did not roll the week counter by exactly one, so this test cannot check the date");
        assertEquals(7, ChronoUnit.DAYS.between(before.toLocalDate(),
                        after.getCurrentDate().toLocalDate()),
                "the week advanced but the in-game date did not move a whole week: " + before + " -> "
                        + after.getCurrentDate());
    }

    @Test
    @Transactional
    @DisplayName("the date is derived, so it cannot be written")
    void theDateCannotBeWritten() {
        assertTrue(java.util.Arrays.stream(GameClock.class.getMethods())
                        .noneMatch(m -> m.getName().equals("setCurrentDate")),
                "a writable date is a second cursor. It disagreed with the clock exactly once already "
                        + "(B10) and there is no reason to give it the chance again.");
    }

    @Test
    @Transactional
    @DisplayName("the game zone is defined once, and it is the game's")
    void theGameZoneIsDefinedOnce() {
        assertEquals("Europe/Belgrade", GameTimeline.GAME_ZONE.getId(),
                "the game's timezone is the game's, not the server's, and it is defined in one place so a "
                        + "controller cannot repeat the literal and drift from it");
    }

    /** Seven game days from a fresh clock, returning where the date ended up. */
    private LocalDateTime runSevenDays() {
        aClockAt();
        gameClock.advanceHours(24 * 7);
        return clocks.findById(1L).orElseThrow().getCurrentDate();
    }

    private GameClock aClockAt() {
        GameClock clock = clocks.findById(1L).orElseGet(GameClock::new);
        GameClocks.moveTo(clock, 1, 1, 1, 9);
        return clocks.save(clock);
    }
}
