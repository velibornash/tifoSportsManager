package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Junior;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tenure clock: how many weeks of his one season a junior has been observed for
 * (owner rule, 2026-10-08).
 *
 * <p>Pure arithmetic, so it is tested without a context and without a database. It is worth a test of
 * its own because it is the one piece of the narrowing model that reads the calendar, and the failure
 * mode is silent: a wrong offset does not throw, it just shows a manager a band that is tighter or
 * wider than the club has earned, which looks like a design choice rather than a bug.
 */
class JuniorTenureClockTest {

    private static Junior arrived(int season, int week) {
        Junior j = new Junior();
        j.setArrivalSeasonNumber(season);
        j.setArrivalWeekNumber(week);
        return j;
    }

    private static int weeks(Junior j, int season, int week) {
        return YouthAcademyService.weeksObserved(j, season, week);
    }

    @Test
    @DisplayName("the whole tenure is 11 weeks: intake in week 2, decided in week 1 of the next season")
    void tenureLengthMatchesTheOwnersCycle() {
        assertEquals(11, YouthAcademyService.TENURE_WEEKS);
        assertEquals(11, weeks(arrived(4, 2), 5, 1),
                "season 4 week 2 to season 5 week 1 is eleven weeks");
    }

    @Test
    @DisplayName("zero weeks are observed on the arrival week itself")
    void nothingObservedOnArrival() {
        assertEquals(0, weeks(arrived(4, 2), 4, 2));
    }

    @Test
    @DisplayName("the season runs out in front of him: ten weeks of training before the decision")
    void trainingWeeksAreCountedAcrossTheSeasonBoundary() {
        // The whole point of flattening season and week onto one line. Counting within the season would
        // report 1 week at season 5 week 1 and never converge.
        assertEquals(1, weeks(arrived(4, 2), 4, 3), "the first training week, one week after arrival");
        assertEquals(5, weeks(arrived(4, 2), 4, 7));
        assertEquals(10, weeks(arrived(4, 2), 4, 12), "the last week of his academy season");
        assertEquals(11, weeks(arrived(4, 2), 5, 1), "the decision week");
    }

    @Test
    @DisplayName("observation never exceeds the tenure, however overdue a junior is")
    void overdueJuniorsAreClampedToTheTenure() {
        assertEquals(11, weeks(arrived(4, 2), 5, 12));
        assertEquals(11, weeks(arrived(4, 2), 6, 1));
        assertEquals(11, weeks(arrived(4, 2), 40, 6));
    }

    @Test
    @DisplayName("a date before his arrival claims nothing rather than going negative")
    void datesBeforeArrivalAreClampedToZero() {
        assertEquals(0, weeks(arrived(4, 2), 4, 1));
        assertEquals(0, weeks(arrived(4, 2), 3, 7));
    }

    @Test
    @DisplayName("a legacy row with no recorded arrival is reported as unobserved, never as certain")
    void unrecordedArrivalIsTheWidestReport() {
        // arrivalSeasonNumber and arrivalWeekNumber are primitive ints, so a row written before the
        // columns existed reads as 0 rather than null. Season 0 is far in the past, so an unguarded
        // subtraction would return a huge number and hand every such row a confident ±1 — the exact
        // leak the band exists to prevent. This is the branch that stops it.
        assertEquals(0, weeks(arrived(0, 0), 7, 4));
        assertEquals(0, weeks(arrived(0, 0), 7, 12));
        assertEquals(0, weeks(arrived(5, 0), 7, 4), "a season with no arrival week is equally unusable");
        assertEquals(0, weeks(new Junior(), 7, 4));
        assertEquals(0, YouthAcademyService.weeksObserved(null, 7, 4));
    }

    @Test
    @DisplayName("two clubs watching the same prospect a season apart see different bands")
    void theBandMovesAsTheSeasonRuns() {
        // Walks the whole thing end to end, because the interesting property is not any single week but
        // that a report only tightens: widest on arrival, converged by the decision.
        Junior j = arrived(6, 2);
        double previous = Double.MAX_VALUE;
        for (int season = 6; season <= 7; season++) {
            for (int week = 1; week <= 12; week++) {
                if (season == 6 && week < 2) continue;
                int observed = weeks(j, season, week);
                Junior prospect = new Junior();
                prospect.setTalentRangeHalfWidth(4.0);
                double width = TalentRange.currentHalfWidth(
                        prospect, observed, YouthAcademyService.TENURE_WEEKS, null);
                assertTrue(width <= previous,
                        "the band widened at season " + season + " week " + week + ": " + width);
                previous = width;
            }
        }
        assertEquals(TalentRange.FINAL_HALF_WIDTH, previous,
                "by the end of the tenure the report must have converged");
    }
}