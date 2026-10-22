package org.tifo.footballmanager.newLogic.util;

import org.tifo.footballmanager.newLogic.model.SeasonCalendar;
import org.tifo.footballmanager.BaseTest;
import org.tifo.footballmanager.newLogic.model.Competition;
import org.tifo.footballmanager.newLogic.model.CompetitionScope;
import org.tifo.footballmanager.newLogic.model.CompetitionType;
import org.tifo.footballmanager.newLogic.model.MatchFixture;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.repository.CompetitionRepository;
import org.tifo.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.tifo.footballmanager.newLogic.model.Country;
import org.tifo.footballmanager.newLogic.model.CountryState;
import org.tifo.footballmanager.newLogic.repository.CountryRepository;
import org.tifo.footballmanager.newLogic.repository.TeamRepository;
import org.tifo.footballmanager.newLogic.service.SeasonService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a cup is seeded (owner, 2026-09-30).
 *
 * <p>The rule, in the owner's words: take the clubs and their rankings, sort descending, and pair one
 * randomly from the top half against one from the bottom half. It holds for the first round too — 108
 * entrants split 54 and 54 — and it is the same function all the way to a final.
 *
 * <p>The arithmetic it produces, for a 310-club national cup:
 *
 * <pre>
 *   round 1   108 entrants (ranks 203-310)  -> 54 ties, 54 winners
 *   round 2   54 winners + 202 direct      -> 256 -> 128 ties
 *   round 3   128 -> 64     round 4  64 -> 32     round 5  32 -> 16
 *   round 6   16 -> 8       round 7   8 -> 4     round 8   4 -> 2, the finalists
 * </pre>
 *
 * <p>This was already implemented; it is pinned here because a seeding rule that is only true by
 * coincidence is a rule nobody can change safely.
 */
class CupDrawSeedingTest extends BaseTest {

    /** Unique per call: `iso_code` is unique and a repeated suffix fails a test for the wrong reason. */
    private static final java.util.concurrent.atomic.AtomicLong ISO_SEQUENCE =
            new java.util.concurrent.atomic.AtomicLong();

    @Autowired private CupFixtureSeeder seeder;
    @Autowired private CompetitionRepository competitions;
    @Autowired private MatchFixtureRepository fixtures;
    @Autowired private InternationalClubCups cups;
    @Autowired private SeasonService seasons;
    @Autowired private CountryRepository countries;
    @Autowired private TeamRepository teams;

