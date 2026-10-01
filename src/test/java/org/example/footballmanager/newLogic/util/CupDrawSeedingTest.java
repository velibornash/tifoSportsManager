package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.service.SeasonService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

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

    /** A domestic cup with {@code clubCount} clubs in it, which is what the draw reads. */
    private Competition aNationalCupWithClubs(int clubCount) {
        Country country = new Country();
        country.setName("ZZ Cup " + System.nanoTime());
        country.setIsoCode("CU" + (char) ('A' + Math.abs(System.nanoTime()) % 20));
        country.setState(CountryState.SIMULATED);
        country = countries.save(country);

        Competition cup = new Competition();
        cup.setName("ZZ National Cup " + System.nanoTime());
        cup.setType(CompetitionType.CUP);
        cup.setScope(CompetitionScope.NATIONAL);
        cup.setCountry(country);
        cup = competitions.save(cup);

        for (int i = 1; i <= clubCount; i++) {
            Team club = new Team();
            club.setName(String.format("ZZ Cup FC%02d", i));
            club.setCountry(country);
            club.setCompetition(cup);
            club.setHumanControlled(false);
            club.setReputation(50.0);
            teams.save(club);
        }
        return cup;
    }
}
