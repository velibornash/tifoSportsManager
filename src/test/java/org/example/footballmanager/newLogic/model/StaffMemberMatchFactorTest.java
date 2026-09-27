package org.example.footballmanager.newLogic.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 4.3 — the head coach has to matter on the pitch.
 *
 * <p>The bound is the thing worth protecting here. The match engine is calibrated; a head coach who
 * was worth half a defender would not be a staffing decision, it would be a balance change wearing a
 * staffing decision's clothes. These tests hold the ceiling and the floor where they belong.
 */
class StaffMemberMatchFactorTest {

    private static StaffMember headCoach(Integer motivation, Integer tactical) {
        StaffMember m = new StaffMember();
        m.setRole(StaffRole.HEAD_COACH);
        m.setMotivation(motivation);
        m.setTactical(tactical);
        return m;
    }

    @Test
    @DisplayName("A club with no head coach plays exactly as it did before the feature existed")
    void noCoachIsExactlyNeutral() {
        assertEquals(1.0, new StaffMember().matchFactor(), 1e-9,
                "an unrated coach changed the match, which is a data artefact not a rule");
    }

    @Test
    @DisplayName("A truly outstanding coach is worth about six percent, not half a defender")
    void theCeilingIsHonest() {
        double best = headCoach(20, 20).matchFactor();
        assertTrue(best > 1.0, "the best possible coach made his players worse");
        assertTrue(best <= 1.07,
                "the best coach is worth " + best + ", which is re-tuning the engine, not staffing it");
    }

    @Test
    @DisplayName("A genuinely bad coach hurts, and not below the floor")
    void theFloorIsHonest() {
        double worst = headCoach(1, 1).matchFactor();
        assertTrue(worst < 1.0, "the worst possible coach made his players better");
        assertTrue(worst >= 0.93, "a bad coach took his side below " + worst);
    }

    @Test
    @DisplayName("The factor moves monotonically, so a better coach is never a worse one")
    void monotonic() {
        double previous = 0;
        for (int rating = 1; rating <= 20; rating++) {
            double factor = headCoach(rating, rating).matchFactor();
            assertTrue(factor > previous,
                    "rating " + rating + " is worth less than rating " + (rating - 1));
            previous = factor;
        }
    }

    @Test
    @DisplayName("Man-management counts for more than tactics, which is the intended weighting")
    void manManagementDominates() {
        double strongMan = headCoach(20, 1).matchFactor();
        double strongTactics = headCoach(1, 20).matchFactor();
        assertTrue(strongMan > strongTactics,
                "tactical work outweighed man-management, which is the wrong way round for a dressing room");
    }

    @Test
    @DisplayName("The whole range is a small band, so the engine's balance is left alone")
    void theRangeIsNarrow() {
        double worst = headCoach(1, 1).matchFactor();
        double best = headCoach(20, 20).matchFactor();
        assertTrue(best - worst < 0.15,
                "the coaching range spans " + (best - worst) + ", which is too wide to be a staffing effect");
    }
}
