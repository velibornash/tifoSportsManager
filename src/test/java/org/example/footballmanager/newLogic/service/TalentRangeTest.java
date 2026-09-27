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
 * The talent report's width and how it narrows (Sprint 5.2).
 *
 * <p>The owner's numbers are pinned exactly: intake is {@code ±(1 + rnd(0..3))}, promotion is
 * {@code ±1}, and a better youth coach narrows faster. Every one of those is a decision, not a
 * derived figure, so a test is the only thing that stops a later "cleanup" quietly changing what a
 * manager is told about his own academy.
 */
class TalentRangeTest {

    private static Junior junior(int arrivalAge, Double intakeHalfWidth) {
        Junior j = new Junior();
        j.setArrivalAge(arrivalAge);
        j.setTalentRangeHalfWidth(intakeHalfWidth);
        return j;
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
    @DisplayName("a report starts at its intake width and ends at ±1")
    void narrowsFromIntakeToOne() {
        Junior j = junior(15, 4.0);

        assertEquals(4.0, TalentRange.currentHalfWidth(j, 15, 19, null),
                "on arrival the report is exactly the rolled width");
        assertEquals(1.0, TalentRange.currentHalfWidth(j, 19, 19, null),
                "at graduation the report has converged to ±1");
    }

    @Test
    @DisplayName("the width never rises as a junior is observed, and never falls below ±1")
    void narrowsMonotonicallyAndStopsAtOne() {
        Junior j = junior(15, 4.0);
        double previous = Double.MAX_VALUE;
        for (int age = 15; age <= 25; age++) {
            double width = TalentRange.currentHalfWidth(j, age, 19, null);
            assertTrue(width <= previous, "width must not widen with age: age " + age + " gave " + width);
            assertTrue(width >= TalentRange.FINAL_HALF_WIDTH,
                    "width must never go below ±1: age " + age + " gave " + width);
            previous = width;
        }
        assertEquals(1.0, previous, "past graduation the report stays at ±1 forever");
    }

    @Test
    @DisplayName("a better youth coach narrows the same report faster")
    void coachNarrowsFaster() {
        Junior j = junior(15, 4.0);

        double noCoach = TalentRange.currentHalfWidth(j, 16, 19, null);
        double averageCoach = TalentRange.currentHalfWidth(j, 16, 19, 10);
        double goodCoach = TalentRange.currentHalfWidth(j, 16, 19, 20);

        assertTrue(goodCoach < averageCoach, "a 20 development coach must beat a 10: "
                + goodCoach + " vs " + averageCoach);
        assertTrue(averageCoach < noCoach, "a coach must beat no coach: " + averageCoach + " vs " + noCoach);
    }

    @Test
    @DisplayName("the coach can only ever help — the best coach matches an already-finished report")
    void coachNeverWidensAndNeverBeatsTheFloor() {
        Junior j = junior(15, 4.0);
        for (int age = 15; age <= 19; age++) {
            double best = TalentRange.currentHalfWidth(j, age, 19, 20);
            double worst = TalentRange.currentHalfWidth(j, age, 19, 1);
            assertTrue(best <= worst, "coach must never widen: age " + age);
            assertTrue(best >= TalentRange.FINAL_HALF_WIDTH, "coach must never beat the ±1 floor: age " + age);
        }
        assertEquals(1.0, TalentRange.currentHalfWidth(j, 19, 19, 20),
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
    @DisplayName("a junior who arrived at graduation age has no observation period, not a divide by zero")
    void zeroSpanIsHandled() {
        Junior j = junior(19, 4.0);
        assertEquals(1.0, TalentRange.observationProgress(j, 19, 19));
        assertEquals(1.0, TalentRange.currentHalfWidth(j, 19, 19, null),
                "no time to observe means the report is already at its floor");
    }

    @Test
    @DisplayName("an unknown arrival age and a zero-length window are NOT the same answer")
    void unknownArrivalIsNotFullyObserved() {
        // These two look alike and are not. A zero-length window means the junior is already at his
        // floor. An unknown arrival age means we do not know how long we have been watching, and the
        // only honest report is the widest one. Returning "fully observed" for both would hand every
        // junior in a pre-existing database a confident ±1 he has not earned.
        Junior unknownArrival = new Junior();
        unknownArrival.setTalentRangeHalfWidth(4.0);
        assertEquals(0.0, TalentRange.observationProgress(unknownArrival, 19, 19),
                "an unrecorded arrival age means no observation progress");
        assertEquals(4.0, TalentRange.currentHalfWidth(unknownArrival, 19, 19, 20),
                "even a perfect coach cannot manufacture certainty from an unrecorded arrival age");
    }

    @Test
    @DisplayName("a junior with no recorded width is reported wide, never certain")
    void missingWidthIsNotCertainty() {
        Junior unrolled = junior(15, null);
        assertEquals(TalentRange.maxHalfWidth(), TalentRange.currentHalfWidth(unrolled, 15, 19, null),
                "a pre-existing junior with no rolled width must not be handed a ±0 report");

        Junior noArrival = new Junior();
        assertEquals(TalentRange.maxHalfWidth(), TalentRange.currentHalfWidth(noArrival, 17, 19, null),
                "no arrival age means no observation period, so full width");
    }

    @Test
    @DisplayName("a stored width outside the legal band is clamped on read")
    void badStoredWidthIsClamped() {
        assertEquals(1.0, TalentRange.currentHalfWidth(junior(15, 0.0), 15, 19, null),
                "a stored zero would claim certainty nobody has");
        assertEquals(4.0, TalentRange.currentHalfWidth(junior(15, 99.0), 15, 19, null));
        assertEquals(4.0, TalentRange.currentHalfWidth(junior(15, Double.NaN), 15, 19, null));
    }

    @Test
    @DisplayName("bounds are the true value plus or minus the width, to two decimals")
    void boundsAreRoundedToTwoDecimals() {
        assertArrayEquals(new double[] { 5.27, 11.27 },
                TalentRange.bounds(8.27, 3.0),
                "a 8.27 talent reported at ±3.0");
        assertArrayEquals(new double[] { 6.77, 8.77 },
                TalentRange.bounds(7.77, 1.0),
                "the ±1 floor is the tightest a report ever gets");
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
        for (double talent = 1.0; talent <= 20.0; talent += 0.37) {
            for (int age = 15; age <= 20; age++) {
                for (int roll = 0; roll <= 3; roll++) {
                    Junior j = junior(15, TalentRange.intakeHalfWidth(roll));
                    double width = TalentRange.currentHalfWidth(j, age, 19, 12);
                    double[] b = TalentRange.bounds(talent, width);
                    assertTrue(b[0] <= talent && talent <= b[1],
                            "talent " + talent + " at age " + age + " roll " + roll
                                    + " fell outside [" + b[0] + ", " + b[1] + "]");
                }
            }
        }
    }

    @Test
    @DisplayName("rounding to two decimals is the house rule and is applied everywhere")
    void twoDecimalRounding() {
        assertEquals(1.23, TalentRange.round2(1.2345));
        assertEquals(1.24, TalentRange.round2(1.235));
        assertEquals(1.0, TalentRange.round2(1.0));
    }
}
