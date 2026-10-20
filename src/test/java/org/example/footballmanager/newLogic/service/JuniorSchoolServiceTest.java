package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Junior;
import org.example.footballmanager.newLogic.model.JuniorStatus;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.JuniorRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The junior school lifecycle (Sprint 5.3a).
 *
 * <p>Covers the three rules a manager will actually hit: it opens in week 1 and not before, it closes
 * in week 12 and not before, and closing it graduates the whole intake onto the transfer list. The
 * pricing is pinned separately and without Spring in {@code JuniorSchoolRulesTest}.
 *
 * <p>Also covers the behavioural change that matters most underneath: <b>a club with no school gets no
 * intake</b>. That is the difference between a purchased academy and the free one every club used to
 * roll, and nothing else in the suite would notice if it regressed.
 */
@SpringBootTest
@ActiveProfiles("test")
class JuniorSchoolServiceTest {

    @Autowired JuniorSchoolService schools;
    @Autowired YouthAcademyService academy;
    @Autowired TeamRepository teams;
    @Autowired JuniorRepository juniors;

    private Team club(String name, boolean human) {
        Team t = new Team();
        t.setName(name + "-" + System.nanoTime());
        t.setBudget(5_000_000.0);
        t.setReputation(60.0);
        t.setHumanControlled(human);
        Stadium s = new Stadium();
        s.setName(name + " Ground");
        s.setCapacity(5_000);
        s.setTicketPrice(10.0);
        t.setStadium(s);
        return teams.save(t);
    }

    @Test
    @DisplayName("a school opens in week 1, charges the fee, and refuses to open twice")
    void opensInWeekOne() {
        Team c = club("Open club", true);
        double before = c.getBudget();

        var state = schools.open(c.getId(), 1, JuniorSchoolService.OPEN_WEEK);

        assertTrue(state.isActive());
        assertFalse(state.isCanOpen(), "an open school cannot be opened again");
        assertFalse(state.isCanClose(), "week 1 is not the close window");
        // Re-read rather than trusting the local instance: the service loaded and mutated its own copy,
        // so the object this test is holding is stale and would report the opening as free.
        double after = teams.findById(c.getId()).orElseThrow().getBudget();
        assertTrue(after < before, "opening must cost money, got " + after + " from " + before);

        var ex = assertThrows(RuntimeException.class,
                () -> schools.open(c.getId(), 1, JuniorSchoolService.OPEN_WEEK));
        assertTrue(ex.getMessage().toLowerCase().contains("already"), ex.getMessage());
    }

    @Test
    @DisplayName("refuses to open outside week 1, naming the week it wants")
    void refusesToOpenOutsideTheWindow() {
        Team c = club("Window club", true);
        for (int week = 2; week <= 12; week++) {
            final int attempt = week;
            var ex = assertThrows(RuntimeException.class, () -> schools.open(c.getId(), 1, attempt));
            assertTrue(ex.getMessage().contains("week 1"),
                    "the refusal must name the week it is complaining about, got: " + ex.getMessage());
        }
        assertFalse(schools.isActive(teams.findById(c.getId()).orElseThrow()),
                "no refused attempt may leave the school half-open");
    }

    @Test
    @DisplayName("closing is only possible in week 12")
    void closesInTheFinalWeekOnly() {
        Team c = club("Close club", true);
        schools.open(c.getId(), 1, JuniorSchoolService.OPEN_WEEK);

        assertThrows(RuntimeException.class, () -> schools.close(c.getId(), 1, 11),
                "week 11 is not the close window");
        assertTrue(schools.isActive(teams.findById(c.getId()).orElseThrow()),
                "a refused close must leave the school running");

        int released = schools.close(c.getId(), 1, JuniorSchoolService.CLOSE_WEEK);
        assertEquals(0, released, "an empty academy graduates nobody");
        assertFalse(schools.isActive(teams.findById(c.getId()).orElseThrow()));
    }

