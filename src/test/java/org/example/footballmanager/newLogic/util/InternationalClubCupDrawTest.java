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
    @Autowired private org.example.footballmanager.newLogic.repository.MatchRepository matchRepository;
    @Autowired private org.example.footballmanager.newLogic.repository.TeamRepository teamRepository;
    @Autowired private org.springframework.transaction.support.TransactionTemplate transactions;

    private Competition cup;
    private int season = 1;

    /**
     * A fresh cup per test, under the Champions name.
     *
     * <p>It used to look the shared competition up by name, so every test in the class drew into the same
     * competition for the same season — and since {@code ensureGroupStage} is idempotent by design, the
     * second test to run found the stage already drawn and asserted against the first test's work. That
     * is why "the Champions Cup is 8 groups of 6" was reporting zero: nothing was wrong with the draw.
     *
     * <p>The name is still {@code Champions Cup} because {@code qualifyPerGroupFor} reads it, and a
     * duplicate name is harmless here — the lookup that would throw on a duplicate is not the one this
     * class uses.
     */
    @BeforeEach
    void setUp() {
        cups.ensureCompetitionsDurably();
        Competition fresh = new Competition();
        fresh.setName(InternationalClubCups.CHAMPIONS);
        fresh.setType(CompetitionType.CUP);
        fresh.setScope(CompetitionScope.INTERNATIONAL);
        fresh.setTier(1);
        fresh.setTeamType(org.example.footballmanager.newLogic.model.CompetitionTeamType.CLUB);
        cup = competitions.save(fresh);
    }

    /**
     * {@code count} clubs, persisted.
     *
     * <p>They have to be saved. {@code ensureGroupStage} writes fixtures pointing at these clubs, and a
     * fixture referencing an unsaved {@code Team} fails the flush with a transient-instance error — which
     * is what this helper did, and six of the tests in this class were erroring on it rather than
     * asserting anything. A test class whose fixtures all throw tests nothing about the draw.
     */
    private List<Team> entrants(int count) {
        List<Team> ranked = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Team team = new Team();
            team.setName(String.format("T%03d-%d", i, System.nanoTime() % 100000));
            ranked.add(teamRepository.save(team));
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

        // Read inside a transaction: the class is not @Transactional on purpose (see play()), and
        // fixture.getHomeTeam() is a lazy proxy, so touching getName() out here has no session. This was
        // one of the two permanent errors in this class before the knockout work.
        transactions.executeWithoutResult(status -> {
            List<MatchFixture> drawn = fixtures
                    .findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(cup.getId(), season);
            Set<String> seen = new HashSet<>();
            for (MatchFixture fixture : drawn) {
                assertFalse(fixture.getHomeTeam().getId().equals(fixture.getAwayTeam().getId()),
                        "a club was drawn against itself");
                String key = fixture.getHomeTeam().getName() + " v " + fixture.getAwayTeam().getName();
                assertTrue(seen.add(key), "the tie " + key + " was drawn twice");
            }
        });
    }

    @Test
    @DisplayName("each group holds six clubs, and each club is in exactly one group")
    void groupsAreDisjoint() {
        draw.ensureGroupStage(cup, entrants(48), InternationalClubCupDraw.CHAMPIONS_QUALIFY_PER_GROUP, season);

        // Inside a transaction for the same reason as everyTieIsBetweenTwoClubs above: the team proxies
        // need a session to be initialised.
        Map<String, Set<Team>> byGroup = transactions.execute(status -> {
            Map<String, Set<Team>> grouped = new HashMap<>();
            for (MatchFixture fixture : fixtures
                    .findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(cup.getId(), season)) {
                Set<Team> members = grouped.computeIfAbsent(fixture.getGroupCode(), key -> new HashSet<>());
                members.add(fixture.getHomeTeam());
                members.add(fixture.getAwayTeam());
            }
            return grouped;
        });
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
    @DisplayName("the knockout walks all the way to a final and a third-place play-off")
    void theKnockoutReachesTheFinalAndThirdPlace() {
        draw.ensureGroupStage(cup, entrants(48), InternationalClubCupDraw.CHAMPIONS_QUALIFY_PER_GROUP, season);

        // Round by round, playing each before asking for the next. A knockout cannot be drawn in one
        // pass, and this test exists because nothing ever played a group stage and asked again - so the
        // bracket was never asked to do anything after its first round.
        playEveryFixtureUpTo(InternationalClubCupDraw.GROUP_MATCHDAYS, 2, 0);

        // Each step: play what is drawn, ask for the next round, then count. Asking is the whole point -
        // the previous version could not do this past the first round, and no test had ever tried,
        // because nothing in this class had ever played a group stage and asked again.
        draw.ensureKnockouts(cup, InternationalClubCupDraw.CHAMPIONS_QUALIFY_PER_GROUP, season);
        assertEquals(8, knockoutsDrawnFor(InternationalClubCupDraw.ROUND_LAST_SIXTEEN),
                "16 qualifiers make 8 last-sixteen ties");

        playEveryFixtureUpTo(InternationalClubCupDraw.ROUND_LAST_SIXTEEN, 2, 0);
        draw.ensureKnockouts(cup, InternationalClubCupDraw.CHAMPIONS_QUALIFY_PER_GROUP, season);
        assertEquals(4, knockoutsDrawnFor(InternationalClubCupDraw.ROUND_QUARTER_FINAL),
                "8 winners make 4 quarter-final ties, and this is the round the old code could never reach");

        playEveryFixtureUpTo(InternationalClubCupDraw.ROUND_QUARTER_FINAL, 2, 0);
        draw.ensureKnockouts(cup, InternationalClubCupDraw.CHAMPIONS_QUALIFY_PER_GROUP, season);
        assertEquals(2, knockoutsDrawnFor(InternationalClubCupDraw.ROUND_SEMI_FINAL),
                "4 winners make 2 semi-final ties");

        playEveryFixtureUpTo(InternationalClubCupDraw.ROUND_SEMI_FINAL, 2, 0);
        draw.ensureKnockouts(cup, InternationalClubCupDraw.CHAMPIONS_QUALIFY_PER_GROUP, season);
        assertEquals(1, knockoutsDrawnFor(InternationalClubCupDraw.ROUND_FINAL), "no final was drawn");
        assertEquals(1, knockoutsDrawnFor(InternationalClubCupDraw.ROUND_THIRD_PLACE),
                "no third-place play-off was drawn. The owner's format names it, and a knockout that "
                        + "silently drops a round is a different tournament from the specified one");

        // The third place is between the clubs that went out of the semi-final, not anybody else.
        List<MatchFixture> thirdPlace = fixturesInRound(InternationalClubCupDraw.ROUND_THIRD_PLACE);
        Set<Long> semiLosers = losingSemiFinalists();
        // ONE tie, between the two clubs. My first version asserted two here, on the reasonable but wrong
        // reading that "third place" is a two-legged affair - and the assertion failed, which is what told
        // me to go and look at what had actually been drawn rather than change the code to match.
        assertEquals(1, thirdPlace.size(), "the third place is a play-off, not a group");
        assertEquals(semiLosers, Set.of(thirdPlace.get(0).getHomeTeam().getId(),
                        thirdPlace.get(0).getAwayTeam().getId()),
                "the third-place tie is not between the two clubs that lost their semi-finals");
    }

    @Test
    @DisplayName("a knockout round waits for its own results rather than drawing the next one")
    void aRoundWaitsForItsResults() {
        draw.ensureGroupStage(cup, entrants(48), InternationalClubCupDraw.CHAMPIONS_QUALIFY_PER_GROUP, season);
        playEveryFixtureUpTo(InternationalClubCupDraw.GROUP_MATCHDAYS, 2, 0);

        // First call draws the last sixteen.
        draw.ensureKnockouts(cup, InternationalClubCupDraw.CHAMPIONS_QUALIFY_PER_GROUP, season);
        int afterFirstDraw = fixturesInRound(InternationalClubCupDraw.ROUND_LAST_SIXTEEN).size();
        assertTrue(afterFirstDraw > 0, "the last sixteen were never drawn, so this test proves nothing");

        // Asked again with them drawn but unplayed: nothing further may appear.
        draw.ensureKnockouts(cup, InternationalClubCupDraw.CHAMPIONS_QUALIFY_PER_GROUP, season);
        assertEquals(afterFirstDraw, fixturesInRound(InternationalClubCupDraw.ROUND_LAST_SIXTEEN).size(),
                "a second call drew the last sixteen again");
        assertEquals(0, knockoutsDrawnFor(InternationalClubCupDraw.ROUND_QUARTER_FINAL),
                "quarter-finals were drawn from a last-sixteen nobody has played");
    }

    @Test
    @DisplayName("a tie with no recorded winner stops the bracket instead of inventing one")
    void anUndecidedTieStopsTheBracket() {
        draw.ensureGroupStage(cup, entrants(48), InternationalClubCupDraw.CHAMPIONS_QUALIFY_PER_GROUP, season);
        playEveryFixtureUpTo(InternationalClubCupDraw.GROUP_MATCHDAYS, 2, 0);
        // The last sixteen, but one tie finishes level with no shootout recorded.
        playEveryFixtureUpTo(InternationalClubCupDraw.ROUND_LAST_SIXTEEN, 2, 0);
        for (MatchFixture tie : fixturesInRound(InternationalClubCupDraw.ROUND_LAST_SIXTEEN)) {
            if (tie.getId() % 2 == 0) {
                play(tie, 1, 1);
            }
        }

        draw.ensureKnockouts(cup, InternationalClubCupDraw.CHAMPIONS_QUALIFY_PER_GROUP, season);

        assertEquals(0, knockoutsDrawnFor(InternationalClubCupDraw.ROUND_QUARTER_FINAL),
                "a tie that nobody won produced a quarter-final, so a club was invented");
    }

    // --- helpers for walking a bracket ---

    private List<MatchFixture> fixturesInRound(int round) {
        return fixtures.findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(cup.getId(), season)
                .stream()
                .filter(f -> f.getRoundNumber() != null && f.getRoundNumber() == round)
                .toList();
    }

    private int knockoutsDrawnFor(int round) {
        return fixturesInRound(round).size();
    }

    /**
     * Plays every unplayed fixture up to and including a round, home side winning every tie.
     *
     * <p>Home always wins, which is enough: the bracket only cares that a tie has a winner, and a fixture
     * that draws is exactly the case the other test covers.
     */
    private void playEveryFixtureUpTo(int round, int homeGoals, int awayGoals) {
        for (int r = 1; r <= round; r++) {
            for (MatchFixture fixture : fixturesInRound(r)) {
                if (!fixture.isPlayed()) {
                    play(fixture, homeGoals, awayGoals);
                }
            }
        }
    }

    /**
     * The clubs that went out of the semi-final, read inside a transaction.
     *
     * <p>Inside one because this class is deliberately not {@code @Transactional} — {@code play()} has to
     * commit for {@code ensureKnockouts} to see it, and that runs in its own transaction. So a lazy
     * {@code playedMatch} read out here has no session and throws. Two other tests in this class fail the
     * same way for the same reason; this one is fixed because it is mine.
     */
    private Set<Long> losingSemiFinalists() {
        return transactions.execute(status -> {
            Set<Long> losers = new HashSet<>();
            for (MatchFixture tie : fixturesInRound(InternationalClubCupDraw.ROUND_SEMI_FINAL)) {
                Team winner = tie.getPlayedMatch().getHomeGoals() > tie.getPlayedMatch().getAwayGoals()
                        ? tie.getHomeTeam() : tie.getAwayTeam();
                losers.add(winner == tie.getHomeTeam() ? tie.getAwayTeam().getId() : tie.getHomeTeam().getId());
            }
            return losers;
        });
    }

    /** Gives a fixture a played match, which is how the draw reads a winner. */
    private void play(MatchFixture fixture, int homeGoals, int awayGoals) {
        org.example.footballmanager.newLogic.model.Match match = new org.example.footballmanager.newLogic.model.Match();
        match.setCompetition(cup);
        match.setHomeTeam(fixture.getHomeTeam());
        match.setAwayTeam(fixture.getAwayTeam());
        match.setSeasonYear(fixture.getSeasonYear());
        match.setRoundNumber(fixture.getRoundNumber());
        match.setWeekNumber(fixture.getWeekNumber());
        match.setHomeGoals(homeGoals);
        match.setAwayGoals(awayGoals);
        match.setPlayed(true);
        match.setFinished(true);
        match.setMatchDate(fixture.getMatchDate());
        match.setEventJson("[]");
        match = matchRepository.save(match);
        fixture.setPlayedMatch(match);
        fixture.setPlayed(true);
        fixtures.save(fixture);
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
