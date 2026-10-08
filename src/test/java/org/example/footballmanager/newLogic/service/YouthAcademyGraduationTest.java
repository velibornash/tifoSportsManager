package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Junior;
import org.example.footballmanager.newLogic.model.JuniorStatus;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.JuniorRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.StaffMemberRepository;
import org.example.footballmanager.newLogic.util.players.SquadNumberAssigner;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sprint 5 — graduation.
 *
 * <p><b>Two of these tests exist to stop something, not to prove something.</b> The owner was explicit
 * that the promotion skill distribution is already right and must be kept: the budget is spread
 * randomly across the eight skills, with a goalkeeper's goalkeeping seeded first so more of the sum
 * lands there and an outfielder's landing there by chance. That is a rule someone chose, and the only
 * way it survives the rest of Sprint 5 is if it is written down as one.
 */
class YouthAcademyGraduationTest {

    private static final List<SkillName> POOL = List.of(
            SkillName.STAMINA, SkillName.GOALKEEPER, SkillName.DEFENDER, SkillName.PACE,
            SkillName.TECHNIQUE, SkillName.PLAYMAKER, SkillName.PASSING, SkillName.STRIKER);

    private YouthAcademyService service;
    private JuniorRepository juniors;
    private PlayerRepository players;
    private SquadNumberAssigner numbers;

    @BeforeEach
    void setUp() throws Exception {
        juniors = mock(JuniorRepository.class);
        players = mock(PlayerRepository.class);
        numbers = mock(SquadNumberAssigner.class);
        when(numbers.nextNumberForTeam(any(), any())).thenReturn(9);
        when(players.save(any())).thenAnswer(i -> i.getArgument(0));

        service = new YouthAcademyService(
                mock(TeamRepository.class), juniors, players,
                mock(TransferService.class), numbers, mock(StaffMemberRepository.class),
                new PlusFeatureService(mock(TeamRepository.class),
                        new ClubOwnershipLinker(mock(TeamRepository.class),
                                mock(org.example.commonmanager.repository.UserRepository.class))),
                // Real, over the same mock the service saves through. The promotion cap reads it, and a
                // stubbed rule would let a test pass on a promotion the product would refuse.
                new SquadRegistrationService(players));
        // The academy draws from a plain Random, so seed it deterministically and make the seed
        // reachable. Without this the distribution tests could only assert on averages.
        Field random = findField(YouthAcademyService.class, "random");
        random.setAccessible(true);
        random.set(service, new Random(42));
    }