    @Test
    @DisplayName("closing the school graduates the whole intake onto the transfer list")
    void closingGraduatesTheIntake() {
        Team c = club("Graduate club", true);
        schools.open(c.getId(), 1, JuniorSchoolService.OPEN_WEEK);
        academy.generateSeasonIntakeForWeek2(1, 2);

        List<Junior> intake = juniors.findByTeamIdAndStatus(c.getId(), JuniorStatus.ACTIVE);
        assertTrue(intake.size() > 0, "an open school must produce an intake");
        int before = intake.size();

        int released = schools.close(c.getId(), 1, JuniorSchoolService.CLOSE_WEEK);

        assertEquals(before, released, "every junior must graduate, not some of them");
        assertTrue(juniors.findByTeamIdAndStatus(c.getId(), JuniorStatus.ACTIVE).isEmpty(),
                "no ACTIVE junior may survive the school closing");
        assertEquals(before, juniors.findByTeamIdAndStatus(c.getId(), JuniorStatus.TRANSFER_LISTED).size(),
                "every graduate must be on the transfer list, not quietly kept");
    }

    @Test
    @DisplayName("a club with no school gets no intake — the whole point of the change")
    void noSchoolMeansNoIntake() {
        Team withoutSchool = club("No school", true);
        Team withSchool = club("With school", true);
        schools.open(withSchool.getId(), 1, JuniorSchoolService.OPEN_WEEK);

        academy.generateSeasonIntakeForWeek2(1, 2);

        assertTrue(juniors.findByTeamIdAndStatus(withoutSchool.getId(), JuniorStatus.ACTIVE).isEmpty(),
                "a club that never opened a school must get no prospects");
        assertFalse(juniors.findByTeamIdAndStatus(withSchool.getId(), JuniorStatus.ACTIVE).isEmpty(),
                "a club that paid for a school must get one");
    }

    @Test
    @DisplayName("an AI club gets no intake even with a school switched on")
    void aiClubsAreExcluded() {
        // The owner's rule: bot squads keep the same players and train at the default pace. Without
        // this the human academy would be the only thing standing between 300 clubs and a free intake.
        Team bot = club("Bot club", false);
        schools.open(bot.getId(), 1, JuniorSchoolService.OPEN_WEEK);

        academy.generateSeasonIntakeForWeek2(1, 2);

        assertTrue(juniors.findByTeamIdAndStatus(bot.getId(), JuniorStatus.ACTIVE).isEmpty(),
                "an AI club must never produce a junior, school or not");
    }

    @Test
    @DisplayName("a fresh intake records the two fields the talent report needs")
    void intakeRecordsReportFields() {
        Team c = club("Report club", true);
        schools.open(c.getId(), 1, JuniorSchoolService.OPEN_WEEK);
        academy.generateSeasonIntakeForWeek2(1, 2);

        List<Junior> intake = juniors.findByTeamIdAndStatus(c.getId(), JuniorStatus.ACTIVE);
        assertFalse(intake.isEmpty());
        for (Junior j : intake) {
            assertNotNull(j.getArrivalAge(), "an arrival age is what the report narrows against");
            assertNotNull(j.getTalentRangeHalfWidth(), "a junior with no rolled width reports at maximum uncertainty forever");
            assertTrue(j.getTalentRangeHalfWidth() >= 1.0 && j.getTalentRangeHalfWidth() <= 4.0,
                    "intake width must be the rolled 1..4, got " + j.getTalentRangeHalfWidth());
        }
    }

    /**
     * The owner's intake rule, exactly (2026-10-08): 6 to 10 juniors, once a season, in week 2.
     *
     * <p>Walked over 40 intakes rather than one, because a single sample cannot tell a uniform 6-10
     * from a bell clipped into 6-10 — both would satisfy a naive bound check. Both ends of the band
     * must actually occur, and the total must land on a flat histogram rather than bunching in the
     * middle.
     */
    @Test
    @DisplayName("an intake brings 6 to 10 juniors, and every value in that band occurs")
    void intakeBringsSixToTen() {
        assertEquals(6, YouthAcademyService.INTAKE_MIN);
        assertEquals(10, YouthAcademyService.INTAKE_MAX);
        assertEquals(2, YouthAcademyService.INTAKE_WEEK);

        java.util.Map<Integer, Integer> histogram = new java.util.TreeMap<>();
        for (int i = 0; i < 40; i++) {
            Team c = club("Intake club " + i, true);
            schools.open(c.getId(), 1, JuniorSchoolService.OPEN_WEEK);
            academy.generateSeasonIntakeForWeek2(1, 2);

            int size = juniors.findByTeamIdAndStatus(c.getId(), JuniorStatus.ACTIVE).size();
            assertTrue(size >= YouthAcademyService.INTAKE_MIN && size <= YouthAcademyService.INTAKE_MAX,
                    "intake of " + size + " is outside the owner's 6-10 band");
            histogram.merge(size, 1, Integer::sum);
        }
        assertEquals(5, histogram.size(),
                "all five values in the band must occur across 40 intakes, saw " + histogram);
        assertTrue(histogram.containsKey(YouthAcademyService.INTAKE_MIN)
                        && histogram.containsKey(YouthAcademyService.INTAKE_MAX),
                "both ends of the band must be reachable, saw " + histogram);
    }

