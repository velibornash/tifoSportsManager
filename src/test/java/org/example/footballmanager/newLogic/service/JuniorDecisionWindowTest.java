package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.exception.ApiException;
import org.example.footballmanager.newLogic.model.Junior;
import org.example.footballmanager.newLogic.model.JuniorStatus;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.JuniorRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two owner rules, both about when a prospect may be decided (Sprint 5.3, 2026-09-27).
 *
 * <ul>
 *   <li><b>The position is known at intake.</b> It used to be rolled at promotion, so a manager paid for
 *       a school, signed a fifteen-year-old with no position listed, and got a goalkeeper out.</li>
 *   <li><b>Decisions are a window: weeks 1-2 only.</b> Promoting a youth player is a registration
 *       decision, not a match-day one.</li>
 * </ul>
 *
 * <p>And the rule that overrides the second one: <b>a junior who reaches 20 is promoted whether the
 * manager is ready or not</b>, in whatever week that falls.
 */
@SpringBootTest
@ActiveProfiles("test")
class JuniorDecisionWindowTest {

    @Autowired YouthAcademyService academy;
    @Autowired JuniorSchoolService schools;
    @Autowired TeamRepository teams;
    @Autowired JuniorRepository juniors;

    private Team club;
    private Team overflow;
    /** Juniors already handed to a test, so a second call does not return the same one. */
    private final Set<Long> handedOut = new HashSet<>();

    @BeforeEach
    void setUp() {
        Team t = new Team();
        t.setName("Window club-" + System.nanoTime());
        t.setBudget(9_000_000.0);
        t.setReputation(60.0);
        t.setHumanControlled(true);
        Stadium s = new Stadium();
        s.setName("Window Ground");
        s.setCapacity(5_000);
        s.setTicketPrice(10.0);
        t.setStadium(s);
        club = teams.save(t);
        // A second club, used only when the first runs out of prospects. See carryover() for why a
        // single club is not enough.
        Team second = new Team();
        second.setName("Overflow club-" + System.nanoTime());
        second.setBudget(9_000_000.0);
        second.setReputation(60.0);
        second.setHumanControlled(true);
        Stadium g = new Stadium();
        g.setName("Overflow Ground");
        g.setCapacity(5_000);
        g.setTicketPrice(10.0);
        second.setStadium(g);
        overflow = teams.save(second);
        schools.open(overflow.getId(), 1, YouthAcademyService.DECISION_WINDOW_FIRST_WEEK);

        // Opened once here. Several tests need more than one carryover junior, and re-opening the
        // school is a real conflict rather than a fixture convenience.
        schools.open(club.getId(), 1, YouthAcademyService.DECISION_WINDOW_FIRST_WEEK);
    }

    /**
     * A junior who arrived last season, so the only thing between him and a decision is the week.
     *
     * <p>Two things made this flaky before, and both are worth recording:
     *
     * <ul>
     *   <li>the academy caps at ten active juniors, so a test wanting three prospects could run the
     *       pool dry;</li>
     *   <li>and {@code generateSeasonIntakeForWeek2} is idempotent per (team, season, week) — it
     *       skips when that season and week already produced an intake. So "generate another intake"
     *       silently does nothing, and the helper sat there with an empty pool and a cheerful
     *       assertion failure.</li>
     * </ul>
     *
     * <p>So the fallback is a <b>second club</b>, not a second intake call: it has its own ten slots
     * and its own season/week, which makes the helper deterministic regardless of how many
     * prospects the random intake happens to produce.
     */
    private Junior carryover(int age) {
        for (Team source : List.of(club, overflow)) {
            List<Junior> available = juniors.findByTeamIdAndStatus(source.getId(), JuniorStatus.ACTIVE)
                    .stream()
                    .filter(j -> !handedOut.contains(j.getId()))
                    .collect(Collectors.toList());
            if (available.isEmpty()) {
                academy.generateSeasonIntakeForWeek2(1, 2);
                available = juniors.findByTeamIdAndStatus(source.getId(), JuniorStatus.ACTIVE).stream()
                        .filter(j -> !handedOut.contains(j.getId()))
                        .collect(Collectors.toList());
            }
            if (available.isEmpty()) {
                continue;
            }
            Junior junior = available.get(0);
            handedOut.add(junior.getId());
            junior.setArrivalSeasonNumber(1);
            junior.setArrivalAge(junior.getAge());
            junior.setAge(age);
            junior.setStatus(JuniorStatus.ACTIVE);
            return juniors.save(junior);
        }
        throw new AssertionError("both academies are exhausted; the fixture cannot supply another junior");
    }

