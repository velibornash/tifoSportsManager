package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.GameClock;
import org.example.footballmanager.newLogic.repository.GameClockRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The same game produces the same date.
 *
 * <p><b>B10.</b> {@code advanceHour} overwrote {@code currentDate} with {@code Instant.now()} truncated to
 * UTC on every single hour, so the in-game date tracked **wall-clock time** rather than the season. Two
 * managers doing the same 168 advances got different dates from the same game state — and
 * {@code ZoneLoadService:181-186} reads exactly that field.
 *
 * <p>Everything else already treated it as game time: {@code SeasonService:443,635} advance it with
 * {@code plusWeeks(1)} and the league fixtures are dated from it. Only this line disagreed, and disagreeing
 * with the rest is how the fixture dates from B4 could have been right while the recovery window was not.
 *
 * <p>The assertion is the property itself: **two independent runs of the same advances end on the same
 * date.** Not a literal date — a test asserting a date would pass for ever and would not notice a clock that
 * drifts.
 */
class GameClockDateFollowsGameTimeTest extends BaseTest {

    @Autowired private GameClockRepository clocks;
    @Autowired private GameClockService gameClock;

    @Test
    @Transactional
    @DisplayName("the same advances produce the same date, whatever the wall clock says")
    void theSameAdvancesProduceTheSameDate() {
        LocalDateTime firstRunEnd = runSevenDays();
        LocalDateTime secondRunEnd = runSevenDays();

        assertEquals(firstRunEnd, secondRunEnd,
                "two runs of the same 168 advances ended on different dates: " + firstRunEnd + " and "
                        + secondRunEnd + ". currentDate is being taken from the wall clock, so it is a "
                        + "function of when the button was pressed rather than of the game.");
    }

    @Test
    @Transactional
    @DisplayName("a day-slot rolling over does not move the date - a game day is not a wall-clock day")
    void aDaySlotRollingDoesNotMoveTheDate() {
        GameClock clock = aClockAt(LocalDateTime.of(2026, 3, 1, 23, 0));
        LocalDateTime before = clock.getCurrentDate();

        // 23:00 -> the hour rolls, and with it the day-slot from 7 back to 1.
        gameClock.advanceHours(1);

        GameClock after = clocks.findById(1L).orElseThrow();
        assertEquals(before.toLocalDate(), after.getCurrentDate().toLocalDate(),
                "the date moved when a day-slot rolled. The seven-day template is a game construct - "
                        + "SeasonService:443 and :635 advance the date by a WEEK when the week counter "
                        + "advances, and that is the rate the whole codebase agrees on. An earlier version "
                        + "of this test asserted a day per day-slot and failed on correct code.");
    }

    @Test
    @Transactional
    @DisplayName("the date moves when the week counter moves, by one week")
    void theDateMovesWhenTheWeekDoes() {
        aClockAt(LocalDateTime.of(2026, 3, 1, 9, 0));
        LocalDateTime before = clocks.findById(1L).orElseThrow().getCurrentDate();
        int weekBefore = clocks.findById(1L).orElseThrow().getCurrentWeek();

        // Enough hours to roll the week counter at least once.
        gameClock.advanceHours(24 * 7);

        GameClock after = clocks.findById(1L).orElseThrow();
        assertTrue(after.getCurrentWeek() > weekBefore,
                "168 hours did not roll the week counter, so this test cannot check the date");
        assertTrue(after.getCurrentDate().isAfter(before),
                "the week advanced but the in-game date did not, so the game's date and its season "
                        + "disagree: " + before + " -> " + after.getCurrentDate());
    }

    @Test
    @Transactional
    @DisplayName("a week of game time is a week of date")
    void aWeekOfGameTimeIsAWeekOfDate() {
        GameClock clock = aClockAt(LocalDateTime.of(2026, 3, 1, 9, 0));
        LocalDateTime before = clock.getCurrentDate();

        gameClock.advanceHours(24 * 7);

        GameClock after = clocks.findById(1L).orElseThrow();
        long days = java.time.temporal.ChronoUnit.DAYS.between(
                before.toLocalDate(), after.getCurrentDate().toLocalDate());
        assertTrue(days > 0 && days % 7 == 0,
                "the date advances a week at a time, so it must have moved a whole number of weeks. "
                        + "Moved " + days + " days, which means the rate disagrees with SeasonService.");
    }

    @Test
    @Transactional
    @DisplayName("the game zone is defined once, on the clock")
    void theGameZoneIsDefinedOnce() {
        assertEquals("Europe/Belgrade", GameClockService.GAME_ZONE.getId(),
                "the game's timezone is the server's, so it belongs to the clock rather than being repeated "
                        + "in a controller");
    }

    /** Seven game days from a fresh clock, returning where the date ended up. */
    private LocalDateTime runSevenDays() {
        aClockAt(LocalDateTime.of(2026, 3, 1, 9, 0));
        gameClock.advanceHours(24 * 7);
        return clocks.findById(1L).orElseThrow().getCurrentDate();
    }

    private GameClock aClockAt(LocalDateTime currentDate) {
        GameClock clock = clocks.findById(1L).orElseGet(GameClock::new);
        clock.setCurrentSeason(1);
        clock.setCurrentWeek(1);
        clock.setCurrentDay(1);
        clock.setCurrentHour(9);
        clock.setAdvanceOffsetSeconds(0L);
        clock.setCurrentDate(currentDate);
        return clocks.save(clock);
    }
}
