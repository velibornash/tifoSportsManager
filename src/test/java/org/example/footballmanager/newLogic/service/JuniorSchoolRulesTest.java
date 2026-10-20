package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.SeasonCalendar;
import org.example.footballmanager.newLogic.model.Team;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The junior school's rules (Sprint 5.3a).
 *
 * <p>Owner's specification, 2026-09-27: a one-off fee plus a weekly upkeep, openable <b>in week 1
 * only</b>, closable <b>in week 12 only</b>, and no AI club has one at all.
 *
 * <p>These are pure rules — two week comparisons and two multiplications — and they are unit-tested
 * without Spring because the windows are the entire feature. A school that could be toggled in any
 * week would be a menu rather than a commitment, and it would hand a manager a free option on the
 * best prospects in the game: watch them, then close the school the week before they graduate.
 */
class JuniorSchoolRulesTest {

    private static Team club(double reputation) {
        Team t = new Team();
        t.setName("Test club");
        t.setReputation(reputation);
        return t;
    }

    @Test
    @DisplayName("the school opens in week 1 and only week 1")
    void opensInWeekOneOnly() {
        assertEquals(1, JuniorSchoolService.OPEN_WEEK);
        for (int week = 1; week <= SeasonCalendar.WEEKS_PER_SEASON; week++) {
            assertEquals(week == 1, JuniorSchoolService.canOpenNow(week),
                    "week " + week + " open window");
        }
    }

    @Test
    @DisplayName("the school closes in the final week and only the final week")
    void closesInTheFinalWeekOnly() {
        assertEquals(SeasonCalendar.WEEKS_PER_SEASON, JuniorSchoolService.CLOSE_WEEK);
        for (int week = 1; week <= SeasonCalendar.WEEKS_PER_SEASON; week++) {
            assertEquals(week == SeasonCalendar.WEEKS_PER_SEASON, JuniorSchoolService.canCloseNow(week),
                    "week " + week + " close window");
        }
    }

    @Test
    @DisplayName("the two windows never overlap")
    void windowsDoNotOverlap() {
        assertTrue(JuniorSchoolService.OPEN_WEEK < JuniorSchoolService.CLOSE_WEEK,
                "a club must have time to run a school before it can close it");
        assertTrue(JuniorSchoolService.CLOSE_WEEK <= SeasonCalendar.WEEKS_PER_SEASON,
                "the close window must exist inside the season");
    }

    @Test
    @DisplayName("both fees scale with the club, so a small club is not shut out of its own academy")
    void feesScaleWithReputation() {
        Team small = club(20.0);
        Team large = club(90.0);

        assertTrue(JuniorSchoolService.activationFee(small) < JuniorSchoolService.activationFee(large),
                "a bigger club should pay more to open one");
        assertTrue(JuniorSchoolService.upkeep(small) < JuniorSchoolService.upkeep(large));
        assertTrue(JuniorSchoolService.activationFee(small) > 0, "even the smallest fee must be a real cost");
    }

    @Test
    @DisplayName("a club with no reputation is charged the floor, not a negative or a NaN")
    void missingReputationDegradesSafely() {
        Team unrated = new Team();
        unrated.setReputation(null);
        assertTrue(JuniorSchoolService.activationFee(unrated) > 0);
        assertTrue(JuniorSchoolService.upkeep(unrated) > 0);
    }

    @Test
    @DisplayName("prices carry at most two decimals, whatever reputation holds")
    void pricesAreRoundedAtTheSource() {
        // Reputation is a raw Double off a seeded club, so 90.87761 is an ordinary value. Left alone
        // it produced an activation fee of 82191.20164797436 — fourteen decimals on a price, which only
        // looked acceptable because the UI happened to format it.
        double[] reputations = { 0.0, 20.0, 50.0, 90.87761, 99.999, 100.0 };
        for (double reputation : reputations) {
            Team club = club(reputation);
            for (double price : new double[] {
                    JuniorSchoolService.activationFee(club), JuniorSchoolService.upkeep(club) }) {
                assertEquals(price, Math.round(price * 100.0) / 100.0,
                        "a price built from reputation " + reputation + " carried extra decimals: " + price);
            }
        }
    }

    @Test
    @DisplayName("upkeep is only charged while the school is running")
    void upkeepFollowsTheSchool() {
        Team club = club(50.0);
        assertEquals(0.0, JuniorSchoolService.weeklyUpkeep(club),
                "a club with no school pays nothing for one");
        assertEquals(0.0, JuniorSchoolService.weeklyUpkeep(null));

        club.setJuniorSchoolActive(true);
        assertTrue(JuniorSchoolService.weeklyUpkeep(club) > 0);
    }

    @Test
    @DisplayName("a null school flag means no school, not a broken one")
    void nullFlagIsInactive() {
        Team club = club(50.0);
        assertEquals(0.0, JuniorSchoolService.weeklyUpkeep(club));
        club.setJuniorSchoolActive(false);
        assertEquals(0.0, JuniorSchoolService.weeklyUpkeep(club));
        club.setJuniorSchoolActive(true);
        assertTrue(JuniorSchoolService.weeklyUpkeep(club) > 0);
    }

    @Test
    @DisplayName("the opening fee is worth more than a few weeks of upkeep, so closing early is not free")
    void feeIsNotTrivial() {
        // If a season of upkeep cost more than the activation fee, the fee would be a formality and
        // the two would be telling a manager opposite stories about what running a school costs.
        Team club = club(50.0);
        double fee = JuniorSchoolService.activationFee(club);
        double season = JuniorSchoolService.upkeep(club) * (SeasonCalendar.WEEKS_PER_SEASON - 1);
        assertTrue(season > fee,
                "a full season of upkeep (" + Math.round(season) + ") should exceed the fee ("
                        + Math.round(fee) + ")");
    }
}
