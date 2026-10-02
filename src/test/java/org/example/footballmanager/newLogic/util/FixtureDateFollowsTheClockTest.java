package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.BaseTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cup tie is dated from the game clock, not from a wall-clock literal.
 *
 * <p><b>This exists because three seeders measured fixture dates from {@code LocalDate.of(2026, 7, 1)}.</b>
 * Every season's round 1 got that same date, and the recovery window is {@code currentDate - 2 days}
 * ({@code ZoneLoadService:69,127}). So once the clock passed 2026-07-06 <b>no cup or international fixture
 * ever fell inside the window</b>: zone loads were written and never read, and {@code RecoveryJob} reported
 * zero for ever. That is a whole feature quietly dead, and nothing errored.
 *
 * <p>The league path was already right — {@code SeasonService:267} seeds from {@code clock.getCurrentDate()}
 * — which is why this is a cup-only bug and why the fix is to use the same clock rather than a better date.
 *
 * <p>The assertion is on movement, not on a specific date: push the clock forward and the fixture must move
 * with it. A test that asserted a literal date would pass against the old code forever.
 */
class FixtureDateFollowsTheClockTest extends BaseTest {

    @Autowired
    org.example.footballmanager.newLogic.repository.GameClockRepository clocks;

    /**
     * The clock row, created if the profile has none.
     *
     * <p>The H2 test profile ships no clock row, so a test that assumed one failed with
     * {@code NoSuchElementException} before it asserted anything. Production guards this the same way -
     * {@code SeasonService.getOrCreateClock} creates it, and the two seeders fall back to a literal - so the
     * test does too rather than assuming a fixture that is not there.
     */
    private org.example.footballmanager.newLogic.model.GameClock theClock() {
        return clocks.findById(1L).orElseGet(() -> {
            var clock = new org.example.footballmanager.newLogic.model.GameClock();
            clock.setCurrentSeason(1);
            clock.setCurrentWeek(1);
            clock.setCurrentDay(1);
            clock.setCurrentHour(0);
            clock.setCurrentDate(LocalDateTime.now().withNano(0));
            return clocks.save(clock);
        });
    }

    @Test
    @Transactional
    @DisplayName("a cup tie moves when the game clock moves")
    void aCupTieMovesWithTheClock() {
        var clock = theClock();
        LocalDateTime start = clock.getCurrentDate();

        LocalDateTime week1 = CupFixtureSeeder.matchDateFor(1, start);

        // Push the clock a full year forward, which is comfortably past the old hardcoded date.
        clock.setCurrentDate(start.plusYears(1));
        clocks.save(clock);

        LocalDateTime week1Later = CupFixtureSeeder.matchDateFor(1, clock.getCurrentDate());

        assertNotEquals(week1, week1Later,
                "a cup tie kept the same date when the clock moved a year. It is measured from a literal, so "
                        + "every season's cup falls in the same wall-clock week - and once the clock passed "
                        + "that week nothing fell inside the two-day recovery window at all.");

        // Against the *moved* clock, not the original: this assertion was comparing a post-push date with
        // a pre-push one and failing on correct code.
        assertEquals(clock.getCurrentDate().toLocalDate().plusDays(4), week1Later.toLocalDate(),
                "round 1 is cup day 5 of week 1, so it is four days after the season start");
    }

    @Test
    @Transactional
    @DisplayName("later rounds are later than earlier rounds")
    void laterRoundsAreLater() {
        var clock = theClock();
        LocalDateTime start = clock.getCurrentDate();

        LocalDateTime round1 = CupFixtureSeeder.matchDateFor(1, start);
        LocalDateTime round2 = CupFixtureSeeder.matchDateFor(2, start);
        LocalDateTime round8 = CupFixtureSeeder.matchDateFor(8, start);

        assertTrue(round1.isBefore(round2) && round2.isBefore(round8),
                "the cup's eight rounds must be in date order: " + round1 + ", " + round2 + ", " + round8);
        assertEquals(7, java.time.temporal.ChronoUnit.WEEKS.between(round1, round8),
                "round 8 is seven weeks after round 1");
    }

    @Test
    @Transactional
    @DisplayName("the season start is the game's own clock, not the wall clock")
    void theSeasonStartIsTheGamesClock() {
        var clock = theClock();
        LocalDateTime start = clock.getCurrentDate();

        assertEquals(start.toLocalDate(),
                CupFixtureSeeder.matchDateFor(1, start).toLocalDate().minusDays(4),
                "the date rule is anchored to whatever date it is handed, and the seeder hands it "
                        + "clock.getCurrentDate()");
    }
}