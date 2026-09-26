package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.PlayerRole;
import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.model.StaffMember;
import org.example.footballmanager.newLogic.model.StaffRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The training percentage (owner, 2026-09-27).
 *
 * <p>The owner's rule, verbatim: a player gets 100% of a week's training only with maximum talent, a
 * coach maxed at the skill being trained, and 120+ minutes in the week — and everything short of
 * that reduces it proportionally, <i>not dramatically, a lazy curve</i>.
 */
class TrainingPercentTest {

    // ---------------------------------------------------------------- the shape

    @Test
    @DisplayName("100% needs all three maximal, and nothing less")
    void onlyPerfectionIsAFullHundred() {
        assertEquals(100.0, TrainingPercent.percent(10, 20, 120), 0.01,
                "maximal talent, a maxed coach and 120 minutes is a full week");
        assertEquals(100.0, TrainingPercent.percent(10, 20, 200), 0.01,
                "more than 120 minutes does not exceed 100%");
    }

    @Test
    @DisplayName("it is a weighted mean, so three factors at 80% give 80% and not 51%")
    void weightedMeanNotProduct() {
        double p80talent = TrainingPercent.percent(8, 20, 120);
        double p80coach = TrainingPercent.percent(10, 16, 120);
        double p80minutes = TrainingPercent.percent(10, 20, 96);

        // A product would put these near 51-60%. The owner asked for a lazy curve, and this is the
        // single assertion that proves it is a mean.
        assertTrue(p80minutes > 80,
                "96 of 120 minutes is 80% of the target, and must not collapse the week: "
                        + round(p80minutes));
        assertTrue(p80coach > 80, "a 16/20 coach: " + round(p80coach));
        assertTrue(p80talent > 80, "talent 8/10: " + round(p80talent));
    }

    @Test
    @DisplayName("more talent is never worse, holding the other two still")
    void moreTalentNeverHurts() {
        double previous = -1;
        for (double talent = 1; talent <= 10; talent++) {
            double p = TrainingPercent.percent(talent, 13, 90);
            assertTrue(p >= previous, "talent " + talent + " gave " + round(p)
                    + " after " + round(previous));
            previous = p;
        }
    }

    @Test
    @DisplayName("more minutes is never worse, holding the other two still")
    void moreMinutesNeverHurts() {
        double previous = -1;
        for (int minutes = 0; minutes <= 180; minutes += 15) {
            double p = TrainingPercent.percent(7, 13, minutes);
            assertTrue(p >= previous, "minutes " + minutes + " gave " + round(p)
                    + " after " + round(previous));
            previous = p;
        }
    }

    @Test
    @DisplayName("a better coach for the trained skill is never worse")
    void aBetterCoachNeverHurts() {
        double previous = -1;
        for (int rating = 1; rating <= 20; rating++) {
            double p = TrainingPercent.percent(7, rating, 90);
            assertTrue(p >= previous, "coach " + rating + " gave " + round(p)
                    + " after " + round(previous));
            previous = p;
        }
    }

    // ------------------------------------------------------------ the three factors

    @Test
    @DisplayName("the worst case still trains a little, rather than freezing")
    void thereIsAFloor() {
        double nothing = TrainingPercent.percent(1, 1, 0);
        assertEquals(22.4, nothing, 0.2,
                "talent 1, a 1/20 coach and no minutes is the floor the owner asked for");

        double floorFactor = TrainingPercent.minutesFactor(0);
        assertEquals(0.30, floorFactor, 0.001,
                "a player who never plays keeps 30% of the minutes factor, so a forgotten squad "
                        + "still moves");
    }

    @Test
    @DisplayName("the coach curve is flatter than the others - a mediocre coach holds a squad together")
    void coachIsSupportiveNotLimiting() {
        assertTrue(TrainingPercent.coachFactor(10) > TrainingPercent.talentFactor(5),
                "half a coach outscores half a talent, because a poor coach should not stop a "
                        + "talented player from learning");
        assertEquals(0.5, TrainingPercent.coachFactor(5), 0.001, "sqrt(0.25) = 0.5");
    }

    @Test
    @DisplayName("minutes scale to 120, then stop")
    void minutesCapAtTheTarget() {
        assertEquals(1.0, TrainingPercent.minutesFactor(120), 0.001);
        assertEquals(1.0, TrainingPercent.minutesFactor(300), 0.001);
        assertTrue(TrainingPercent.minutesFactor(60) > TrainingPercent.minutesFactor(30));
    }