    // ── the window ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the decision window is week 1 and nothing else (owner, 2026-10-08)")
    void windowIsWeekOneOnly() {
        assertEquals(1, YouthAcademyService.DECISION_WINDOW_FIRST_WEEK);
        assertEquals(1, YouthAcademyService.DECISION_WINDOW_LAST_WEEK);
        assertTrue(YouthAcademyService.isDecisionWindow(1));
        for (int week = 2; week <= 12; week++) {
            assertFalse(YouthAcademyService.isDecisionWindow(week),
                    "week " + week + " must be outside the registration window");
        }
    }

    @Test
    @DisplayName("a decision is allowed in week 1 and refused from week 2 onwards")
    void decisionsRefusedOutsideTheWindow() {
        Junior week1 = carryover(18);
        academy.promoteJunior(week1.getId(), 2, 1, false);
        assertEquals(JuniorStatus.PROMOTED, juniors.findById(week1.getId()).orElseThrow().getStatus(),
                "week 1 must allow the decision");

        // Week 2 was the last day of the window when it ran 1-2. It is now the intake week of the
        // following season's cohort and nothing else, so it must refuse like any other week.
        Junior week2 = carryover(18);
        assertThrows(ApiException.class, () -> academy.releaseJunior(week2.getId(), 2, 2, false),
                "week 2 is outside a one-week window");
        assertEquals(JuniorStatus.ACTIVE, juniors.findById(week2.getId()).orElseThrow().getStatus(),
                "a refused decision must leave the junior exactly as he was");

        Junior week3 = carryover(18);
        assertThrows(ApiException.class, () -> academy.promoteJunior(week3.getId(), 2, 3, false),
                "week 3 is outside the window");
        assertEquals(JuniorStatus.ACTIVE, juniors.findById(week3.getId()).orElseThrow().getStatus(),
                "a refused decision must leave the junior exactly as he was");
    }

    @Test
    @DisplayName("the refusal names the window and the week, so the manager knows when to come back")
    void refusalExplainsItself() {
        Junior junior = carryover(18);
        ApiException ex = assertThrows(ApiException.class, () -> academy.promoteJunior(junior.getId(), 2, 7, false));

        assertEquals("DECISION_WINDOW_CLOSED", ex.getCode());
        assertTrue(ex.getMessage().contains("week 1"),
                "the message must name the window, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("week 7"),
                "the message must name the week it actually is, got: " + ex.getMessage());
    }

    // ── the tenure, which overrides the window ─────────────────────────────────────────────────

