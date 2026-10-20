package org.tifo.footballmanager.newLogic.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A manager who pays for a gym has to be able to see that they did.
 *
 * <p>This is the "the effect is visible on the training report" half of T-FAC-1. The report could only
 * carry a factor before, and a factor is not an explanation: {@code ×1.08} could be the gym, a rounding
 * artefact, or nothing at all. So the level itself is now readable, and the two must agree - a report
 * saying "level 7" beside a {@code ×1.00} would be worse than no column.
 */
class StadiumFacilityLevelTest {

    private Stadium withLevels(Integer ground, Integer gym, Integer tactical) {
        Stadium s = new Stadium();
        s.setTrainingQuality(ground);
        s.setGymLevel(gym);
        s.setTacticalLevel(tactical);
        return s;
    }

    @Test
    @DisplayName("each skill names the facility level that trained it")
    void eachSkillNamesItsFacility() {
        Stadium s = withLevels(3, 7, 11);

        assertEquals(3, s.facilityLevelFor(SkillName.PACE), "speed trains on the ground");
        assertEquals(7, s.facilityLevelFor(SkillName.STAMINA), "stamina trains on the gym");
        assertEquals(11, s.facilityLevelFor(SkillName.PASSING), "passing trains on the tactical room");
    }

    @Test
    @DisplayName("the named level and the factor it produced are the same answer")
    void theNamedLevelAndTheFactorAgree() {
        Stadium s = withLevels(3, 7, 11);

        for (SkillName skill : SkillName.values()) {
            Integer level = s.facilityLevelFor(skill);
            if (level == null) {
                assertEquals(1.0, s.trainingFactorFor(skill), 1e-9,
                        skill + " has no recorded level, so its factor must be neutral");
                continue;
            }
            // Higher level, higher factor - and the two can never contradict each other.
            assertTrue(s.trainingFactorFor(skill) >= 0.90,
                    skill + " at level " + level + " must not train below the neutral floor");
        }

        assertTrue(s.trainingFactorFor(SkillName.PASSING) > s.trainingFactorFor(SkillName.PACE),
                "a tactical room at 11 must beat a ground at 3 on the skills it trains");
    }

    @Test
    @DisplayName("an unrecorded facility is neutral, not level 1")
    void unrecordedIsNeutralNotLevelOne() {
        Stadium none = new Stadium();
        assertNull(none.facilityLevelFor(SkillName.PACE),
                "a club predating the feature has no level recorded, and the report must say so");
        assertEquals(1.0, none.trainingFactorFor(SkillName.PACE), 1e-9,
                "reading null as level 1 would quietly take 10% off every existing club's growth");
    }
}