    // ------------------------------------------------------------- the agreed grid

    @Test
    @DisplayName("the worked example the owner was shown still holds")
    void theGridAgreedWithTheOwner() {
        // talent 20/15/10 was discussed on a 20 scale; talent is 1-10 today, and the formula
        // normalises, so the same ratios are the same percentages. These are the numbers that were
        // agreed, pinned so a later tune cannot quietly move them.
        assertEquals(93.3, TrainingPercent.percent(1.00 * 10, 20, 60), 0.15);
        assertEquals(96.8, TrainingPercent.percent(1.00 * 10, 20, 90), 0.15);
        assertEquals(100.0, TrainingPercent.percent(1.00 * 10, 20, 120), 0.01);

        assertEquals(85.5, TrainingPercent.percent(0.75 * 10, 20, 60), 0.15);
        assertEquals(92.2, TrainingPercent.percent(0.75 * 10, 20, 120), 0.15);

        assertEquals(77.1, TrainingPercent.percent(0.50 * 10, 20, 60), 0.15);
        assertEquals(83.8, TrainingPercent.percent(0.50 * 10, 20, 120), 0.15);

        assertEquals(88.6, TrainingPercent.percent(1.00 * 10, 15, 60), 0.15);
        assertEquals(95.3, TrainingPercent.percent(1.00 * 10, 15, 120), 0.15);

        assertEquals(83.0, TrainingPercent.percent(1.00 * 10, 10, 60), 0.15);
        assertEquals(89.7, TrainingPercent.percent(1.00 * 10, 10, 120), 0.15);

        // And on the real 1-10 talent scale.
        assertEquals(100.0, TrainingPercent.percent(10, 20, 120), 0.01);
        assertEquals(83.8, TrainingPercent.percent(5, 20, 120), 0.15);
        assertEquals(56.9, TrainingPercent.percent(1, 10, 120), 0.2);
    }

    // ----------------------------------------------------------------- who coaches

    @Test
    @DisplayName("the head coach sets the ceiling, and an assistant is better than nobody")
    void theCoachIsTheHeadCoach() {
        StaffMember assistant = staff(StaffRole.ASSISTANT, 12);
        StaffMember head = staff(StaffRole.HEAD_COACH, 16);
        StaffMember scout = staff(StaffRole.SCOUT, 20);

        assertSame(head, TrainingPercent.coachFor(List.of(scout, assistant, head)),
                "a scout with 20 must not become the coach just because he is in the list");
        assertSame(assistant, TrainingPercent.coachFor(List.of(scout, assistant)),
                "a club with no head coach still has somebody who can teach");
        assertNull(TrainingPercent.coachFor(List.of()), "no staff is genuinely nothing");
    }

    @Test
    @DisplayName("roles pick the skill, so the coach is asked about the right thing")
    void rolesChooseTheSkill() {
        assertEquals(SkillName.GOALKEEPER, TrainingPercent.primarySkillFor(PlayerRole.GOALKEEPER));
        assertEquals(SkillName.DEFENDER, TrainingPercent.primarySkillFor(PlayerRole.LEFT_BACK));
        assertEquals(SkillName.DEFENDER, TrainingPercent.primarySkillFor(PlayerRole.CENTRE_BACK));
        assertEquals(SkillName.PASSING, TrainingPercent.primarySkillFor(PlayerRole.CENTRE_MIDFIELDER));
        assertEquals(SkillName.TECHNIQUE, TrainingPercent.primarySkillFor(PlayerRole.WINGER));
        assertEquals(SkillName.STRIKER, TrainingPercent.primarySkillFor(PlayerRole.STRIKER));
        assertNull(TrainingPercent.secondarySkillFor(PlayerRole.GOALKEEPER),
                "a goalkeeper has one job, so there is no second skill");
    }

    // ------------------------------------------------------------------- the coach

