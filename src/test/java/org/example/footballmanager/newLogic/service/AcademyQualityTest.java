package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.StaffMember;
import org.example.footballmanager.newLogic.model.Stadium;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Academy quality: the growth multiplier a youth setup is worth (Sprint 5.3).
 *
 * <p>Both inputs existed and were read by nothing — {@code Stadium.youthLevel} from S4.4 and the
 * {@code YOUTH_COACH}'s development attribute from S4.3, each of which deferred part of its effect
 * here. The properties pinned below are the ones a later "simplification" would break.
 */
class AcademyQualityTest {

    private static Stadium ground(Integer youthLevel) {
        Stadium s = new Stadium();
        s.setYouthLevel(youthLevel);
        return s;
    }

    private static StaffMember coach(Integer development) {
        StaffMember s = new StaffMember();
        s.setDevelopment(development);
        return s;
    }

    @Test
    @DisplayName("an average academy is exactly neutral")
    void averageIsNeutral() {
        assertEquals(1.0, AcademyQuality.multiplier(10, 10), 0.0001,
                "level 10 of 20 is the midpoint of both inputs and must multiply by nothing");
    }

    @Test
    @DisplayName("an unrecorded academy is neutral, and a recorded half still counts")
    void unrecordedIsNeutral() {
        // Every pre-existing club has null in both columns. Reading null as zero would take
        // development off every club in the database — the fourth instance of that trap.
        assertEquals(1.0, AcademyQuality.multiplier(null, null), 0.0001,
                "nothing recorded must be exactly neutral, not the worst in the league");
        assertEquals(1.0, AcademyQuality.multiplierFor(null, null), 0.0001);

        // "Neutral" applies to the missing half only: a club that has hired a good youth coach but
        // never recorded a facility must still benefit from the coach. Swallowing the recorded half
        // would be a different bug, and an uglier one.
        assertTrue(AcademyQuality.multiplier(null, 15) > 1.0,
                "a recorded coach counts even when the facility is unrecorded");
        assertTrue(AcademyQuality.multiplier(15, null) > 1.0,
                "a recorded facility counts even when there is no coach");
        assertTrue(AcademyQuality.multiplier(null, 15) < AcademyQuality.multiplier(15, 15),
                "but one recorded half is worth less than two");
    }

    @Test
    @DisplayName("neither input can rescue the other")
    void oneFactorCannotRescueTheOther() {
        double facilityOnly = AcademyQuality.multiplier(20, 10);
        double coachOnly = AcademyQuality.multiplier(10, 20);
        double both = AcademyQuality.multiplier(20, 20);
        double neither = AcademyQuality.multiplier(1, 1);

        assertTrue(facilityOnly > 1.0 && coachOnly > 1.0, "each input must be worth having on its own");
        assertTrue(neither < 1.0, "a poor academy must be a real handicap");
        // The whole point of capping each input at 15%: one excellent factor plus one average one
        // cannot reach what two excellent factors reach.
        assertTrue(facilityOnly < both && coachOnly < both,
                "one good factor plus an average one must fall short of two");
        assertTrue(facilityOnly - 1.0 <= AcademyQuality.MAX_INPUT_WEIGHT + 0.0001,
                "a single input may not exceed its stated weight");
    }

    @Test
    @DisplayName("the multiplier is bounded and never inverts")
    void boundedAndNeverInverts() {
        // The best case is reached exactly: two inputs at their maximum, each worth its full weight.
        assertEquals(AcademyQuality.MAX_MULTIPLIER, AcademyQuality.multiplier(20, 20), 0.0001);
        // The worst case is *not* the clamp: level 1 is a deviation of -0.9, so the formula bottoms out
        // at 0.73. MIN_MULTIPLIER is a guard against a future change to the weight, not a figure any
        // real pair of inputs produces. Asserted as a floor so the two are not confused.
        assertEquals(0.73, AcademyQuality.multiplier(1, 1), 0.0001,
                "the worst real pair of inputs");
        assertTrue(AcademyQuality.multiplier(1, 1) >= AcademyQuality.MIN_MULTIPLIER,
                "and it must still sit above the safety floor");
        for (int level = 0; level <= 25; level++) {
            for (int dev = 0; dev <= 25; dev++) {
                double m = AcademyQuality.multiplier(level, dev);
                assertTrue(m >= AcademyQuality.MIN_MULTIPLIER && m <= AcademyQuality.MAX_MULTIPLIER,
                        "escaped the band at level=" + level + " dev=" + dev + " -> " + m);
                assertTrue(m > 0, "a dire academy still develops somebody");
            }
        }
    }

    @Test
    @DisplayName("out-of-range and nonsense attribute values are clamped, not trusted")
    void clampsBadInput() {
        assertEquals(AcademyQuality.multiplier(20, 20), AcademyQuality.multiplier(999, 999), 0.0001);
        assertEquals(AcademyQuality.multiplier(1, 1), AcademyQuality.multiplier(-5, -5), 0.0001);
        assertEquals(0.0, AcademyQuality.deviation(null), 0.0001,
                "an unrecorded attribute contributes nothing — not the worst case");
        assertEquals(-0.9, AcademyQuality.deviation(1), 0.0001);
        assertEquals(1.0, AcademyQuality.deviation(20), 0.0001);
    }

    @Test
    @DisplayName("quality rises with both inputs and never falls as either improves")
    void monotonicInBothInputs() {
        double previous = -1;
        for (int level = 1; level <= 20; level++) {
            double m = AcademyQuality.multiplier(level, 10);
            assertTrue(m > previous, "a better facility must never reduce quality: " + level + " -> " + m);
            previous = m;
        }
        previous = -1;
        for (int dev = 1; dev <= 20; dev++) {
            double m = AcademyQuality.multiplier(10, dev);
            assertTrue(m > previous, "a better coach must never reduce quality: " + dev + " -> " + m);
            previous = m;
        }
    }

    @Test
    @DisplayName("the two inputs are interchangeable, so neither is secretly the important one")
    void inputsAreInterchangeable() {
        assertEquals(AcademyQuality.multiplier(20, 4), AcademyQuality.multiplier(4, 20), 0.0001);
        assertEquals(AcademyQuality.multiplier(3, 17), AcademyQuality.multiplier(17, 3), 0.0001);
    }

    @Test
    @DisplayName("every label maps to the band it claims")
    void labelsMatchTheirBands() {
        assertEquals("Excellent", AcademyQuality.label(1.30));
        assertEquals("Good", AcademyQuality.label(1.10));
        assertEquals("Average", AcademyQuality.label(1.00));
        assertEquals("Average", AcademyQuality.label(0.95));
        assertEquals("Poor", AcademyQuality.label(0.85));
        assertEquals("Barely a setup", AcademyQuality.label(0.70));
    }

    @Test
    @DisplayName("a stadium with no youth level and a club with no coach are both survivable")
    void missingObjectsAreNotFatal() {
        assertEquals(1.0, AcademyQuality.multiplierFor(ground(null), coach(null)), 0.0001);
        assertTrue(AcademyQuality.multiplierFor(ground(20), coach(20)) > 1.2,
                "a club with both must be clearly better than neutral");
        assertTrue(AcademyQuality.multiplierFor(null, coach(1)) < 1.0,
                "a club with a poor coach and no recorded ground is still below neutral");
    }
}