    @Test
    @DisplayName("a junior whose academy season is over is transfer-listed, not promoted")
    void expiredJuniorIsTransferListed() {
        // Owner, 2026-10-08. The age ceiling is gone and the tenure replaced it: the deadline is one
        // season, not twenty years old. This path holds no manager decision, so it is not bound by the
        // week-1 window — it fires from the season rollover, and must still fire in any week.
        assertFalse(YouthAcademyService.isDecisionWindow(7), "week 7 is outside the window");
        assertEquals(JuniorStatus.TRANSFER_LISTED, YouthAcademyService.EXPIRED_JUNIOR_STATUS,
                "an unresolved prospect goes on the market, he does not walk into the first team");

        Junior junior = carryover(19);
        junior.setAge(YouthAcademyService.GRADUATION_MAX_AGE);
        juniors.save(junior);

        // carryover() stamps arrivalSeasonNumber = 1, so closing season 2 expires him.
        int listed = academy.graduateExpiredJuniors(2);

        assertTrue(listed >= 1, "the tenure must fire regardless of the week");
        assertEquals(JuniorStatus.TRANSFER_LISTED,
                juniors.findById(junior.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("a junior still inside his tenure is left alone by the expiry pass")
    void juniorInsideHisTenureIsUntouched() {
        // The counterpart that makes the rule mean one season rather than "everyone, always". A
        // prospect who arrived this season has not served it yet.
        //
        // The intake is generated here rather than assumed, because `assertEquals(0, ...)` over an
        // empty academy is the kind of test that cannot fail: it is green whether the sweep works or
        // whether there was never anything to sweep. The precondition is asserted first so the zero
        // below means something.
        academy.generateSeasonIntakeForWeek2(4, 2);
        List<Junior> thisSeason = juniors.findByTeamIdAndStatus(club.getId(), JuniorStatus.ACTIVE);
        assertFalse(thisSeason.isEmpty(), "precondition: the academy has prospects to protect");
        assertTrue(thisSeason.stream().allMatch(j -> j.getArrivalSeasonNumber() == 4));

        // The sweep is global, so its return count is not this test's to assert on — other fixtures in
        // the shared test database hold older cohorts. What is this club's is whether his prospects
        // survived, and that is asserted by id rather than by a total.
        academy.graduateExpiredJuniors(4);

        for (Junior junior : thisSeason) {
            assertEquals(JuniorStatus.ACTIVE, juniors.findById(junior.getId()).orElseThrow().getStatus(),
                    junior.getName() + " arrived in season 4 and must survive the season-4 pass");
        }

        // And the same cohort does expire the moment the next season closes.
        academy.graduateExpiredJuniors(5);
        for (Junior junior : thisSeason) {
            JuniorStatus after = juniors.findById(junior.getId()).orElseThrow().getStatus();
            assertTrue(after == JuniorStatus.TRANSFER_LISTED || after == JuniorStatus.RELEASED,
                    junior.getName() + " served his one season and must be resolved, was " + after);
        }
    }

    // ── the promotion reveal ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("promoting reveals the exact talent to a subscriber")
    void revealCarriesTalentForASubscriber() {
        // Found by the owner: the reveal screen is called "Junior Promotion Reveal" and showed no
        // talent. The rule had been implemented on JuniorAcademyItemDTO, while the reveal screen
        // reads JuniorPromotionResultDTO -- a different DTO with no talent field at all.
        Junior junior = carryover(18);
        junior.setPosition(Position.ATT);
        junior.setTalent(8.0);
        juniors.save(junior);

        var reveal = academy.promoteJuniorWithReveal(junior.getId(), 2, 1, true);

        assertNotNull(reveal.getTalent(),
                "a subscriber must be told the ceiling they just paid to see");
        assertEquals(8.0, reveal.getTalent());
        assertNotNull(reveal.getPlayerId(), "and the player must still be created either way");
    }

    @Test
    @DisplayName("promoting reveals the talent to nobody without a subscription, but still makes the player")
    void revealWithholdsTalentFromANonSubscriber() {
        Junior junior = carryover(18);
        junior.setPosition(Position.ATT);
        junior.setTalent(9.0);
        juniors.save(junior);

        var reveal = academy.promoteJuniorWithReveal(junior.getId(), 2, 1, false);

        org.junit.jupiter.api.Assertions.assertNull(reveal.getTalent(),
                "the reveal is the paid moment; a non-subscriber must not get the ceiling from it");
        assertNotNull(reveal.getPlayerId(),
                "and the promotion itself must still happen -- the subscription gates information, not the club's own player");
        assertNotNull(reveal.getAllocatedSkills(), "the skills are the manager's own work and are always shown");
    }

    // ── position at intake ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a junior has a position from the moment he arrives")
    void positionIsKnownAtIntake() {
        academy.generateSeasonIntakeForWeek2(1, 2);

        List<Junior> intake = juniors.findByTeamIdAndStatus(club.getId(), JuniorStatus.ACTIVE);
        assertFalse(intake.isEmpty());
        for (Junior junior : intake) {
            assertNotNull(junior.getPosition(),
                    "a prospect signed with no position is the bug this field exists to remove");
        }
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    @DisplayName("promotion keeps the position he was signed for")
    void promotionKeepsThePosition() {
        Junior junior = carryover(18);
        Position signed = Position.GK;
        junior.setPosition(signed);
        juniors.save(junior);

        academy.promoteJunior(junior.getId(), 2, 1, false);

        Junior promoted = juniors.findById(junior.getId()).orElseThrow();
        assertEquals(signed, promoted.getPosition());
        assertNotNull(promoted.getPromotedPlayer());
        assertEquals(signed, promoted.getPromotedPlayer().getPosition(),
                "the senior player must play the position he was signed for, not a fresh roll");
    }

    @Test
    @DisplayName("a legacy junior with no recorded position is given one before he graduates")
    void legacyPositionsAreRepaired() {
        academy.generateSeasonIntakeForWeek2(1, 2);
        List<Junior> intake = juniors.findByTeamIdAndStatus(club.getId(), JuniorStatus.ACTIVE);
        for (Junior junior : intake) {
            junior.setPosition(null);
        }
        juniors.saveAll(intake);

        int nullsBefore = (int) juniors.findByPositionIsNull().stream()
                .filter(j -> club.getId().equals(j.getTeam().getId()))
                .count();
        assertTrue(nullsBefore > 0, "the fixture must actually contain a junior with no position");

        int repaired = academy.assignMissingPositions();

        assertTrue(repaired >= nullsBefore, "the repair must find the rows it was given");
        assertTrue(juniors.findByPositionIsNull().stream()
                        .noneMatch(j -> club.getId().equals(j.getTeam().getId())),
                "no junior of this club may be left without a position");
    }

}
