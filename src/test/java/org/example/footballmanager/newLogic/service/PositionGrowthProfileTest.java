package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.SkillName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 4.5 — who learns what, and when.
 *
 * <p>These assert the <b>properties</b> the design claims rather than the numbers, because the
 * numbers are the part most likely to be retuned and the properties are the part that must not be.
 * A test that asserted "a winger's pace rate is 1.20" would be a test that fails when someone tunes a
 * number and passes when someone deletes the distinction between a winger and a centre-half.
 */
class PositionGrowthProfileTest {

    private static Player player(Position position, int age, double height, double weight) {
        Player p = new Player();
        p.setPosition(position);
        p.setAge(age);
        p.setHeight(height);
        p.setWeight(weight);
        return p;
    }

    private static Player average(Position position, int age) {
        return player(position, age, 1.80, 78.0);
    }

    @Test
    @DisplayName("A player learns his own job fastest, and somebody else's job slowly but not never")
    void naturalFitIsFastAndOffJobIsSlow() {
        for (Position position : Position.values()) {
            double best = 0;
            for (SkillName skill : SkillName.values()) {
                if (skill == SkillName.FATIGUE) continue;
                best = Math.max(best, PositionGrowthProfile.learningRate(position, skill));
            }
            assertTrue(best >= 1.15,
                    position + " has no skill he learns well, so he has no natural game");

            for (SkillName skill : SkillName.values()) {
                if (skill == SkillName.FATIGUE) continue;
                double rate = PositionGrowthProfile.learningRate(position, skill);
                assertTrue(rate > 0.0,
                        position + " cannot learn " + skill + " at all, which removes a decision the"
                                + " manager is allowed to make");
            }
        }
    }

    @Test
    @DisplayName("A winger learns crossing and a centre-half learns heading, and they do not swap")
    void positionsActuallyDiffer() {
        double wingerCrossing = PositionGrowthProfile.learningRate(Position.WNG, SkillName.PASSING);
        double centreHalfCrossing = PositionGrowthProfile.learningRate(Position.DEF, SkillName.PASSING);
        assertTrue(wingerCrossing > centreHalfCrossing,
                "a winger is not a better crosser to train than a centre-half");

        double centreHalfDefending = PositionGrowthProfile.learningRate(Position.DEF, SkillName.DEFENDER);
        double wingerDefending = PositionGrowthProfile.learningRate(Position.WNG, SkillName.DEFENDER);
        assertTrue(centreHalfDefending > wingerDefending,
                "a centre-half is not a better defender to train than a winger");
    }

    @Test
    @DisplayName("A 34-year-old cannot improve pace at all, whatever his talent")
    void ageCeilingsAreHard() {
        for (Position position : Position.values()) {
            Player old = average(position, 34);
            assertTrue(PositionGrowthProfile.isTooOldToLearn(old, SkillName.PACE)
                            || position == Position.GK,
                    position + " can still learn pace at 34");
            assertEquals(0.0, PositionGrowthProfile.ageFactor(position, SkillName.PACE, 34), 1e-9);
            assertEquals(0.0, PositionGrowthProfile.ageFactor(position, SkillName.STRIKER, 34), 1e-9,
                    position + " can still learn finishing at 34");
        }
    }

    @Test
    @DisplayName("A goalkeeper is allowed to keep learning his job after everybody else has stopped")
    void keepersLastLonger() {
        assertFalse(PositionGrowthProfile.isTooOldToLearn(average(Position.GK, 36), SkillName.GOALKEEPER),
                "a 36-year-old goalkeeper may not improve shot stopping");
        assertTrue(PositionGrowthProfile.isTooOldToLearn(average(Position.GK, 36), SkillName.PACE),
                "a 36-year-old goalkeeper can still learn pace");
    }

    @Test
    @DisplayName("Finishing peaks earlier for a striker than shot stopping does for a keeper")
    void peaksArePositionSpecific() {
        assertEquals(26, PositionGrowthProfile.peakAge(Position.ATT, SkillName.STRIKER));
        assertEquals(31, PositionGrowthProfile.peakAge(Position.GK, SkillName.GOALKEEPER));
        assertTrue(PositionGrowthProfile.peakAge(Position.ATT, SkillName.STRIKER)
                        < PositionGrowthProfile.peakAge(Position.GK, SkillName.GOALKEEPER),
                "a striker peaks in his job later than a goalkeeper does in his");
    }

    @Test
    @DisplayName("A full-back's pace goes before a centre-back's, and a winger's before both")
    void paceDeclinesEarliestForThePlayersWhoNeedItMost() {
        int fullBack = PositionGrowthProfile.peakAge(Position.DEF, SkillName.PACE);
        int striker = PositionGrowthProfile.peakAge(Position.ATT, SkillName.PACE);
        int keeper = PositionGrowthProfile.peakAge(Position.GK, SkillName.PACE);
        assertTrue(fullBack <= striker, "a full-back's pace outlasts a striker's");
        assertTrue(fullBack <= keeper, "a full-back's pace outlasts a goalkeeper's");
        assertEquals(25, PositionGrowthProfile.peakAge(Position.WNG, SkillName.PACE));
    }

    @Test
    @DisplayName("The age curve never rewards learning more in your late twenties")
    void ageCurveNeverRisesAboveThePeak() {
        for (Position position : Position.values()) {
            for (SkillName skill : SkillName.values()) {
                if (skill == SkillName.FATIGUE) continue;
                int peak = PositionGrowthProfile.peakAge(position, skill);
                double atPeak = PositionGrowthProfile.ageFactor(position, skill, peak);
                assertTrue(atPeak <= 1.0,
                        position + " " + skill + " learns faster at its peak than it does at 22");
                assertTrue(PositionGrowthProfile.ageFactor(position, skill, peak + 2) < atPeak,
                        position + " " + skill + " does not decline after its peak");
            }
        }
    }

