package org.tifo.footballmanager.newLogic.service;

import org.tifo.footballmanager.newLogic.model.SkillName;
import org.tifo.footballmanager.newLogic.model.StaffMember;
import org.tifo.footballmanager.newLogic.model.StaffRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a club's coaching staff is worth for one training skill (owner, 2026-10-18).
 *
 * <p>The owner: *"za svaki skil podesen u slotovima za trening utice: skill glavnog trenera za taj skil +
 * overall glavnog trenera + overall asistenata"*.
 *
 * <p>Every coach here is given all eight skills explicitly, because {@code coachRating} falls back to the
 * legacy attributes when a skill is null — a half-built coach would be measured against a fallback rather
 * than against what the test set, and the test would pass while measuring something else.
 */
class TrainingPercentCoachingTest {

    private static final SkillName[] ALL = {
            SkillName.STAMINA, SkillName.GOALKEEPER, SkillName.DEFENDER, SkillName.PACE,
            SkillName.TECHNIQUE, SkillName.PLAYMAKER, SkillName.PASSING, SkillName.STRIKER};

    @Test
    @DisplayName("a club with nobody who teaches the skill is worth nothing")
    void noStaffIsZero() {
        assertEquals(0, TrainingPercent.coachingStrength(List.of(), SkillName.PASSING, 25));
        assertEquals(0, TrainingPercent.coachingStrength(null, SkillName.PASSING, 25));
    }

    @Test
    @DisplayName("the head coach contributes his skill in the thing and his overall")
    void theHeadCoachContributesTwice() {
        // All eight at 10, so his overall is 10 and his passing is 10: the mean of the two terms is 10.
        assertEquals(10, TrainingPercent.coachingStrength(List.of(coach(StaffRole.HEAD_COACH, 10)),
                SkillName.PASSING, 25));

        // A head coach at 16 all round is worth 16 for every skill.
        assertEquals(16, TrainingPercent.coachingStrength(List.of(coach(StaffRole.HEAD_COACH, 16)),
                SkillName.STRIKER, 25));
    }

    @Test
    @DisplayName("a head coach who is a specialist still answers for his overall")
    void aSpecialistHeadCoachDoesNotBypassHisOverall() {
        // Passing 20, everything else 10: his overall is the mean of the eight, which rounds to 11.
        StaffMember head = coach(StaffRole.HEAD_COACH, 10);
        head.setSkill(SkillName.PASSING, 20);
        assertEquals(11, head.overallSkill(), "the overall is the mean of his eight skills");

        // So the club is worth the mean of (20 passing, 11 overall) = 16 — not 20. A head coach who is
        // brilliant at one thing and ordinary at everything else is not a complete coach.
        assertEquals(16, TrainingPercent.coachingStrength(List.of(head), SkillName.PASSING, 25));
    }

    @Test
    @DisplayName("assistants are counted, and a good one raises the club")
    void assistantsRaiseTheClub() {
        StaffMember head = coach(StaffRole.HEAD_COACH, 10);
        assertEquals(13, TrainingPercent.coachingStrength(
                List.of(head, coach(StaffRole.ASSISTANT, 20)), SkillName.PASSING, 25),
                "a 20 assistant lifts a 10 head coach");

        // Two of them lift it further.
        int one = TrainingPercent.coachingStrength(List.of(head, coach(StaffRole.ASSISTANT, 20)),
                SkillName.PASSING, 25);
        int two = TrainingPercent.coachingStrength(
                List.of(head, coach(StaffRole.ASSISTANT, 20), coach(StaffRole.ASSISTANT, 20)),
                SkillName.PASSING, 25);
        assertTrue(two > one, "a second assistant is worth having: " + one + " then " + two);
    }

    @Test
    @DisplayName("an assistant worse than the head coach must not make the club worse")
    void aWeakAssistantIsNeverADowngrade() {
        StaffMember head = coach(StaffRole.HEAD_COACH, 20);
        int alone = TrainingPercent.coachingStrength(List.of(head), SkillName.PASSING, 25);
        int withWeak = TrainingPercent.coachingStrength(
                List.of(head, coach(StaffRole.ASSISTANT, 5)), SkillName.PASSING, 25);
        assertEquals(alone, withWeak,
                "hiring somebody worse than the head coach must not be a downgrade, or a manager stops "
                        + "hiring at all");
    }

    @Test
    @DisplayName("a specialist counts for his own skill and for nothing else")
    void specialistsAreSpecialists() {
        StaffMember gkCoach = coach(StaffRole.GK_COACH, 20);

        assertTrue(TrainingPercent.coachingStrength(List.of(gkCoach), SkillName.GOALKEEPER, 25) > 0,
                "a goalkeeping coach teaches goalkeeping");
        assertEquals(0, TrainingPercent.coachingStrength(List.of(gkCoach), SkillName.STRIKER, 25),
                "and nothing else");

        // The physio teaches stamina, and the youth coach only players under 23.
        assertTrue(TrainingPercent.coachingStrength(List.of(coach(StaffRole.PHYSIO, 15)),
                SkillName.STAMINA, 25) > 0);
        assertTrue(TrainingPercent.coachingStrength(List.of(coach(StaffRole.YOUTH_COACH, 15)),
                SkillName.PASSING, 18) > 0);
        assertEquals(0, TrainingPercent.coachingStrength(List.of(coach(StaffRole.YOUTH_COACH, 15)),
                SkillName.PASSING, 30), "a youth coach does not teach a thirty-year-old");
    }

    @Test
    @DisplayName("a scout and a doctor take no training session")
    void nonCoachingRolesContributeNothing() {
        assertEquals(0, TrainingPercent.coachingStrength(List.of(coach(StaffRole.SCOUT, 20)),
                SkillName.PASSING, 25));
        assertEquals(0, TrainingPercent.coachingStrength(List.of(coach(StaffRole.DOCTOR, 20)),
                SkillName.PASSING, 25));
    }

    @Test
    @DisplayName("the result is always on the 1-20 scale the training formula divides by")
    void theResultStaysOnScale() {
        List<StaffMember> full = List.of(
                coach(StaffRole.HEAD_COACH, 20), coach(StaffRole.ASSISTANT, 20),
                coach(StaffRole.ASSISTANT, 20), coach(StaffRole.ASSISTANT, 20));
        int best = TrainingPercent.coachingStrength(full, SkillName.PASSING, 25);
        assertTrue(best <= 20, "a club of perfect coaches must not exceed the scale: " + best);
        assertEquals(20, best, "and should reach the top of it");

        // And the formula that consumes it behaves: better coaching is a better week, never a worse one.
        assertTrue(TrainingPercent.percent(10, best, 120) > TrainingPercent.percent(10, 1, 120));
    }

    private static StaffMember coach(StaffRole role, int value) {
        StaffMember member = new StaffMember();
        member.setRole(role);
        member.setName(role.name() + " " + value);
        for (SkillName skill : ALL) {
            member.setSkill(skill, value);
        }
        return member;
    }
}