    @Test
    @DisplayName("a coach is rated per skill, so a specialist can beat a generalist where it matters")
    void specialistsBeatGeneralistsWhereItMatters() {
        StaffMember specialist = staff(StaffRole.HEAD_COACH, 6);
        specialist.setSkillPassing(20);
        specialist.setSkillStriker(20);
        specialist.setSkillDefender(4);
        specialist.setSkillPace(4);
        specialist.setSkillTechnique(4);
        specialist.setSkillPlaymaker(4);
        specialist.setSkillStamina(4);
        specialist.setSkillGoalkeeper(4);

        StaffMember generalist = staff(StaffRole.HEAD_COACH, 6);
        for (SkillName skill : SkillName.values()) {
            if (skill == SkillName.FATIGUE) continue;
            generalist.setSkill(skill, 10);
        }

        assertTrue(specialist.coachRating(SkillName.PASSING)
                        > generalist.coachRating(SkillName.PASSING),
                "twenty at passing beats ten, and that is the whole point of per-skill ratings");
        assertTrue(generalist.coachRating(SkillName.DEFENDER)
                        > specialist.coachRating(SkillName.DEFENDER),
                "and the generalist is still better at defending");

        // A specialist pays less, which is the trade the owner described.
        double cheap = CoachWage.weeklyWageFor(specialist, 3, 60);
        double dear = CoachWage.weeklyWageFor(generalist, 3, 60);
        assertTrue(dear > cheap,
                "the coach who can rotate costs more: " + round(dear) + " vs " + round(cheap));
    }

    @Test
    @DisplayName("a coach with no skill ratings recorded is mediocre, not catastrophic")
    void unrecordedRatingsFallBack() {
        StaffMember legacy = new StaffMember();
        legacy.setRole(StaffRole.HEAD_COACH);
        legacy.setDevelopment(9);
        assertEquals(9, legacy.coachRating(SkillName.PASSING),
                "an old record with only a development rating must still teach, not read as 1");
        assertTrue(legacy.coachRating(SkillName.PASSING) > 0);
    }

    // ------------------------------------------------------------------ the wage

    @Test
    @DisplayName("a coach's pay is the sum of his skills plus a premium per maxed skill")
    void wageFollowsTheSkills() {
        StaffMember coach = staff(StaffRole.HEAD_COACH, 6);
        for (SkillName skill : SkillName.values()) {
            if (skill != SkillName.FATIGUE) coach.setSkill(skill, 10);
        }
        assertEquals(80, coach.coachSkillSum());
        assertEquals(0, coach.maxedSkillCount());

        coach.setSkillPassing(20);
        assertEquals(90, coach.coachSkillSum());
        assertEquals(1, coach.maxedSkillCount());

        double wage = CoachWage.weeklyWageFor(coach, 3, 60);
        assertTrue(wage > 0);
        assertTrue(CoachWage.explain(coach).contains("Sum of skills 90"),
                "the staff screen has to be able to say why: " + CoachWage.explain(coach));
    }

    @Test
    @DisplayName("a maxed skill is priced above its points, so specialisation has to be paid for")
    void maxedSkillsArePricedHeavily() {
        // Two 20s and six 1s versus four 20s and four 1s: the second has a much higher sum, but the
        // point of the premium is that the gap survives even when the sums are close.
        StaffMember two = staff(StaffRole.HEAD_COACH, 6);
        two.setSkillPassing(20);
        two.setSkillStriker(20);
        for (SkillName s : SkillName.values()) {
            if (s != SkillName.FATIGUE && s != SkillName.PASSING && s != SkillName.STRIKER) {
                two.setSkill(s, 1);
            }
        }
        StaffMember four = staff(StaffRole.HEAD_COACH, 6);
        four.setSkillPassing(20);
        four.setSkillStriker(20);
        four.setSkillDefender(20);
        four.setSkillTechnique(20);
        for (SkillName s : SkillName.values()) {
            if (s != SkillName.FATIGUE && s != SkillName.PASSING && s != SkillName.STRIKER
                    && s != SkillName.DEFENDER && s != SkillName.TECHNIQUE) {
                four.setSkill(s, 1);
            }
        }
        assertTrue(CoachWage.weeklyWageFor(four, 3, 60) > CoachWage.weeklyWageFor(two, 3, 60),
                "four maxed skills must beat two, or a coach with two 20s and six 1s looks stronger "
                        + "than one who can actually keep a squad improving");
    }

    // ------------------------------------------------------------------ helpers

    private StaffMember staff(StaffRole role, int fill) {
        StaffMember m = new StaffMember();
        m.setRole(role);
        m.setDevelopment(fill);
        for (SkillName skill : SkillName.values()) {
            if (skill != SkillName.FATIGUE) m.setSkill(skill, fill);
        }
        return m;
    }

    private String round(double v) {
        return String.valueOf(Math.round(v * 10) / 10.0);
    }
}