    @Test
    @DisplayName("A natural position is not held below the top of the scale")
    void naturalPositionsCanReachTheTop() {
        assertEquals(20.0, PositionGrowthProfile.naturalCeiling(Position.DEF, SkillName.DEFENDER), 1e-9);
        assertEquals(20.0, PositionGrowthProfile.naturalCeiling(Position.ATT, SkillName.STRIKER), 1e-9);
        assertEquals(20.0, PositionGrowthProfile.naturalCeiling(Position.GK, SkillName.GOALKEEPER), 1e-9);
    }

    @Test
    @DisplayName("A skill that is not his job has a lower, but real, ceiling")
    void offJobSkillsAreCappedSoftly() {
        double ceiling = PositionGrowthProfile.naturalCeiling(Position.DEF, SkillName.STRIKER);
        assertTrue(ceiling < 20.0, "a centre-half can train his way to 20 finishing as easily as a striker");
        assertTrue(ceiling >= 7.0,
                "a professional footballer is not helpless at something outside his game, got " + ceiling);
    }

    @Test
    @DisplayName("Tall and heavy helps a defender and hurts a sprinter")
    void theBodyMattersInTheRightDirection() {
        Player big = player(Position.DEF, 24, 1.95, 90.0);
        Player small = player(Position.DEF, 24, 1.70, 70.0);
        assertTrue(PositionGrowthProfile.physicalFactor(big, SkillName.DEFENDER)
                        > PositionGrowthProfile.physicalFactor(small, SkillName.DEFENDER),
                "a bigger centre-half does not learn defending faster");
        assertTrue(PositionGrowthProfile.physicalFactor(big, SkillName.PACE)
                        < PositionGrowthProfile.physicalFactor(small, SkillName.PACE),
                "a big man does not learn pace faster than a small one");
    }

    @Test
    @DisplayName("A body is a nudge, not a destiny")
    void theBodyIsBounded() {
        for (SkillName skill : SkillName.values()) {
            assertTrue(PositionGrowthProfile.physicalFactor(
                            player(Position.ATT, 24, 2.15, 110.0), skill) >= 0.92);
            assertTrue(PositionGrowthProfile.physicalFactor(
                            player(Position.ATT, 24, 1.55, 60.0), skill) <= 1.08);
        }
    }

    @Test
    @DisplayName("A player with no recorded height or weight is not penalised for it")
    void missingBodyDataIsNeutral() {
        Player blank = player(Position.MID, 24, 0, 0);
        for (SkillName skill : SkillName.values()) {
            assertEquals(1.0, PositionGrowthProfile.physicalFactor(blank, skill), 1e-9,
                    "a player with no height or weight recorded was penalised for it");
        }
    }

    @Test
    @DisplayName("No profile grows at a rate that would make a season meaningless")
    void magnitudesStayInABand() {
        // The coefficient that scales a week's growth, ignoring the percentage and the roll. This is
        // the guard on a rewrite of the growth rules: it is entirely possible to satisfy every
        // property above and still double or halve how fast everybody develops, which no property
        // test would notice. The old global penalty was STRIKER 0.76 / PACE 0.86, so a natural skill
        // sitting near 1.0-1.3 is continuity, and anything much outside that is a balance change.
        for (Position position : Position.values()) {
            for (int age = 18; age <= 38; age++) {
                for (SkillName skill : SkillName.values()) {
                    if (skill == SkillName.FATIGUE) continue;
                    Player p = average(position, age);
                    double coefficient = 0.52
                            * PositionGrowthProfile.learningRate(position, skill)
                            * PositionGrowthProfile.ageFactor(position, skill, age)
                            * PositionGrowthProfile.physicalFactor(p, skill);
                    assertTrue(coefficient <= 0.52 * 1.30 * 1.15 * 1.08,
                            position + " " + skill + " at " + age + " grows at " + coefficient
                                    + ", faster than any profile should");
                    assertTrue(coefficient >= 0.0,
                            position + " " + skill + " at " + age + " produced a negative coefficient");
                }
            }
        }
    }

    @Test
    @DisplayName("A youth player still learns faster than a peak-age one — nobody plateaus in their twenties")
    void youthStillLearns() {
        for (Position position : Position.values()) {
            double nineteen = PositionGrowthProfile.ageFactor(position, SkillName.PASSING, 19);
            double twentySeven = PositionGrowthProfile.ageFactor(position, SkillName.PASSING, 27);
            assertTrue(nineteen > twentySeven,
                    position + " learns no faster at 19 than at 27");
        }
    }

    @Test
    @DisplayName("Skills nobody trains are not treated as skills with a curve")
    void fatigueIsNotATrainableSkill() {
        assertEquals(1.0, PositionGrowthProfile.learningRate(Position.GK, SkillName.FATIGUE), 1e-9,
                "fatigue has a learning rate, so it will start being trained as if it were a skill");
        assertEquals(1.0, PositionGrowthProfile.learningRate(null, SkillName.STRIKER), 1e-9);
        assertEquals(20.0, PositionGrowthProfile.naturalCeiling(null, SkillName.STRIKER), 1e-9,
                "a player with no position was capped below the top of the scale");
    }
}