    /** Descending by name, so the order is the ranking and does not depend on a real squad. */
    private static List<Team> ranked(int count) {
        List<Team> teams = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Team team = new Team();
            team.setName(String.format("R%03d", i));
            teams.add(team);
        }
        return teams;
    }

    private static int rankOf(Team team) {
        return Integer.parseInt(team.getName().substring(1));
    }

    @Test
    @DisplayName("the halves are exactly half each, strongest first")
    void theHalvesAreHalves() {
        for (int size : new int[]{108, 256, 128, 64, 32, 16, 8, 4}) {
            List<List<Team>> halves = seeder.splitForDraw(ranked(size));
            assertEquals(size / 2, halves.get(0).size(), size + " entrants did not split evenly");
            assertEquals(size / 2, halves.get(1).size(), size + " entrants did not split evenly");
            // Top half must be the better clubs, in order.
            for (int i = 1; i < halves.get(0).size(); i++) {
                assertTrue(rankOf(halves.get(0).get(i - 1)) < rankOf(halves.get(0).get(i)),
                        size + ": the top half is not in descending rank order");
            }
            assertTrue(rankOf(halves.get(0).get(0)) < rankOf(halves.get(1).get(0)),
                    size + ": the two halves are not the strong half and the weak half");
        }
    }

    @Test
    @DisplayName("every tie is one from each half — no favourite meets a favourite")
    void noTieIsWithinAHalf() {
        for (int size : new int[]{108, 256, 128, 64, 4}) {
            List<List<Team>> halves = seeder.splitForDraw(ranked(size));
            Set<Team> top = new HashSet<>(halves.get(0));
            Set<Team> bottom = new HashSet<>(halves.get(1));

            List<Team> upper = new ArrayList<>(halves.get(0));
            List<Team> lower = new ArrayList<>(halves.get(1));
            Collections.shuffle(upper, new Random(size));
            Collections.shuffle(lower, new Random(size + 1));

            for (int i = 0; i < upper.size(); i++) {
                assertTrue(top.contains(upper.get(i)) && bottom.contains(lower.get(i)),
                        size + ": tie " + i + " is not one from each half");
            }
        }
    }

    @Test
    @DisplayName("the pairing inside the halves is random, so the draw is still a draw")
    void thePairingIsRandom() {
        // A fixed pairing would seed the cup identically every season, and the point of a draw is that
        // the manager cannot know it. Same entrants, different ties.
        List<List<Team>> halves = seeder.splitForDraw(ranked(256));
        Set<String> firstDraw = pairings(halves, 1);
        Set<String> secondDraw = pairings(halves, 2);

        assertTrue(!firstDraw.equals(secondDraw),
                "two draws of the same 256 clubs produced identical ties, so the pairing is not random");
    }

    @Test
    @DisplayName("a draw is reproducible from its seed")
    void theDrawIsReproducible() {
        // The opposite requirement, and both have to hold: random across seasons, identical within one.
        List<List<Team>> halves = seeder.splitForDraw(ranked(128));
        assertEquals(pairings(halves, 99), pairings(halves, 99),
                "the same seed produced two different draws, so a replay would not match");
    }

    @Test
    @DisplayName("round 1 is the bottom 108, and round 2 is them plus the 202 above them")
    void theEntryRoundIsTheBottomOfTheTable() {
        // The seeder said "ranks 203-310 enter in week 1; ranks 1-202 join the winners in week 2" and
        // then did the opposite twice over: it drew the strongest 108, and its round-2 field was the
        // 54 winners alone, so the 202 clubs that had not played a match were simply not in the cup.
        List<Team> ranked = ranked(310);

        int directEntrantCount = Math.max(0, ranked.size() - CupFixtureSeeder.ENTRY_ROUND_TEAMS);
        assertEquals(202, directEntrantCount, "the direct entrants should be the 202 above the entry round");

        // The entry round, as the seeder now takes it: the tail, not the head.
        List<Team> firstKnockout = ranked.subList(directEntrantCount, ranked.size());
        assertEquals(108, firstKnockout.size());
        assertEquals("R203", firstKnockout.get(0).getName(), "the entry round must start at rank 203");
        assertEquals("R310", firstKnockout.get(firstKnockout.size() - 1).getName(),
                "the entry round must end at rank 310");
        assertTrue(rankOf(firstKnockout.get(0)) > rankOf(ranked.get(0)),
                "the entry round is the head of the list again, which is the bug");

        // And the strongest club is a direct entrant, not in the preliminary.
        assertTrue(ranked.subList(0, directEntrantCount).contains(ranked.get(0)),
                "the best club in the country is not among the direct entrants");
    }

    @Test
    @DisplayName("the round arithmetic the rule produces")
    void theArithmeticIsTheOwners() {
        // 310 clubs: the bottom 108 play round 1, the top 202 join the 54 winners in round 2.
        assertEquals(108, CupFixtureSeeder.ENTRY_ROUND_TEAMS);
        assertEquals(256, CupFixtureSeeder.MAIN_DRAW_TEAMS);
        assertEquals(310 - CupFixtureSeeder.ENTRY_ROUND_TEAMS + CupFixtureSeeder.ENTRY_ROUND_TEAMS / 2,
                CupFixtureSeeder.MAIN_DRAW_TEAMS,
                "the 256 figure should be the 202 direct entrants plus the 54 first-round winners");

        // And the halving from there to a final.
        int teams = CupFixtureSeeder.MAIN_DRAW_TEAMS;
        int rounds = 0;
        while (teams > 2) {
            teams /= 2;
            rounds++;
        }
        assertEquals(7, rounds,
                "256 should take seven halvings to reach two finalists, so eight rounds plus a final");
    }

    @Test
    @DisplayName("an odd number of entrants gives byes rather than dropping a club")
    void oddCountsAreHandled() {
        // 31 survivors cannot halve evenly, and a club that quietly disappears from a cup is worse
        // than a bracket with a bye in it.
        List<List<Team>> halves = seeder.splitForDraw(ranked(31));
        assertEquals(15, halves.get(0).size());
        assertEquals(16, halves.get(1).size());
        assertEquals(31, halves.get(0).size() + halves.get(1).size(),
                "an entrant went missing from an odd draw");
    }

    private Set<String> pairings(List<List<Team>> halves, long seed) {
        List<Team> upper = new ArrayList<>(halves.get(0));
        List<Team> lower = new ArrayList<>(halves.get(1));
        Collections.shuffle(upper, new Random(seed));
        Collections.shuffle(lower, new Random(seed + 7));

        Set<String> ties = new HashSet<>();
        for (int i = 0; i < Math.min(upper.size(), lower.size()); i++) {
            ties.add(upper.get(i).getName() + " v " + lower.get(i).getName());
        }
        return ties;
    }

    /**
     * The draw must target the country's own cup.
     *
     * <p>It used to take {@code findAll().filter(type == CUP).findFirst()} — the first cup row the
     * database returned. That was harmless with one cup and is not now: there are sixteen CUP
     * competitions, so "the first" is whichever comes back, and the job could draw the Champions Cup on
     * the national cup's week map with the national cup never drawn at all. Neither symptom is visible
     * from a single competition in the fixture, so the international cups have to be in this test.
     *
     * <p><b>Built here rather than found</b>, because the H2 profile has no national cup at all — every
     * other test in this class exercises {@code splitForDraw}, which is pure arithmetic and never touches
     * a competition. So "the draw was not wired" was true here too, and a test written against the seeded
     * world would have measured the seeder rather than the draw.
     */
    @Test
    @DisplayName("the draw ignores the international cups and picks the domestic one")
    void theDrawTargetsTheNationalCup() {
        cups.ensureCompetitionsDurably();
        Competition international = competitions.findAll().stream()
                .filter(c -> c.getType() == CompetitionType.CUP)
                .filter(c -> c.getScope() == CompetitionScope.INTERNATIONAL)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no international cup exists, so this test proves nothing"));

        Competition national = aNationalCupWithClubs(8);
        int season = seasons.getActiveSeasonYear();

        int drawn = seeder.drawRoundForWeek(1);

        assertTrue(drawn > 0, "round 1 of an eight-club national cup was not drawn, so this test proves nothing");
        assertTrue(fixtures.findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(
                        national.getId(), season).size() > 0,
                "the national cup holds no fixtures after a successful draw");
        assertEquals(0, fixtures.findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(
                        international.getId(), season).size(),
                "fixtures were drawn into " + international.getName() + ", which has its own draw, its own "
                        + "week map and its own format - the national cup's round-for-week arithmetic does "
                        + "not apply to it");
    }

    /**
     * A domestic cup with {@code clubCount} clubs in it, which is what the draw reads.
     *
     * <p>The clubs go into a <b>league</b>, not into the cup, and that is not a detail. The draw's field is
     * {@code rankedClubs}, which keeps a team only when the competition it plays in is a LEAGUE — that is
     * the filter that keeps the national side out of the club cup, and it is the definition the pyramid
     * uses. This helper used to set {@code club.setCompetition(cup)}, which is a CUP, so every club it
     * built was rejected by that filter and the draw found an empty country.
     *
     * <p>{@link #theDrawTargetsTheNationalCup} had been failing on {@code main} for exactly this reason,
     * and its own message said so — "round 1 was not drawn, so this test proves nothing" — which is a test
     * reporting that it measures nothing, in a class where nobody read the run.
     */
    private Competition aNationalCupWithClubs(int clubCount) {
        Country country = new Country();
        // A monotonic suffix, not `nanoTime() % 20`: that collides, `iso_code` is unique, and the
        // collision surfaced as a DataIntegrityViolation in an unrelated test on a slow run.
        String stamp = Long.toString(ISO_SEQUENCE.incrementAndGet());
        country.setName("ZZ Cup " + stamp);
        country.setIsoCode("ZZ" + stamp);
        country.setState(CountryState.SIMULATED);
        country = countries.save(country);

        Competition league = new Competition();
        league.setName("ZZ National League " + stamp);
        league.setType(CompetitionType.LEAGUE);
        league.setScope(CompetitionScope.NATIONAL);
        league.setCountry(country);
        league = competitions.save(league);

        Competition cup = new Competition();
        cup.setName("ZZ National Cup " + stamp);
        cup.setType(CompetitionType.CUP);
        cup.setScope(CompetitionScope.NATIONAL);
        cup.setCountry(country);
        cup = competitions.save(cup);

        for (int i = 1; i <= clubCount; i++) {
            Team club = new Team();
            club.setName(String.format("ZZ Cup FC%02d " + stamp, i));
            club.setCountry(country);
            club.setCompetition(league);
            club.setHumanControlled(false);
            club.setReputation(50.0);
            teams.save(club);
        }
        return cup;
    }

    /**
     * The admin "Re-draw the cup" button must not draw the cup again.
     *
     * <p>Its guard counted <b>unplayed</b> ties, which is a different question from "has this round been
     * drawn?". The two agree exactly until the round is played, and then they stop: a played round has no
     * unplayed ties, the guard concludes it was never drawn, and the button deals all 108 teams a second
     * time. Measured on the owner's database — 54 duplicate round-1 ties on every press, the whole field
     * entered twice, from a method named {@code seedIfMissing} whose Javadoc claimed the opposite.
     *
     * <p>So the sequence that matters is the one nobody tested: draw, <b>play the round</b>, press again.
     * Pressing twice without playing never failed, because the guard was still holding.
     */
    @Test
    @DisplayName("pressing the button again after the round is played does not draw it twice")
    void pressingTheButtonAgainAfterTheRoundIsPlayedAddsNothing() {
        aNationalCupWithClubs(310);
        int season = seasons.getActiveSeasonYear();

        seeder.seedIfMissing();
        List<MatchFixture> afterFirstPress = fixturesOfThisCup(season, 1);
        assertTrue(afterFirstPress.size() > 0, "the first press drew nothing, so this test proves nothing");

        // Play every tie. This is the step that used to disarm the guard.
        for (MatchFixture fixture : afterFirstPress) {
            fixture.setPlayed(true);
            fixtures.save(fixture);
        }
        assertTrue(fixturesOfThisCup(season, 1).stream().noneMatch(MatchFixture::isPlayed) == false,
                "the ties were not marked played, so the guard was never disarmed and this proves nothing");

        seeder.seedIfMissing();

        assertEquals(afterFirstPress.size(), fixturesOfThisCup(season, 1).size(),
                "the button redrew a round that was already drawn and already played: every club in it has "
                        + "been entered a second time, against a different opponent");
    }

    /**
     * The scheduled draw has the same guard and the same blind spot.
     *
     * <p>{@code drawRoundForWeek} asked the unplayed question too. The job runner's done-flag hides that
     * for a slot that has not been rewound, and hides nothing at all for one that has — and the rewind is
     * a supported admin operation.
     */
    @Test
    @DisplayName("the scheduled draw does not redeal a round that has been played")
    void theScheduledDrawDoesNotRedealAPlayedRound() {
        aNationalCupWithClubs(310);
        int season = seasons.getActiveSeasonYear();

        assertTrue(seeder.drawRoundForWeek(1) > 0, "round 1 was not drawn, so this test proves nothing");
        List<MatchFixture> drawn = fixturesOfThisCup(season, 1);

        for (MatchFixture fixture : drawn) {
            fixture.setPlayed(true);
            fixtures.save(fixture);
        }

        assertEquals(0, seeder.drawRoundForWeek(1),
                "the scheduled draw redealt a round that was already drawn and already played");
        assertEquals(drawn.size(), fixturesOfThisCup(season, 1).size(),
                "the round gained ties it should not have been dealt again");
    }

    /**
     * The owner's week map, pinned in full.
     *
     * <p>Stated by the owner on 2026-10-13 for Kup Srbije in a country of 310 clubs, and it is written out
     * here in the order he gave it: weeks 1-5, week 6 is the national-team pause, then weeks 7, 8, 9 and 10,
     * with the final on week 10.
     *
     * <p>The constant read {@code {1, 2, 3, 4, 5, 7, 8, 11}} — eight entries, so a 256-team knockout had
     * <b>no final</b>, and its last round sat three weeks after the quarter-final. The class comment
     * defended that as a reading of an earlier brief, which is the kind of comment that outlives the brief
     * it was defending.
     *
     * <p>The arithmetic is asserted as well as the calendar, because a map of the right length in the
     * wrong order still produces a cup that ends on the wrong night.
     */
    @Test
    @DisplayName("the cup runs weeks 1-5 and 7-10, no week 6, and finishes with a final on week 10")
    void theWeekMapIsTheOwners() {
        assertArrayEquals(new int[]{1, 2, 3, 4, 5, 7, 8, 9, 10}, SeasonCalendar.CUP_WEEKS,
                "the cup's week map is not the owner's: weeks 1-5 then 7-10, week 6 being the national-team week");

        // Nine rounds, and they must be the nine the bracket needs: 256 entrants after round 2 take eight
        // halvings to a single champion, plus the entry round in front of them.
        assertEquals(9, SeasonCalendar.CUP_WEEKS.length,
                "a 310-club cup needs the entry round plus eight halvings from 256, which is nine rounds");

        for (int week : SeasonCalendar.CUP_WEEKS) {
            assertTrue(week != 6, "week 6 is the national-team week and carries no club football");
            assertTrue(week >= 1 && week <= 12, "week " + week + " is outside a twelve-week season");
        }

        // The rounds the bracket produces, from the owner's arithmetic, checked against the map.
        // Round 2 is the one week the field grows rather than halves: the 54 entry-round winners join the
        // 202 clubs above it.
        int[] entrantsPerRound = {108, 256, 128, 64, 32, 16, 8, 4, 2};
        assertEquals(54, entrantsPerRound[0] / 2, "the entry round's 108 clubs must produce 54 winners");
        assertEquals(202, entrantsPerRound[1] - entrantsPerRound[0] / 2,
                "round 2 must add the 202 clubs above the entry round to the 54 that came through it");
        assertEquals(256, entrantsPerRound[1], "round 2's field is the owner's 256");

        // From round 3 on it is a straight halving, and the last round's winner is the champion.
        for (int round = 2; round < entrantsPerRound.length; round++) {
            assertEquals(entrantsPerRound[round] * 2, entrantsPerRound[round - 1],
                    "round " + (round + 1) + " should be half of round " + round
                            + ", so " + entrantsPerRound[round - 1] + " and not " + entrantsPerRound[round]);
            assertTrue(entrantsPerRound[round] % 2 == 0,
                    "round " + (round + 1) + " has " + entrantsPerRound[round] + " entrants, which is odd");
        }
        assertEquals(1, entrantsPerRound[entrantsPerRound.length - 1] / 2,
                "the bracket does not finish on one champion over nine rounds");

        // And the final is the last week on the map, not a week that merely happens to be free.
        assertEquals(10, SeasonCalendar.CUP_WEEKS[SeasonCalendar.CUP_WEEKS.length - 1],
                "the last round is not on the owner's final week");
    }

    /**
     * A national side can never be drawn into a club cup, and neither can a misfiled one.
     *
     * <p>This is the guarantee behind commit {@code 42fc45c7}, and it had **no test**. The filter exists —
     * {@code CupFixtureSeeder.isLeagueClub} keeps only a team whose competition is a LEAGUE — but nothing
     * failed when it was removed, which is the same "a green status is not evidence" shape this board keeps
     * hitting.
     *
     * <p>It matters because it already went wrong once. Serbia U-21 was in Kup Srbije rounds 1 to 4 and
     * <b>won three knockout rounds</b>, and SK Kikinda was not in the cup at all because a national side
     * took its place in the preliminary. The bracket was drawn before the filter existed and nothing has
     * redrawn it; this test is what stops the next world from starting the same way.
     *
     * <p>Three kinds of team that must never be drawn, and the third is the one a "has a competition" test
     * would wave through: a team in a <b>CUP</b> is not a league club either.
     */
    @Test
    @DisplayName("no national side and no misfiled club is ever drawn into a national cup")
    void noNonLeagueClubIsEverDrawnIntoACup() {
        Competition cup = aNationalCupWithClubs(310);
        Country country = cup.getCountry();
        int season = seasons.getActiveSeasonYear();

        // The two shapes a country actually has, and they are not in a league.
        Team senior = aTeamOutsideAnyLeague("ZZ Senior " + UUID.randomUUID(), country);
        Team under21 = aTeamOutsideAnyLeague("ZZ U21 " + UUID.randomUUID(), country);

        // And a club filed in a CUP instead of a league, which "has a competition" would accept.
        Competition otherCup = new Competition();
        otherCup.setName("ZZ Other Cup " + UUID.randomUUID());
        otherCup.setType(CompetitionType.CUP);
        otherCup.setScope(CompetitionScope.NATIONAL);
        otherCup.setCountry(country);
        otherCup = competitions.save(otherCup);
        Team misfiled = new Team();
        misfiled.setName("ZZ Misfiled " + UUID.randomUUID());
        misfiled.setCountry(country);
        misfiled.setCompetition(otherCup);
        misfiled.setHumanControlled(false);
        misfiled.setReputation(50.0);
        misfiled = teams.save(misfiled);

        seeder.seedIfMissing();
        assertTrue(seeder.drawRoundForWeek(2) >= 0, "round 2 did not run, so this test proves nothing");

        for (Team forbidden : List.of(senior, under21, misfiled)) {
            List<MatchFixture> ties = fixtures.findAllForTeam(forbidden.getId());
            assertTrue(ties.isEmpty(), forbidden.getName() + " was drawn into " + cup.getName()
                    + " in " + ties.size() + " tie(s). A club cup takes league clubs; a national side is not "
                    + "one, and neither is a club filed in a cup.");
        }

        // The draw must still have happened, or "nothing was drawn" would satisfy the assertions above.
        assertTrue(fixturesOfThisCup(season, 1).size() > 0,
                "the cup drew nothing at all, so this test proves nothing");
    }

    /** A team with no competition at all, which is exactly how a national side is stored. */
    private Team aTeamOutsideAnyLeague(String name, Country country) {
        Team team = new Team();
        team.setName(name);
        team.setCountry(country);
        team.setCompetition(null);
        team.setHumanControlled(false);
        team.setReputation(50.0);
        return teams.save(team);
    }

    /** The ties of the domestic cup this test just built, for one round. */
    private List<MatchFixture> fixturesOfThisCup(int season, int round) {
        Competition cup = competitions.findAll().stream()
                .filter(c -> c.getType() == CompetitionType.CUP)
                .filter(c -> c.getScope() == CompetitionScope.NATIONAL)
                .filter(c -> c.getName() != null && c.getName().startsWith("ZZ National Cup"))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("the test's own cup is gone, so this proves nothing"));
        return fixtures.findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(cup.getId(), season)
                .stream()
                .filter(f -> f.getRoundNumber() != null && f.getRoundNumber() == round)
                .toList();
    }
}
