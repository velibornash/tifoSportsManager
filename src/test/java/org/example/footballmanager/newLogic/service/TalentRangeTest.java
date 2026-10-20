package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Junior;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The talent report's width and how it narrows (Sprint 5.2, owner rule 2026-10-08).
 *
 * <p>The owner's numbers are pinned exactly: intake is {@code ±(1 + rnd(0..3))}, promotion is
 * {@code ±1}, and a better youth coach narrows faster. Every one of those is a decision, not a
 * derived figure, so a test is the only thing that stops a later "cleanup" quietly changing what a
 * manager is told about his own academy.
 *
 * <p><b>The clock is the season, not the age</b> (owner, 2026-10-08). Progress used to be measured in
 * ages over {@code graduationAge - arrivalAge}. Tenure is now exactly one season, so that span is
 * zero or one and the estimate would have jumped from widest to ±1 on arrival, handing over the exact
 * ceiling for free. These tests are written on weeks of the tenure, and
 * {@link #arrivalAgeNoLongerDrivesTheBand()} exists specifically to fail if that regresses.
 */
class TalentRangeTest {

    /** The tenure the owner specified, restated here so a change to the service constant is visible. */
    private static final int TENURE = YouthAcademyService.TENURE_WEEKS;

    private static Junior junior(Double intakeHalfWidth) {
        Junior j = new Junior();
        j.setTalentRangeHalfWidth(intakeHalfWidth);
        return j;
    }

    private static double widthAt(double intake, int weeksObserved, Integer coachDev) {
        return TalentRange.currentHalfWidth(junior(intake), weeksObserved, TENURE, coachDev);
    }

    @Test
    @DisplayName("intake width is 1 + a roll of 0..3, so the band spans ±1 to ±4")
    void intakeWidthSpansOneToFour() {
        assertEquals(1.0, TalentRange.intakeHalfWidth(0));
        assertEquals(2.0, TalentRange.intakeHalfWidth(1));
        assertEquals(3.0, TalentRange.intakeHalfWidth(2));
        assertEquals(4.0, TalentRange.intakeHalfWidth(3));
        assertEquals(4.0, TalentRange.maxHalfWidth());
    }

    @Test
    @DisplayName("a roll outside 0..3 is clamped rather than trusted")
    void intakeWidthClampsBadRolls() {
        assertEquals(1.0, TalentRange.intakeHalfWidth(-5), "a negative roll is a bug, not a tighter band");
        assertEquals(4.0, TalentRange.intakeHalfWidth(99));
    }

    @Test
    @DisplayName("a report starts at its intake width and has converged by the decision week")
    void narrowsFromIntakeToOne() {
        assertEquals(4.0, widthAt(4.0, 0, null),
                "on arrival the report is exactly the rolled width");
        assertEquals(1.0, widthAt(4.0, TENURE, null),
                "by the week the manager decides, the report has converged to ±1");
    }

    @Test
    @DisplayName("the tenure is 11 weeks: intake in week 2, decided in week 1 of the next season")
    void tenureIsElevenWeeks() {
        // 12-week season, arrival in week 2, decision in week 1 of the following season:
        //   (12 + 1) - 2 = 11
        assertEquals(11, YouthAcademyService.TENURE_WEEKS);
        assertEquals(2, YouthAcademyService.INTAKE_WEEK);
        assertEquals(1, YouthAcademyService.DECISION_WINDOW_LAST_WEEK,
                "the decision window is a single week");
    }

    @Test
    @DisplayName("the width narrows week by week, never rises, and never falls below ±1")
    void narrowsWeekByWeekAndStopsAtOne() {
        Junior j = junior(4.0);
        double previous = Double.MAX_VALUE;
        for (int week = 0; week <= TENURE; week++) {
            double width = TalentRange.currentHalfWidth(j, week, TENURE, null);
            assertTrue(width <= previous, "width must not widen at week " + week + ": " + width);
            assertTrue(width >= TalentRange.FINAL_HALF_WIDTH,
                    "width must never go below ±1 at week " + week + ": " + width);
            previous = width;
        }
        assertEquals(1.0, previous, "at the end of the tenure the report sits at ±1");
    }

    @Test
    @DisplayName("the coach's narrowing is visible week by week, not only at the end")
    void everyTrainingWeekNarrowsTheBand() {
        // The owner asked for the estimate to firm up *during* the season. A model that only narrowed
        // at the boundary would produce the same endpoints as this one and still be wrong, so this
        // walks the weeks and requires a strict decrease rather than checking arrival and exit only.
        Junior j = junior(4.0);
        for (int week = 0; week < TENURE; week++) {
            double now = TalentRange.currentHalfWidth(j, week, TENURE, null);
            double next = TalentRange.currentHalfWidth(j, week + 1, TENURE, null);
            assertTrue(next < now,
                    "week " + week + " -> " + (week + 1) + " did not narrow: " + now + " -> " + next);
        }
    }

    @Test
    @DisplayName("a better youth coach narrows the same report faster")
    void coachNarrowsFaster() {
        int midSeason = Math.max(1, TENURE / 2);
        double noCoach = widthAt(4.0, midSeason, null);
        double averageCoach = widthAt(4.0, midSeason, 10);
        double goodCoach = widthAt(4.0, midSeason, 20);

        assertTrue(goodCoach < averageCoach, "a 20 development coach must beat a 10: "
                + goodCoach + " vs " + averageCoach);
        assertTrue(averageCoach < noCoach, "a coach must beat no coach: " + averageCoach + " vs " + noCoach);
    }

    @Test
    @DisplayName("the coach can only ever help — the best coach never beats the ±1 floor")
    void coachNeverWidensAndNeverBeatsTheFloor() {
        Junior j = junior(4.0);
        for (int week = 0; week <= TENURE; week++) {
            double best = TalentRange.currentHalfWidth(j, week, TENURE, 20);
            double worst = TalentRange.currentHalfWidth(j, week, TENURE, 1);
            assertTrue(best <= worst, "coach must never widen at week " + week);
            assertTrue(best >= TalentRange.FINAL_HALF_WIDTH, "coach must never beat the ±1 floor at week " + week);
        }
        assertEquals(1.0, TalentRange.currentHalfWidth(j, TENURE, TENURE, 20),
                "a perfect coach cannot make a converged report narrower than the owner's ±1");
    }

    @Test
    @DisplayName("coach development is clamped to the 1-20 attribute range")
    void coachSpeedupIsClamped() {
        assertEquals(TalentRange.coachSpeedup(1), TalentRange.coachSpeedup(1));
        assertEquals(TalentRange.coachSpeedup(20), TalentRange.coachSpeedup(20));
        assertTrue(TalentRange.coachSpeedup(0) >= TalentRange.coachSpeedup(1),
                "a zero attribute must not be faster than the minimum");
        assertTrue(TalentRange.coachSpeedup(999) <= TalentRange.coachSpeedup(20),
                "an out-of-range attribute must not exceed the maximum");
        assertTrue(TalentRange.coachSpeedup(null) >= 1.0, "an unknown coach is no coach, not a penalty");
    }

    @Test
    @DisplayName("a zero-length tenure claims no progress rather than dividing by zero")
    void zeroTenureIsHandled() {
        assertEquals(0.0, TalentRange.observationProgress(0, 0),
                "nothing to measure against, so nothing is claimed");
        assertEquals(0.0, TalentRange.observationProgress(5, -3));
        assertEquals(4.0, TalentRange.currentHalfWidth(junior(4.0), 5, 0, 20),
                "even a perfect coach cannot manufacture certainty from an empty tenure");
    }

    @Test
    @DisplayName("weeks observed beyond the tenure clamp to fully observed")
    void overLongObservationClampsToOne() {
        // A junior nobody resolved is still resolved eventually, and by then he is as known as he is
        // ever going to get. This must not exceed 1.0, which would push the width below the ±1 floor.
        assertEquals(1.0, TalentRange.observationProgress(TENURE + 40, TENURE));
        assertEquals(1.0, TalentRange.currentHalfWidth(junior(4.0), TENURE + 40, TENURE, null));
    }

    @Test
    @DisplayName("negative weeks observed clamp to zero")
    void negativeObservationClampsToZero() {
        assertEquals(0.0, TalentRange.observationProgress(-7, TENURE));
    }

    @Test
    @DisplayName("a junior with no recorded width is reported wide, never certain")
    void missingWidthIsNotCertainty() {
        assertEquals(TalentRange.maxHalfWidth(), TalentRange.currentHalfWidth(junior(null), 0, TENURE, null),
                "a pre-existing junior with no rolled width must not be handed a ±0 report");
        assertEquals(TalentRange.maxHalfWidth(),
                TalentRange.currentHalfWidth(new Junior(), 0, TENURE, null),
                "no intake width recorded means full width");
    }

    @Test
    @DisplayName("a stored width outside the legal band is clamped on read")
    void badStoredWidthIsClamped() {
        assertEquals(1.0, TalentRange.currentHalfWidth(junior(0.0), 0, TENURE, null),
                "a stored zero would claim certainty nobody has");
        assertEquals(4.0, TalentRange.currentHalfWidth(junior(99.0), 0, TENURE, null));
        assertEquals(4.0, TalentRange.currentHalfWidth(junior(Double.NaN), 0, TENURE, null));
    }

    @Test
    @DisplayName("bounds are the true value plus or minus the width, to two decimals")
    void boundsAreRoundedToTwoDecimals() {
        // Inside the 1-10 scale, so the clamp does not interfere with what is being tested here.
        assertArrayEquals(new double[] { 4.27, 7.27 },
                TalentRange.bounds(5.77, 1.5),
                "a 5.77 talent reported at +/-1.5");
        assertArrayEquals(new double[] { 6.77, 8.77 },
                TalentRange.bounds(7.77, 1.0),
                "the +/-1 floor is the tightest a report ever gets");
    }

    @Test
    @DisplayName("a band never leaves the 1-10 scale, because no player has a talent of 11")
    void boundsStayOnTheScale() {
        // Caught by the owner looking at the academy screen: a talent of 10 reported at +/-4 read
        // "6.00 - 14.00", and 14 is not a talent anybody can have.
        assertEquals(1, TalentRange.MIN_TALENT);
        assertEquals(10, TalentRange.MAX_TALENT);

        for (int talent = 1; talent <= 10; talent++) {
            for (int roll = 1; roll <= 4; roll++) {
                Junior j = junior(TalentRange.intakeHalfWidth(roll));
                double[] b = TalentRange.bounds(talent, TalentRange.currentHalfWidth(j, 0, TENURE, null));
                assertTrue(b[0] >= TalentRange.MIN_TALENT,
                        "talent " + talent + " roll " + roll + " reported below the floor: " + b[0]);
                assertTrue(b[1] <= TalentRange.MAX_TALENT,
                        "talent " + talent + " roll " + roll + " reported above the ceiling: " + b[1]);
                assertTrue(b[0] <= b[1], "a clamped band must not invert");
            }
        }
        assertArrayEquals(new double[] { 6.0, 10.0 }, TalentRange.bounds(10.0, 4.0),
                "the widest possible report on the best possible talent");
        assertArrayEquals(new double[] { 1.0, 5.0 }, TalentRange.bounds(1.0, 4.0),
                "and on the worst");
    }

    @Test
    @DisplayName("an out-of-scale stored talent produces a readable band rather than an inverted one")
    void outOfScaleTalentDegrades() {
        double[] high = TalentRange.bounds(14.0, 2.0);
        assertTrue(high[0] <= high[1], "must not invert");
        assertTrue(high[1] <= TalentRange.MAX_TALENT);
    }

    @Test
    @DisplayName("the revealed talent is the integer the roll actually produced")
    void revealIsTheIntegerRoll() {
        // The owner asked for "exact talent with decimals". The roll is a weighted integer on a 1-10
        // scale, and re-rolling it as a double would move the graduation distribution, which the same
        // owner ruled must not move. So the honest reading of both decisions is an integer.
        assertEquals(7.0, TalentRange.revealExact(7.0));
        assertEquals(10.0, TalentRange.revealExact(10.0));
        assertEquals(1.0, TalentRange.revealExact(1.0));
        assertNull(TalentRange.revealExact(Double.NaN));
    }

    @Test
    @DisplayName("bounds refuse to render when there is no true value to centre on")
    void boundsRefuseWithoutATrueValue() {
        assertNull(TalentRange.bounds(Double.NaN, 2.0),
                "a NaN talent must produce no range at all, not a range of NaN");
        assertNull(TalentRange.bounds(Double.POSITIVE_INFINITY, 2.0));
        assertNotNull(TalentRange.bounds(8.0, 2.0));
    }

    @Test
    @DisplayName("the true value always sits inside its own reported range")
    void trueValueIsAlwaysInsideTheRange() {
        // The property that makes the feature honest rather than decorative: a report must never
        // exclude the truth, or the manager is being told something false rather than something vague.
        //
        // Walked across the real 1-10 scale. An earlier version walked to 20 and failed, which was the
        // clamp working correctly: a talent of 10.25 does not exist, and a report that "contains" it
        // would have to claim a talent of 14 is possible.
        for (double talent = TalentRange.MIN_TALENT; talent <= TalentRange.MAX_TALENT; talent += 0.37) {
            for (int week = 0; week <= TENURE; week++) {
                for (int roll = 0; roll <= 3; roll++) {
                    Junior j = junior(TalentRange.intakeHalfWidth(roll));
                    double width = TalentRange.currentHalfWidth(j, week, TENURE, 12);
                    double[] b = TalentRange.bounds(talent, width);
                    assertTrue(b[0] <= talent && talent <= b[1],
                            "talent " + talent + " at week " + week + " roll " + roll
                                    + " fell outside [" + b[0] + ", " + b[1] + "]");
                }
            }
        }
    }

    @Test
    @DisplayName("a wide intake roll is still wide on arrival for a nineteen-year-old")
    void arrivalAgeNoLongerDrivesTheBand() {
        // The regression this model change was made to prevent. Intake produces ages 15-19 and the
        // nineteen-year-old ages to twenty within his single season, so under the old age-based span
        // (graduationAge - arrivalAge) he spanned **zero** ages: progress 1.0 on arrival and every
        // report pinned at ±1, i.e. his exact talent handed over the moment he signed. He must now be
        // exactly as uncertain on arrival as a fifteen-year-old is, because nothing about his arrival
        // age is what the club has or has not observed.
        Junior wide = junior(4.0);
        wide.setArrivalAge(19);
        wide.setAge(19);
        assertEquals(4.0, TalentRange.currentHalfWidth(wide, 0, TENURE, null),
                "a nineteen-year-old on arrival knows nothing, exactly like a fifteen-year-old");

        Junior young = junior(4.0);
        young.setArrivalAge(15);
        young.setAge(15);
        assertEquals(TalentRange.currentHalfWidth(young, 0, TENURE, null),
                TalentRange.currentHalfWidth(wide, 0, TENURE, null),
                "arrival age must not change the report at all");
    }

    @Test
    @DisplayName("rounding to two decimals is the house rule and is applied everywhere")
    void twoDecimalRounding() {
        assertEquals(1.23, TalentRange.round2(1.2345));
        assertEquals(1.24, TalentRange.round2(1.235));
        assertEquals(1.0, TalentRange.round2(1.0));
    }
}