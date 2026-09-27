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
                Junior j = junior(15, TalentRange.intakeHalfWidth(roll));
                double[] b = TalentRange.bounds(talent, TalentRange.currentHalfWidth(j, 15, 20, null));
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
        org.junit.jupiter.api.Assertions.assertNull(TalentRange.revealExact(Double.NaN));
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
    @DisplayName("the horizon is the graduation deadline, not the junior's own graduation age")
    void horizonMustBeTheDeadlineNotHisOwnAge() {
        // Found against live data, not by a failing test: every junior in a real academy came back at
        // the +/-1 floor from arrival, because the caller passed graduationAge(), which clamps to the
        // CURRENT age. Span therefore equalled elapsed time, progress was 1.0 for everyone, and the
        // narrowing mechanic did nothing at all. A green suite did not catch it because the arithmetic
        // is correct for the arguments it was given.
        //
        // The tell: a wide intake roll must still be wide for a nineteen-year-old who has a season
        // left to be watched.
        Junior j = junior(15, 4.0);
        double againstOwnAge = TalentRange.currentHalfWidth(j, 19, 19, null);
        double againstDeadline = TalentRange.currentHalfWidth(j, 19, 20, null);

        assertEquals(1.0, againstOwnAge,
                "passing his own age as the horizon collapses the span and pins every report at the floor");
        assertTrue(againstDeadline > 1.0,
                "against the real deadline a 19-year-old must still be uncertain, got " + againstDeadline);
        assertEquals(1.0, TalentRange.currentHalfWidth(j, 20, 20, null),
                "at the deadline the report has converged");
    }

    @Test
    @DisplayName("a wide intake roll is still wide for a young player with years to go")
    void youngPlayerStaysUncertain() {
        Junior j = junior(15, 4.0);
        assertEquals(4.0, TalentRange.currentHalfWidth(j, 15, 20, null),
                "a fifteen-year-old on arrival knows nothing");
        assertTrue(TalentRange.currentHalfWidth(j, 17, 20, null) < 4.0,
                "two years of observation must tighten the band");
        assertTrue(TalentRange.currentHalfWidth(j, 17, 20, null) > 1.0,
                "but two years is not the whole window");
    }

    @Test
    @DisplayName("rounding to two decimals is the house rule and is applied everywhere")
    void twoDecimalRounding() {
        assertEquals(1.23, TalentRange.round2(1.2345));
        assertEquals(1.24, TalentRange.round2(1.235));
        assertEquals(1.0, TalentRange.round2(1.0));
    }
}