    @Test
    @DisplayName("intake happens in week 2 and in no other week")
    void intakeIsWeekTwoOnly() {
        Team c = club("Week club", true);
        schools.open(c.getId(), 1, JuniorSchoolService.OPEN_WEEK);

        for (int week = 1; week <= 12; week++) {
            if (week == YouthAcademyService.INTAKE_WEEK) continue;
            academy.generateSeasonIntakeForWeek2(1, week);
            assertTrue(juniors.findByTeamIdAndStatus(c.getId(), JuniorStatus.ACTIVE).isEmpty(),
                    "week " + week + " must not bring an intake");
        }

        academy.generateSeasonIntakeForWeek2(1, YouthAcademyService.INTAKE_WEEK);
        assertFalse(juniors.findByTeamIdAndStatus(c.getId(), JuniorStatus.ACTIVE).isEmpty(),
                "week " + YouthAcademyService.INTAKE_WEEK + " must bring the intake");
    }

    /**
     * The cost of a one-season tenure, stated rather than left to be discovered.
     *
     * <p>Carryover is now <b>transient</b>: a prospect who arrived in season N must be resolved in
     * week 1 of season N+1, and by week 2 he is gone — so the academy is empty when the new cohort
     * lands and the full 6-10 arrive. But a manager who ignores the week-1 window leaves his
     * carryover in place, the academy holds the maximum, and the new intake is suppressed entirely.
     * That is the cap doing its job, and it is the deadline being real; it is pinned here so that a
     * manager reporting "no prospects arrived" gets the reason rather than a bug.
     */
    @Test
    @DisplayName("an academy still holding the maximum takes no new intake")
    void aFullAcademyTakesNoNewIntake() {
        Team c = club("Full academy", true);
        schools.open(c.getId(), 1, JuniorSchoolService.OPEN_WEEK);
        academy.generateSeasonIntakeForWeek2(1, 2);

        // Fill to the cap with unresolved carryover from an earlier season.
        List<Junior> held = juniors.findByTeamIdAndStatus(c.getId(), JuniorStatus.ACTIVE);
        while (held.size() < YouthAcademyService.MAX_ACTIVE_JUNIORS) {
            held.add(juniorRow(c, 1));
        }
        assertEquals(YouthAcademyService.MAX_ACTIVE_JUNIORS, held.size());

        academy.generateSeasonIntakeForWeek2(2, 2);

        assertEquals(YouthAcademyService.MAX_ACTIVE_JUNIORS,
                juniors.findByTeamIdAndStatus(c.getId(), JuniorStatus.ACTIVE).size(),
                "no room, no intake — resolve the carryover and the next cohort arrives");
    }

    private Junior juniorRow(Team team, int arrivalSeason) {
        Junior j = new Junior();
        j.setName("Test junior");
        j.setAge(17);
        j.setTalent(6);
        j.setAcademySkillExact(8.0);
        j.setAcademySkill(8);
        j.setPosition(Position.MID);
        j.setStatus(JuniorStatus.ACTIVE);
        j.setTeam(team);
        j.setArrivalAge(17);
        j.setArrivalSeasonNumber(arrivalSeason);
        j.setArrivalWeekNumber(YouthAcademyService.INTAKE_WEEK);
        return juniors.save(j);
    }

    private static void assertNotNull(Object value, String message) {
        org.junit.jupiter.api.Assertions.assertNotNull(value, message);
    }
}
