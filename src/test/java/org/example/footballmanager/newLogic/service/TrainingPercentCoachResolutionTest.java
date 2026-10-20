package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.model.StaffMember;
import org.example.footballmanager.newLogic.model.StaffRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 4.3 — coaching staff has to actually matter.
 *
 * <p>The bug these exist to prevent is the quiet one: a club hires a goalkeeping coach, his wage
 * leaves the account every week of the season, and the goalkeeper improves at exactly the same rate
 * as he would have with nobody in the room. The feature existed, the UI listed him, and nothing
 * happened.
 */
class TrainingPercentCoachResolutionTest {

    private static StaffMember staff(StaffRole role, String name) {
        StaffMember m = new StaffMember();
        m.setName(name);
        m.setRole(role);
        m.setDevelopment(10);
        m.setMotivation(10);
        for (SkillName skill : SkillName.values()) {
            m.setSkill(skill, 10);
        }
        return m;
    }

    @Test
    @DisplayName("A goalkeeping coach improves goalkeeping and nothing else")
    void gkCoachIsASpecialist() {
        assertTrue(TrainingPercent.teaches(StaffRole.GK_COACH, SkillName.GOALKEEPER, 30));
        assertFalse(TrainingPercent.teaches(StaffRole.GK_COACH, SkillName.STRIKER, 30));
        assertFalse(TrainingPercent.teaches(StaffRole.GK_COACH, SkillName.PASSING, 30));
        assertFalse(TrainingPercent.teaches(StaffRole.GK_COACH, SkillName.DEFENDER, 30));
    }

    @Test
    @DisplayName("A physio teaches stamina and condition, which is the whole of his job")
    void physioIsASpecialist() {
        assertTrue(TrainingPercent.teaches(StaffRole.PHYSIO, SkillName.STAMINA, 30));
        assertTrue(TrainingPercent.teaches(StaffRole.PHYSIO, SkillName.FATIGUE, 30));
        assertFalse(TrainingPercent.teaches(StaffRole.PHYSIO, SkillName.GOALKEEPER, 30));
    }

    @Test
    @DisplayName("A scout is not a coach, however good he is")
    void scoutNeverTeaches() {
        StaffMember scout = staff(StaffRole.SCOUT, "Eyes");
        for (SkillName skill : SkillName.values()) {
            assertFalse(TrainingPercent.teaches(StaffRole.SCOUT, skill, 30),
                    "a scout was allowed to teach " + skill);
        }
        assertNull(TrainingPercent.coachForSkill(List.of(scout), SkillName.STRIKER, 30),
                "a club with only a scout should have no coach, not a scout");
    }

    @Test
    @DisplayName("A youth coach is only counted for players young enough to be his responsibility")
    void youthCoachOnlyForTheYoung() {
        assertTrue(TrainingPercent.teaches(StaffRole.YOUTH_COACH, SkillName.PASSING, 19));
        assertTrue(TrainingPercent.teaches(StaffRole.YOUTH_COACH, SkillName.PASSING, 22));
        assertFalse(TrainingPercent.teaches(StaffRole.YOUTH_COACH, SkillName.PASSING, 23),
                "a youth coach was coaching a 23-year-old");
        assertFalse(TrainingPercent.teaches(StaffRole.YOUTH_COACH, SkillName.PASSING, 31));
    }

    @Test
    @DisplayName("The best available coach for the skill wins, not the first one listed")
    void bestCoachForTheSkillWins() {
        StaffMember head = staff(StaffRole.HEAD_COACH, "Head");
        head.setSkill(SkillName.GOALKEEPER, 8);
        StaffMember keeper = staff(StaffRole.GK_COACH, "Keeper");
        keeper.setSkill(SkillName.GOALKEEPER, 18);

        assertSame(keeper, TrainingPercent.coachForSkill(List.of(head, keeper), SkillName.GOALKEEPER, 30));
        // ...and the head coach still handles what the specialist cannot teach.
        assertSame(head, TrainingPercent.coachForSkill(List.of(head, keeper), SkillName.STRIKER, 30));
    }

