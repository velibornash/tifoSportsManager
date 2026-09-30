package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The international club cup format (owner, 2026-09-30).
 *
 * <p>Specified, not designed: Champions takes one club per league into <b>8 groups of 6</b>, single
 * round robin over 5 matchdays, top two through; Masters takes every second and every third into
 * <b>16 groups of 6</b> with <b>only the winner</b> through; Challenge takes the fourth-placed clubs and
 * is the same as Champions. Week 6 is a national-team pause. After that they are identical: 1/8, 1/4,
 * 1/2, a final and a third-place play-off.
 */
class InternationalClubCupDrawTest extends BaseTest {

    @Autowired private InternationalClubCupDraw draw;
    @Autowired private InternationalClubCups cups;
    @Autowired private CupFixtureSeeder nationalSeeder;
    @Autowired private CompetitionRepository competitions;
    @Autowired private MatchFixtureRepository fixtures;

    private Competition cup;
    private int season = 1;

    @BeforeEach
    void setUp() {
        cups.ensureCompetitionsDurably();
        cup = competitions.findAll().stream()
                .filter(c -> InternationalClubCups.CHAMPIONS.equals(c.getName()))
                .findFirst()
                .orElseThrow();
    }

    private List<Team> entrants(int count) {
        List<Team> ranked = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Team team = new Team();
            team.setName(String.format("T%03d", i));
            ranked.add(team);
        }
        return ranked;
    }

    @Test
    @DisplayName("48 clubs make 8 groups of 6, and 5 matchdays of 3 ties each")
    void championsGroupStage() {
        InternationalClubCupDraw.DrawResult result = draw.ensureGroupStage(
                cup, entrants(48), InternationalClubCupDraw.CHAMPIONS_QUALIFY_PER_GROUP, season);

        assertEquals(8, result.groups(), "the Champions Cup is 8 groups of 6");
        assertEquals(48, result.clubs());
        // 8 groups x 5 matchdays x 3 ties.
        assertEquals(120, result.groupFixtures(), "a single round robin of 6 is 5 ties per group");

        List<MatchFixture> drawn = fixtures
                .findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(cup.getId(), season);
        Set<Integer> weeks = new java.util.TreeSet<>();
        for (MatchFixture fixture : drawn) {
            weeks.add(fixture.getWeekNumber());
        }
        assertEquals(Set.of(1, 2, 3, 4, 5), weeks,
                "the five group matchdays belong in weeks 1-5, and week 6 is the national-team pause");
    }

    @Test
    @DisplayName("no club plays itself, and no tie is played twice")
    void everyTieIsBetweenTwoClubs() {
        draw.ensureGroupStage(cup, entrants(48), InternationalClubCupDraw.CHAMPIONS_QUALIFY_PER_GROUP, season);

        List<MatchFixture> drawn = fixtures
                .findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(cup.getId(), season);
        Set<String> seen = new HashSet<>();
        for (MatchFixture fixture : drawn) {
            assertFalse(fixture.getHomeTeam().getId().equals(fixture.getAwayTeam().getId()),
                    "a club was drawn against itself");
            String key = fixture.getHomeTeam().getName() + " v " + fixture.getAwayTeam().getName();
            assertTrue(seen.add(key), "the tie " + key + " was drawn twice");
        }
    }

    @Test
    @DisplayName("each group holds six clubs, and each club is in exactly one group")
    void groupsAreDisjoint() {
        draw.ensureGroupStage(cup, entrants(48), InternationalClubCupDraw.CHAMPIONS_QUALIFY_PER_GROUP, season);

        Map<String, Set<Team>> byGroup = new HashMap<>();
        for (MatchFixture fixture : fixtures
                .findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(cup.getId(), season)) {
            Set<Team> members = byGroup.computeIfAbsent(fixture.getGroupCode(), key -> new HashSet<>());
            members.add(fixture.getHomeTeam());
            members.add(fixture.getAwayTeam());
        }
        assertEquals(8, byGroup.size());
        for (Map.Entry<String, Set<Team>> group : byGroup.entrySet()) {
            assertEquals(6, group.getValue().size(), group.getKey() + " has " + group.getValue().size());
        }

        Set<Team> all = new HashSet<>();
        for (Set<Team> members : byGroup.values()) {
            for (Team team : members) {
                assertTrue(all.add(team), team.getName() + " is in two groups");
            }
        }
        assertEquals(48, all.size());
    }

    @Test
    @DisplayName("the groups are spread, not bunched — the eight best are in eight different groups")
    void theSerpentineSpreadsTheStrong() {
        // Not the owner's rule; it is the one choice here that was not specified. Dealing the ranked
        // clubs into groups in order would put all eight strongest clubs in one group and settle the
        // Champions Cup in the group stage.
        List<List<Team>> groups = draw.dealIntoGroups(entrants(48));

        assertEquals(8, groups.size());
        Set<Team> firstOfEach = new HashSet<>();
        for (List<Team> group : groups) {
            assertEquals(6, group.size());
            firstOfEach.add(group.get(0));
        }
        assertEquals(8, firstOfEach.size(),
                "two groups share their strongest club, so the seeding is bunching rather than spreading");
    }

    @Test
    @DisplayName("top two from a Champions group, only the winner from a Masters group")
    void whoQualifiesIsTheDifferenceBetweenTheCups() {
        assertEquals(2, InternationalClubCupDraw.CHAMPIONS_QUALIFY_PER_GROUP);
        assertEquals(1, InternationalClubCupDraw.MASTERS_QUALIFY_PER_GROUP,
                "only the group winner goes through from a Masters group");
        assertEquals(2, InternationalClubCupDraw.CHALLENGE_QUALIFY_PER_GROUP);

        assertEquals(2, InternationalClubCupDraw.qualifyPerGroupFor(InternationalClubCups.CHAMPIONS));
        assertEquals(1, InternationalClubCupDraw.qualifyPerGroupFor(InternationalClubCups.MASTERS));
        assertEquals(2, InternationalClubCupDraw.qualifyPerGroupFor(InternationalClubCups.CHALLENGE));
    }

    @Test
    @DisplayName("96 clubs make 16 groups, which is the Masters Cup")
    void mastersGroupStage() {
        Competition masters = competitions.findAll().stream()
                .filter(c -> InternationalClubCups.MASTERS.equals(c.getName()))
                .findFirst()
                .orElseThrow();
        InternationalClubCupDraw.DrawResult result = draw.ensureGroupStage(
                masters, entrants(96), InternationalClubCupDraw.MASTERS_QUALIFY_PER_GROUP, 2);

        assertEquals(16, result.groups(), "every second and every third place means 16 groups of 6");
        assertEquals(16 * 5 * 3, result.groupFixtures());
    }

    @Test
    @DisplayName("the knockouts wait for the group stage to be played")
    void knockoutsWaitForResults() {
        draw.ensureGroupStage(cup, entrants(48), InternationalClubCupDraw.CHAMPIONS_QUALIFY_PER_GROUP, season);
        long before = fixtures
                .findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(cup.getId(), season).size();

        InternationalClubCupDraw.DrawResult result = draw.ensureKnockouts(
                cup, InternationalClubCupDraw.CHAMPIONS_QUALIFY_PER_GROUP, season);

        assertEquals(0, result.knockoutFixtures(),
                "a knockout was drawn from a group stage nobody has played");
        assertEquals(before, fixtures
                .findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(cup.getId(), season).size(),
                "a knockout was drawn from an unplayed group stage");
    }

    @Test
    @DisplayName("drawing the group stage twice does not draw it twice")
    void theDrawIsIdempotent() {
        draw.ensureGroupStage(cup, entrants(48), InternationalClubCupDraw.CHAMPIONS_QUALIFY_PER_GROUP, season);
        long first = fixtures
                .findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(cup.getId(), season).size();
        draw.ensureGroupStage(cup, entrants(48), InternationalClubCupDraw.CHAMPIONS_QUALIFY_PER_GROUP, season);
        long second = fixtures
                .findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(cup.getId(), season).size();

        assertEquals(first, second, "a second run drew a second set of group fixtures");
        assertTrue(first > 0, "nothing was drawn at all, so idempotence proves nothing");
    }

    @Test
    @DisplayName("week 6 is never used by either cup")
    void weekSixIsTheNationalTeamPause() {
        for (int week : InternationalClubCupDraw.CUP_WEEKS) {
            assertNotEqualsSix(week);
        }
        for (int week : CupFixtureSeeder.CUP_WEEKS) {
            assertNotEqualsSix(week);
        }
    }

    private void assertNotEqualsSix(int week) {
        assertTrue(week != 6, "week 6 is the national-team pause and carries no club match");
    }
}
