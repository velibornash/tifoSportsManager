package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.PlayerRole;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Individual training focus: one or two skills, per player, per week (Sprint 4.1).
 *
 * <p>The backlog's verify line, verbatim: <i>assign a striker "shooting" focus → his shooting grows
 * at the direct rate and his other skills at the general rate.</i> The interesting part is not that
 * it works but that it <b>bypasses the role's allow-list</b> — a striker focused on heading is a
 * decision the manager made, and a system that quietly rewrites it to "shooting" is refusing the job.
 */
@SpringBootTest
@ActiveProfiles("test")
class TrainingFocusServiceTest {

    @Autowired TrainingFocusService focus;
    @Autowired PlayerRepository players;
    @Autowired TeamRepository teams;

    private Team club;
    private Team rival;

    @BeforeEach
    void setUp() {
        club = club("Focus club");
        rival = club("Rival club");
    }

    private Team club(String name) {
        Team t = new Team();
        t.setName(name + "-" + System.nanoTime());
        t.setBudget(5_000_000.0);
        t.setReputation(60.0);
        Stadium s = new Stadium();
        s.setName(name + " Ground");
        s.setCapacity(20_000);
        s.setTicketPrice(18.0);
        s.setPitchQuality(80.0);
        s.setPitchCondition(80);
        s.setMaintenanceRemaining(0);
        t.setStadium(s);
        return teams.save(t);
    }

    private Player aPlayer(Team at, Position position, int age, double talent) {
        Player p = new Player();
        p.setName("P" + System.nanoTime());
        p.setTeam(at);
        p.setPosition(position);
        p.setAge(age);
        p.setTalent(talent);
        p.setPlayerValue(1_000_000);
        p.setEarnings(3_000);
        p.setForm(6.0);
        p.setMorale(60.0);
        return players.save(p);
    }

    @Test
    @DisplayName("a focus overrides the role default")
    void focusWinsOverTheRole() {
        Player striker = aPlayer(club, Position.ATT, 24, 8.0);
        SkillName roleDefault = SkillName.STRIKER;

        assertEquals(roleDefault, focus.primarySkillFor(striker, roleDefault, 1, 1),
                "with no focus, the role default stands");

        focus.setFocus(club.getId(), striker.getId(), 1, 1, List.of(SkillName.DEFENDER));

        assertEquals(SkillName.DEFENDER, focus.primarySkillFor(striker, roleDefault, 1, 1),
                "an individual focus is an explicit decision and is taken at face value");
    }

    @Test
    @DisplayName("any of the eight skills may be focused, including the ones a role forbids")
    void theRoleAllowListDoesNotApply() {
        Player striker = aPlayer(club, Position.ATT, 24, 8.0);

        // The backlog's own instruction: allow goalkeeping for any player, and add stamina. The
        // restriction is gone rather than widened, so all eight work for everyone.
        for (SkillName skill : List.of(SkillName.GOALKEEPER, SkillName.STAMINA,
                SkillName.PASSING, SkillName.PACE)) {
            focus.setFocus(club.getId(), striker.getId(), 1, 1, List.of(skill));
            assertEquals(skill, focus.primarySkillFor(striker, SkillName.STRIKER, 1, 1),
                    "a striker may be focused on " + skill);
        }
    }

    @Test
    @DisplayName("one or two skills, and a third is trimmed rather than refused")
    void atMostTwo() {
        Player p = aPlayer(club, Position.MID, 24, 8.0);

        List<SkillName> three = focus.setFocus(club.getId(), p.getId(), 1, 1,
                List.of(SkillName.PASSING, SkillName.STRIKER, SkillName.PACE));

        assertEquals(2, three.size(), "three skills is a programme, not a focus");
        assertEquals(List.of(SkillName.PASSING, SkillName.STRIKER), three,
                "and the manager's own first two are kept, in his order");

        assertEquals(SkillName.PASSING, focus.primarySkillFor(p, SkillName.PLAYMAKER, 1, 1));
        assertEquals(SkillName.STRIKER, focus.secondarySkillFor(p, 1, 1));
    }

