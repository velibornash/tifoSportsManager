package org.example.footballmanager.newLogic.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 0.4 — sub-integer training progress must be visible to the rating layer.
 *
 * <p>Weekly growth is 0.05-0.6 points, so a player's only progress for most of a season lives in
 * the fraction. The dual int/Double representation in {@link Skills} exists for exactly that
 * reason, but getRatingScore() read the floored ints, so displayed OVR and match rating did not
 * move for weeks of training.
 *
 * <p>Scope correction worth keeping in mind: the match engine was never affected.
 * {@code RealSquadFactory.toSimSkills} already reads getExact(...). The gap was the display and
 * rating layer only.
 */
class SkillsExactRatingTest {

    private Skills skills;

    @BeforeEach
    void setUp() {
        skills = new Skills();
        skills.setSkill(SkillName.PASSING, 13);
        skills.setSkill(SkillName.STRIKER, 13);
        skills.setSkill(SkillName.PACE, 13);
        skills.setSkill(SkillName.TECHNIQUE, 13);
        skills.setSkill(SkillName.PLAYMAKER, 13);
        skills.setSkill(SkillName.DEFENDER, 13);
        skills.setSkill(SkillName.GOALKEEPER, 13);
        skills.initializeExactFromVisibleIfNeeded();
    }

    @Test
    @DisplayName("a player at 13.9 rates above a player at 13.0 in the same position")
    void subIntegerProgressIsReflected() {
        double at13 = skills.getRatingScore(Position.MID);
        skills.setExact(SkillName.PASSING, 13.9);
        double at139 = skills.getRatingScore(Position.MID);

        assertTrue(at139 > at13,
                "rating must increase with sub-integer progress: " + at13 + " -> " + at139);
    }

    @Test
    @DisplayName("two players with the same floored skill but different fractions rate differently")
    void sameFloorDifferentFractionRatesDifferently() {
        skills.setExact(SkillName.PASSING, 13.2);
        skills.setExact(SkillName.STRIKER, 13.2);
        double low = skills.getRatingScore(Position.ATT);
        skills.setExact(SkillName.PASSING, 13.8);
        skills.setExact(SkillName.STRIKER, 13.8);
        double high = skills.getRatingScore(Position.ATT);

        assertNotEquals(low, high, 1e-9, "same visible int, different fraction, must differ");
        assertTrue(high > low);
    }

    @Test
    @DisplayName("the visible int still floors, so the UI keeps showing whole numbers")
    void visibleIntStillFloors() {
        skills.setExact(SkillName.PASSING, 13.9);
        assertEquals(13, skills.visibleInt(SkillName.PASSING));

        skills.setExact(SkillName.PASSING, 14.0);
        assertEquals(14, skills.visibleInt(SkillName.PASSING));
    }

    @Test
    @DisplayName("rating still matches the old integer formula when values are whole")
    void wholeNumberRatingsAreUnchanged() {
        // MID = pace*1.0 + technique*1.2 + playmaker*2.0 + passing*1.5 + defender*0.7
        double expected = 13 * 1.0 + 13 * 1.2 + 13 * 2.0 + 13 * 1.5 + 13 * 0.7;
        assertEquals(expected, skills.getRatingScore(Position.MID), 1e-9,
                "whole-number ratings must be identical to the previous integer-based formula");
    }

    @Test
    @DisplayName("GK and WNG positions also read exact values")
    void allPositionsUseExactValues() {
        double before = skills.getRatingScore(Position.GK);
        skills.setExact(SkillName.GOALKEEPER, 19.5);
        assertTrue(skills.getRatingScore(Position.GK) > before, "GK must respond to exact goalkeeping");

        double wngBefore = skills.getRatingScore(Position.WNG);
        skills.setExact(SkillName.PACE, 19.5);
        assertTrue(skills.getRatingScore(Position.WNG) > wngBefore, "WNG must respond to exact pace");
    }

    @Test
    @DisplayName("getExact falls back to the visible int before initialisation")
    void exactFallsBackWhenUninitialised() {
        Skills fresh = new Skills();
        fresh.setSkill(SkillName.PASSING, 11);
        // setSkill seeds the exact field, so clear it to simulate a legacy row.
        assertEquals(11.0, fresh.getExact(SkillName.PASSING), 1e-9);
    }

    @Test
    @DisplayName("syncVisibleFromExact floors, so training shows up over time not instantly")
    void syncFloorsRatherThanRounds() {
        skills.setExact(SkillName.PASSING, 13.99);
        skills.syncVisibleFromExact();
        assertEquals(13, skills.getPassing(), "0.99 progress must not display as 14 yet");

        skills.setExact(SkillName.PASSING, 14.0);
        skills.syncVisibleFromExact();
        assertEquals(14, skills.getPassing());
    }

    @Test
    @DisplayName("a season of realistic weekly growth produces a visible rating gain")
    void seasonOfTrainingMovesTheRating() {
        double start = skills.getRatingScore(Position.ATT);
        // 19 weeks at ~0.33/week (a young Advanced player, talent 6, skill ~10)
        for (int week = 0; week < 19; week++) {
            skills.setExact(SkillName.STRIKER, skills.getExact(SkillName.STRIKER) + 0.33);
        }
        double end = skills.getRatingScore(Position.ATT);

        assertTrue(end - start > 5.0,
                "a season of striker training must move the ATT rating meaningfully (delta="
                        + (end - start) + ")");
    }
}