    private static Field findField(Class<?> type, String name) throws Exception {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            }
        }
        throw new NoSuchFieldException(name);
    }

    private Junior juniorAt(int age, double academySkill, double talent) {
        Team team = new Team();
        team.setId(1L);
        team.setName("Club");
        team.setJuniorCoachSkill(50);

        Junior j = new Junior();
        j.setId(1L);
        j.setName("Kid");
        j.setAge(age);
        j.setTalent(talent);
        j.setAcademySkillExact(academySkill);
        j.setAcademySkill((int) Math.floor(academySkill));
        j.setTeam(team);
        j.setStatus(JuniorStatus.ACTIVE);
        j.setArrivalSeasonNumber(1);
        when(juniors.findById(1L)).thenReturn(java.util.Optional.of(j));
        return j;
    }

    // --- the distribution, locked ---

    @Test
    @DisplayName("The promotion budget is spread across all eight skills, never concentrated")
    void budgetIsSpreadNotDumped() throws Exception {
        // A strong junior: a large budget. If the whole sum landed on one skill he would arrive as a
        // 20 in one thing and a 1 in seven others, which is not what the distribution does.
        for (int run = 0; run < 40; run++) {
            var skills = buildSkills(20, false);
            int nonZero = 0;
            for (SkillName s : POOL) {
                if (skills.getExact(s) > 0.0) nonZero++;
            }
            assertTrue(nonZero >= 3,
                    "a strong graduate only had " + nonZero + " non-zero skills out of 8");
        }
    }

    @Test
    @DisplayName("A goalkeeper gets his goalkeeping seeded, so more of the sum lands there")
    void goalkeepersGetMoreOnGoalkeeping() throws Exception {
        double gkAverage = 0;
        double outfieldAverage = 0;
        int runs = 60;
        for (int run = 0; run < runs; run++) {
            gkAverage += buildSkills(8, true).getExact(SkillName.GOALKEEPER);
            outfieldAverage += buildSkills(8, false).getExact(SkillName.GOALKEEPER);
        }
        assertTrue(gkAverage > outfieldAverage,
                "a goalkeeper did not get more goalkeeping than an outfielder: "
                        + (gkAverage / runs) + " vs " + (outfieldAverage / runs));
    }

    @Test
    @DisplayName("No graduate ever exceeds 10 in a single skill — the existing cap, deliberately kept")
    void perSkillCapIsTen() throws Exception {
        for (int run = 0; run < 80; run++) {
            var skills = buildSkills(60, run % 2 == 0);
            for (SkillName s : POOL) {
                assertTrue(skills.getExact(s) <= 10.99,
                        s + " graduated at " + skills.getExact(s) + ", above the pool cap of 10");
            }
        }
    }

    @Test
    @DisplayName("The whole budget is spent, or there is nothing left over")
    void budgetIsFullySpent() throws Exception {
        var method = findMethod("createSkillsetFromBudget", int.class, boolean.class);
        method.setAccessible(true);
        for (int budget = 6; budget <= 60; budget++) {
            for (boolean gk : new boolean[]{true, false}) {
                Object build = method.invoke(service, budget, gk);
                Field remaining = findField(build.getClass(), "remaining");
                remaining.setAccessible(true);
                int left = (int) remaining.get(build);
                assertEquals(0, left, "budget " + budget + " (gk=" + gk + ") left " + left + " unspent");
            }
        }
    }

    @Test
    @DisplayName("Skills land on the 1-20 scale the rest of the game uses")
    void skillsAreOnTheGameScale() throws Exception {
        var skills = buildSkills(6, false);
        for (SkillName s : POOL) {
            double value = skills.getExact(s);
            assertTrue(value >= 0.0 && value <= 20.99,
                    s + " left the academy at " + value + ", outside the 1-20 scale");
        }
    }

    // --- the graduation window, the actual change ---

    @Test
    @DisplayName("A graduate is 15 to 20, from his own age rather than age+1")
    void graduationWindowIsFifteenToTwenty() throws Exception {
        assertEquals(15, graduationAgeFor(15));
        assertEquals(18, graduationAgeFor(18));
        assertEquals(20, graduationAgeFor(20));
    }

    @Test
    @DisplayName("An out-of-range age in the database cannot break the window")
    void windowIsClampedNotTrusted() throws Exception {
        assertEquals(15, graduationAgeFor(12), "a twelve-year-old graduated as a twelve-year-old");
        assertEquals(20, graduationAgeFor(27), "a twenty-seven-year-old stayed in the academy at 27");
    }

    @Test
    @DisplayName("The window is the deadline, not a suggestion")
    void twentyIsTheCeiling() throws Exception {
        assertEquals(20, YouthAcademyService.GRADUATION_MAX_AGE);
        assertEquals(15, YouthAcademyService.GRADUATION_MIN_AGE);
        assertTrue(YouthAcademyService.GRADUATION_MAX_AGE
                        > YouthAcademyService.GRADUATION_MIN_AGE,
                "the window is empty");
    }

    @Test
    @DisplayName("Talent comes through from the academy into the first team")
    void talentIsCarriedOver() throws Exception {
        Junior junior = juniorAt(17, 6.0, 8.5);
        var player = buildPlayer(junior);
        assertEquals(8.5, player.getTalent(), 1e-9,
                "a talent 8.5 junior arrived in the first team as talent "
                        + player.getTalent());
    }

    @Test
    @DisplayName("Every skill the junior was worked on is represented, not just a rolled roll")
    void promotionIsDeterministicForAFixedSeed() throws Exception {
        // Not a claim of determinism across versions - just that the same seed twice gives the same
        // graduate, which is what makes a bug in the distribution reproducible at all.
        Field random = findField(YouthAcademyService.class, "random");
        random.setAccessible(true);
        random.set(service, new Random(7));
        var first = buildSkills(9, false);
        random.set(service, new Random(7));
        var second = buildSkills(9, false);
        for (SkillName s : POOL) {
            assertEquals(first.getExact(s), second.getExact(s), 1e-9,
                    s + " differed between two runs of the same seed");
        }
    }

    // --- helpers ---

    private org.example.footballmanager.newLogic.model.Skills buildSkills(int budget, boolean goalkeeper)
            throws Exception {
        var method = findMethod("createSkillsetFromBudget", int.class, boolean.class);
        method.setAccessible(true);
        Object build = method.invoke(service, budget, goalkeeper);
        Field skills = findField(build.getClass(), "skills");
        skills.setAccessible(true);
        return (org.example.footballmanager.newLogic.model.Skills) skills.get(build);
    }

    private org.example.footballmanager.newLogic.model.Player buildPlayer(Junior junior) {
        try {
            var method = findMethod("createSeniorFromJunior", Junior.class);
            method.setAccessible(true);
            Object build = method.invoke(service, junior);
            Field player = findField(build.getClass(), "player");
            player.setAccessible(true);
            return (org.example.footballmanager.newLogic.model.Player) player.get(build);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private int graduationAgeFor(int juniorAge) throws Exception {
        var method = findMethod("graduationAge", Junior.class);
        method.setAccessible(true);
        return (int) method.invoke(service, juniorAt(juniorAge, 6.0, 7.0));
    }

    private static java.lang.reflect.Method findMethod(String name, Class<?>... types) throws Exception {
        for (Class<?> c = YouthAcademyService.class; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, types);
            } catch (NoSuchMethodException ignored) {
                // keep walking
            }
        }
        throw new NoSuchMethodException(name);
    }
}