    @Test
    @DisplayName("fatigue is not something a coach teaches, and is never a focus")
    void fatigueIsNotFocusable() {
        Player p = aPlayer(club, Position.MID, 24, 8.0);
        assertTrue(focus.setFocus(club.getId(), p.getId(), 1, 1,
                List.of(SkillName.FATIGUE)).isEmpty());
    }

    @Test
    @DisplayName("a focus lasts one week")
    void focusIsPerWeek() {
        Player p = aPlayer(club, Position.MID, 24, 8.0);
        focus.setFocus(club.getId(), p.getId(), 1, 5, List.of(SkillName.PASSING));

        assertTrue(focus.hasFocus(p, 1, 5));
        assertFalse(focus.hasFocus(p, 1, 6), "next week he is back on the programme");
        assertEquals(SkillName.PLAYMAKER, focus.primarySkillFor(p, SkillName.PLAYMAKER, 1, 6));
    }

    @Test
    @DisplayName("setting a focus twice replaces it rather than accumulating")
    void settingTwiceReplaces() {
        Player p = aPlayer(club, Position.MID, 24, 8.0);

        focus.setFocus(club.getId(), p.getId(), 1, 1, List.of(SkillName.PASSING, SkillName.STRIKER));
        List<SkillName> second = focus.setFocus(club.getId(), p.getId(), 1, 1,
                List.of(SkillName.PACE));

        assertEquals(List.of(SkillName.PACE), second);
        assertNull(focus.secondarySkillFor(p, 1, 1),
                "changing his mind on Thursday must not leave a second skill behind");
    }

    @Test
    @DisplayName("clearing a focus hands him back to the programme")
    void clearingRestoresTheDefault() {
        Player p = aPlayer(club, Position.MID, 24, 8.0);
        focus.setFocus(club.getId(), p.getId(), 1, 1, List.of(SkillName.PASSING));
        assertTrue(focus.hasFocus(p, 1, 1));

        assertEquals(1, focus.clearFocus(p.getId(), 1, 1));
        assertFalse(focus.hasFocus(p, 1, 1));
        assertEquals(SkillName.PLAYMAKER, focus.primarySkillFor(p, SkillName.PLAYMAKER, 1, 1));
    }

    @Test
    @DisplayName("a club cannot set a focus on a rival's player")
    void onlyTheOwningClubDecides() {
        Player theirs = aPlayer(rival, Position.MID, 24, 8.0);

        // The owning club may, of course, set it.
        assertEquals(List.of(SkillName.PASSING),
                focus.setFocus(rival.getId(), theirs.getId(), 1, 1, List.of(SkillName.PASSING)));

        // A different club may not, however plausible the reason.
        assertTrue(focus.setFocus(club.getId(), theirs.getId(), 1, 2, List.of(SkillName.PACE))
                        .isEmpty(),
                "otherwise a manager could write training data for another club's player");
        assertFalse(focus.hasFocus(theirs, 1, 2));
    }

    @Test
    @DisplayName("a squad's focuses are readable in one go")
    void squadFocusIsReadable() {
        Player one = aPlayer(club, Position.ATT, 24, 8.0);
        Player two = aPlayer(club, Position.DEF, 24, 8.0);
        focus.setFocus(club.getId(), one.getId(), 1, 1, List.of(SkillName.PASSING));
        focus.setFocus(club.getId(), two.getId(), 1, 1, List.of(SkillName.PACE, SkillName.STRIKER));

        var bySquad = focus.focusForSquad(club.getId(), 1, 1);
        assertEquals(2, bySquad.size());
        assertEquals(List.of(SkillName.PASSING), bySquad.get(one.getId()));
        assertEquals(2, bySquad.get(two.getId()).size());
    }

    @Test
    @DisplayName("a null or unknown player falls back to the programme instead of throwing")
    void rubbishInFallsBack() {
        // A null player falls back to the programme rather than throwing - a missing player must
        // not take out the whole week's training run.
        assertEquals(SkillName.PASSING, focus.primarySkillFor(null, SkillName.PASSING, 1, 1));
        Player ghost = aPlayer(club, Position.MID, 24, 8.0);
        players.delete(ghost);
        assertEquals(SkillName.PASSING, focus.primarySkillFor(ghost, SkillName.PASSING, 1, 1),
                "a deleted player with no focus row must not break the week");
    }
}