    @Test
    @DisplayName("Hiring a goalkeeping coach measurably speeds up a goalkeeper and nobody else")
    void hiringASpecialistChangesOnlyThatSkill() {
        StaffMember head = staff(StaffRole.HEAD_COACH, "Head");
        head.setSkill(SkillName.GOALKEEPER, 10);
        head.setMotivation(20);
        StaffMember keeper = staff(StaffRole.GK_COACH, "Keeper");
        keeper.setSkill(SkillName.GOALKEEPER, 20);
        keeper.setMotivation(20);

        double before = TrainingPercent.percent(9.0, head.coachRating(SkillName.GOALKEEPER), 120);
        StaffMember goalkeeperCoach =
                TrainingPercent.coachForSkill(List.of(head, keeper), SkillName.GOALKEEPER, 30);
        double after = TrainingPercent.percent(9.0, goalkeeperCoach.coachRating(SkillName.GOALKEEPER), 120);
        assertTrue(after > before,
                "hiring a 20-rated goalkeeping coach did not improve goalkeeping growth");

        double strikerBefore = TrainingPercent.percent(9.0, head.coachRating(SkillName.STRIKER), 120);
        StaffMember strikerCoach =
                TrainingPercent.coachForSkill(List.of(head, keeper), SkillName.STRIKER, 30);
        double strikerAfter = TrainingPercent.percent(9.0, strikerCoach.coachRating(SkillName.STRIKER), 120);
        assertEquals(strikerBefore, strikerAfter, 1e-9,
                "hiring a goalkeeping coach improved striking as well");
    }

    @Test
    @DisplayName("A poor coach makes the week worse than the percentage implies")
    void poorCoachHurts() {
        StaffMember good = staff(StaffRole.HEAD_COACH, "Good");
        good.setMotivation(20);
        StaffMember poor = staff(StaffRole.HEAD_COACH, "Poor");
        poor.setMotivation(1);
        StaffMember indifferent = staff(StaffRole.HEAD_COACH, "Average");
        indifferent.setMotivation(10);

        assertTrue(TrainingPercent.disciplineFactor(poor) < 1.0,
                "a coach nobody listens to does not reduce growth");
        assertTrue(TrainingPercent.disciplineFactor(poor)
                        < TrainingPercent.disciplineFactor(indifferent),
                "a poor coach is worth as much as an average one");
        assertTrue(TrainingPercent.disciplineFactor(good) <= 1.0,
                "man-management should reduce waste, not add a bonus on top of the approved formula");
    }

    @Test
    @DisplayName("Nobody hired is not the same as somebody hired badly")
    void noCoachIsNotAPunishment() {
        assertEquals(1.0, TrainingPercent.disciplineFactor(null), 1e-9,
                "a club with no staff was punished for having an empty staff list");

        // A coach with nothing recorded is a mediocre coach, not a perfect one and not a bad one.
        // That is the same convention StaffMember.coachRating already uses for its own fallbacks,
        // and it is the only one that is safe: defaulting an empty record to 20 would silently hand
        // every backfilled club a perfect coach, and defaulting it to 1 would make every one of them
        // a bad one. Either would be a data artefact wearing a rule's clothes.
        StaffMember unrated = new StaffMember();
        double factor = TrainingPercent.disciplineFactor(unrated);
        assertTrue(factor > 0.75 && factor < 1.0,
                "a coach with no motivation recorded should be treated as mediocre, got " + factor);
        assertEquals(TrainingPercent.disciplineFactor(staff(StaffRole.HEAD_COACH, "A")), factor, 1e-9,
                "an unrated coach and an average coach should be the same thing");
    }

    @Test
    @DisplayName("The discipline penalty stays within its stated bounds")
    void penaltyIsBounded() {
        for (int motivation = 1; motivation <= 20; motivation++) {
            StaffMember m = staff(StaffRole.HEAD_COACH, "X");
            m.setMotivation(motivation);
            double factor = TrainingPercent.disciplineFactor(m);
            assertTrue(factor >= 0.75 && factor <= 1.0,
                    "discipline factor out of range at motivation " + motivation + ": " + factor);
        }
    }

    @Test
    @DisplayName("No staff, no coach, and a valid week anyway")
    void emptyStaffIsHandled() {
        assertNull(TrainingPercent.coachForSkill(List.of(), SkillName.STRIKER, 30));
        assertNull(TrainingPercent.coachForSkill(null, SkillName.STRIKER, 30));
        assertNull(TrainingPercent.coachForSkill(List.of(staff(StaffRole.HEAD_COACH, "H")),
                null, 30));
    }
}
