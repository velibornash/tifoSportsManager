package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.Team;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 4.6 — mentoring, familiarity, cohesion.
 *
 * <p>The mentor pairing is the part with real failure modes, so the tests concentrate there: a
 * blanket bonus that every junior gets would pass a naive "mentoring works" test while being a worse
 * feature, and pairing a centre-half to a winger would be a friendship rather than a mentorship.
 */
class SquadEnvironmentTest {

    private static long nextId = 1;

    private static Player player(Position position, int age, double talent) {
        Player p = new Player();
        p.setId(nextId++);
        p.setName("P" + p.getId());
        p.setPosition(position);
        p.setAge(age);
        p.setTalent(talent);
        p.setFamiliarity(100.0);
        return p;
    }

    private static Team team(double cohesion) {
        Team t = new Team();
        t.setId(1L);
        t.setName("Club");
        t.setCohesion(cohesion);
        return t;
    }

    @Test
    @DisplayName("A junior is paired with a senior at his own position")
    void pairsAtTheSamePosition() {
        Player junior = player(Position.ATT, 19, 8.0);
        Player mentor = player(Position.ATT, 33, 9.0);
        Map<Long, Long> pairs = SquadEnvironment.mentorPairs(List.of(junior, mentor));

        assertEquals(1, pairs.size());
        assertEquals(junior.getId(), pairs.get(mentor.getId()));
    }

    @Test
    @DisplayName("A senior at a different position is a friend, not a mentor")
    void willNotMentorAcrossPositions() {
        Player junior = player(Position.WNG, 19, 8.0);
        Player centreBack = player(Position.DEF, 33, 9.0);
        Player winger = player(Position.WNG, 30, 7.0);

        Map<Long, Long> pairs = SquadEnvironment.mentorPairs(List.of(junior, centreBack, winger));
        assertEquals(1, pairs.size(), "a centre-half was made to mentor a winger");
        assertEquals(junior.getId(), pairs.get(winger.getId()));
    }

    @Test
    @DisplayName("One senior teaches one junior — a blanket bonus would not be a mentorship")
    void oneMentorOneJunior() {
        List<Player> squad = new ArrayList<>();
        Player senior = player(Position.MID, 34, 9.0);
        squad.add(senior);
        for (int i = 0; i < 4; i++) {
            squad.add(player(Position.MID, 19, 8.0 - i * 0.5));
        }
        Map<Long, Long> pairs = SquadEnvironment.mentorPairs(squad);

        assertEquals(1, pairs.size(), "one senior was given the whole junior intake");
        assertEquals(1, pairs.values().size());
    }

    @Test
    @DisplayName("A senior is not eligible until he is old enough and far enough away in age")
    void mentorshipHasAgeRules() {
        Player junior = player(Position.MID, 19, 8.0);
        assertFalse(SquadEnvironment.canMentor(player(Position.MID, 24, 9.5), junior),
                "a 24-year-old mentored a 19-year-old");
        assertFalse(SquadEnvironment.canMentor(player(Position.MID, 30, 9.5), player(Position.MID, 28, 8.0)),
                "a 30-year-old mentored a 28-year-old — not a gap, just a preference");
        assertTrue(SquadEnvironment.canMentor(player(Position.MID, 31, 9.5), junior));
    }

    @Test
    @DisplayName("A player cannot mentor himself")
    void noSelfMentoring() {
        Player p = player(Position.MID, 31, 9.0);
        assertFalse(SquadEnvironment.canMentor(p, p));
        assertTrue(SquadEnvironment.mentorPairs(List.of(p)).isEmpty(),
                "a lone veteran was paired with himself");
    }

    @Test
    @DisplayName("Nobody eligible means nobody mentored, rather than an exception")
    void noEligiblePairIsFine() {
        assertTrue(SquadEnvironment.mentorPairs(List.of()).isEmpty());
        assertTrue(SquadEnvironment.mentorPairs(null).isEmpty());
        assertTrue(SquadEnvironment.mentorPairs(List.of(player(Position.ATT, 19, 8.0))).isEmpty(),
                "a junior with no senior in the squad was still given a mentor");
    }

    @Test
    @DisplayName("The best junior gets the best senior, because the point is worth listening to")
    void pairsByTalent() {
        Player bestJunior = player(Position.ATT, 18, 9.5);
        Player averageJunior = player(Position.ATT, 20, 5.0);
        Player goodSenior = player(Position.ATT, 32, 9.0);
        Player averageSenior = player(Position.ATT, 33, 5.0);

        Map<Long, Long> pairs = SquadEnvironment.mentorPairs(
                List.of(bestJunior, averageJunior, goodSenior, averageSenior));
        assertEquals(bestJunior.getId(), pairs.get(goodSenior.getId()));
    }

    @Test
    @DisplayName("A new signing is held back until he knows the system, and catches up")
    void familiarityGatesANewSigning() {
        Player signing = player(Position.ATT, 22, 8.0);
        signing.setFamiliarity((double) SquadEnvironment.NEW_SIGNING_FAMILIARITY);

        double held = SquadEnvironment.familiarityFactor(signing);
        assertTrue(held < 1.0, "a player who had never played for this club was not held back");
        assertTrue(held >= 1.0 - SquadEnvironment.FAMILIARITY_PENALTY);

        for (int week = 0; week < 20; week++) {
            int familiarity = SquadEnvironment.familiarityAfterWeek(
                    (int) SquadEnvironment.familiarityOf(signing), 90);
            signing.setFamiliarity((double) familiarity);
        }
        assertEquals(1.0, SquadEnvironment.familiarityFactor(signing), 1e-9,
                "after twenty full matches he was still not trusted to learn");
    }

    @Test
    @DisplayName("Familiarity is use-it-or-lose-it: no football, and he forgets the system")
    void familiarityDecaysWhenUnused() {
        int afterIdle = SquadEnvironment.familiarityAfterWeek(100, 0);
        assertTrue(afterIdle < 100, "a squad player who never played stayed fully familiar");
        assertTrue(afterIdle > 0);
    }

    @Test
    @DisplayName("A substitute's week counts for something, but not a full match")
    void partWeekCountsPartly() {
        int full = SquadEnvironment.familiarityAfterWeek(50, 90);
        int part = SquadEnvironment.familiarityAfterWeek(50, 20);
        assertTrue(part > 50 && part < full,
                "twenty minutes was treated the same as a full match, or as nothing at all");
    }

    @Test
    @DisplayName("Familiarity stays inside 0-100 however long a player is idle")
    void familiarityIsBounded() {
        int faded = SquadEnvironment.familiarityAfterWeek(0, 0);
        assertTrue(faded >= 0);
        for (int i = 0; i < 200; i++) {
            faded = SquadEnvironment.familiarityAfterWeek(faded, 90);
        }
        assertEquals(SquadEnvironment.FULL_FAMILIARITY, faded);
    }

    @Test
    @DisplayName("A settled club is worth something and a broken one is worth nothing")
    void cohesionHasDirection() {
        double settled = SquadEnvironment.cohesionGrowthFactor(team(100.0));
        double average = SquadEnvironment.cohesionGrowthFactor(team(50.0));
        double broken = SquadEnvironment.cohesionGrowthFactor(team(0.0));

        assertTrue(settled > average, "a settled dressing room was worth the same as an average one");
        assertTrue(average > broken, "an unsettled dressing room was not a penalty at all");
        assertEquals(1.0 + SquadEnvironment.COHESION_GROWTH_MAX, settled, 1e-9);
        assertEquals(1.0, broken, 1e-9);
    }

    @Test
    @DisplayName("Cohesion never makes a team worse than neutral")
    public void cohesionIsNeverNegative() {
        assertEquals(1.0, SquadEnvironment.cohesionGrowthFactor(team(0.0)), 1e-9);
        assertEquals(1.0, SquadEnvironment.cohesionMatchFactor(team(0.0)), 1e-9);
    }

    @Test
    @DisplayName("A squad that keeps turning over never settles, however long it exists")
    void turnoverPreventsCohesion() {
        int cohesion = 80;
        for (int week = 0; week < 12; week++) {
            cohesion = SquadEnvironment.cohesionAfterWeek(cohesion, 3);
        }
        assertTrue(cohesion < 40,
                "a club that signed three players a week every week settled down to " + cohesion);

        // +3 a week from 50, so it takes eighteen weeks to settle. The claim is that a stable squad
        // does settle, not that it does so in any particular number of weeks.
        int stable = 50;
        for (int week = 0; week < 18; week++) {
            stable = SquadEnvironment.cohesionAfterWeek(stable, 0);
        }
        assertEquals(100, stable, "a stable squad never settled");
        for (int week = 0; week < 10; week++) {
            stable = SquadEnvironment.cohesionAfterWeek(stable, 0);
        }
        assertEquals(100, stable, "a settled squad un-settled itself");
    }

    @Test
    @DisplayName("Cohesion is seasoning, not a second growth system")
    public void cohesionIsBoundedSmall() {
        assertTrue(SquadEnvironment.COHESION_GROWTH_MAX <= 0.05,
                "cohesion is worth more than a coach's week, which would rebalance growth");
        assertTrue(SquadEnvironment.COHESION_MATCH_MAX <= 0.05,
                "cohesion is worth more on the pitch than a head coach, which would rebalance the sim");
        assertTrue(SquadEnvironment.MENTOR_GROWTH_BONUS <= 0.10,
                "a mentor is worth more than a full week of a good coach's work");
    }

    @Test
    @DisplayName("An unrecorded squad is neither a good nor a bad one")
    void missingStateIsNeutral() {
        Team unset = new Team();
        unset.setId(1L);
        double growth = SquadEnvironment.cohesionGrowthFactor(unset);
        assertTrue(growth > 1.0 && growth < 1.0 + SquadEnvironment.COHESION_GROWTH_MAX,
                "a club with no recorded cohesion got the maximum or the minimum: " + growth);
        assertEquals(1.0, SquadEnvironment.familiarityFactor(null), 1e-9);
        assertEquals(1.0, SquadEnvironment.cohesionGrowthFactor(null), 1e-9);
    }

    @Test
    @DisplayName("The mentored juniors can be listed for the training screen")
    void mentoredJuniorsAreReportable() {
        Player junior = player(Position.DEF, 20, 8.0);
        Player mentor = player(Position.DEF, 34, 9.0);
        Player loner = player(Position.ATT, 20, 8.0);

        List<Player> mentored = SquadEnvironment.mentoredJuniors(List.of(junior, mentor, loner));
        assertEquals(1, mentored.size());
        assertEquals(junior.getId(), mentored.get(0).getId());
        assertNotNull(SquadEnvironment.mentorOf(Map.of(junior.getId(), mentor.getId()), junior.getId()));
        assertNull(SquadEnvironment.mentorOf(Map.of(), loner.getId()));
    }
}
