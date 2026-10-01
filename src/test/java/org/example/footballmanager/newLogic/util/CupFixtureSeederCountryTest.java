package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cup draws the clubs of <b>its own country</b>.
 *
 * <p><b>This exists because the country was never asked for.</b> {@code rankedClubs()} took no argument:
 * it asked for every club in the world and kept the ones whose country matched
 * <i>whichever club the unordered query returned first</i> — "a Serbia dependency expressed as a
 * {@code continue}". So the draw field depended on database row order, and {@code survivorsOf(cup, round)}
 * — which has the cup in hand — called it without one, meaning round 1's field was the world's bottom 108
 * clubs rather than this cup's.
 *
 * <p>A second country with its own cup is the whole test. With one country the old code passes, which is
 * why it survived: nothing could distinguish "correct" from "right by accident".
 */
class CupFixtureSeederCountryTest extends BaseTest {

    private static final long SEED = 20260928L;
    /** MAIN_DRAW_TEAMS is 256, so a country needs at least that many clubs for any draw at all. */
    private static final int CLUBS_PER_COUNTRY = 260;

    @Autowired private CompetitionRepository competitions;
    @Autowired private MatchFixtureRepository fixtures;
    @Autowired private TeamRepository teams;
    @Autowired private CountryRepository countries;
    @Autowired private org.example.footballmanager.newLogic.repository.PlayerRepository players;
    @Autowired private org.example.footballmanager.newLogic.service.SeasonService seasonService;

    private CupFixtureSeeder seeder;
    private java.util.Map<Long, Integer> indexById;
    private Country serbia;
    private Country hungary;

    @BeforeEach
    void setUp() {
        // The real SeasonService, not a mock: the seeder reads the active season from it, and a stub
        // returning a different number would draw into a season the test never inspects.
        seeder = new CupFixtureSeeder(competitions, fixtures, teams, players, seasonService, new Random(SEED));
    }

    /**
     * The owner-visible claim: the round-1 field is the bottom ENTRY_ROUND_TEAMS of <b>this cup's</b>
     * country, and every drawn tie has both ends in that country.
     */
    @Test
    @Transactional
    @DisplayName("round 1 draws only clubs from the cup's own country")
    void roundOneDrawsOnlyTheCupsCountry() {
        // **Hungary is created and populated FIRST, deliberately.**
        //
        // findClubTeamsForOperations returns clubs in id order, so "the first club the repository
        // returns" is a Hungarian one here. An earlier version of this test created Serbia first, and
        // the old country-inferring code then passed it — the mutation check caught a green test that
        // proved nothing. The bug only shows when the wrong country happens to come first, which is
        // exactly why it survived in production: on a fresh database Serbia is often first, so it looked
        // right until another country was seeded ahead of it.
        hungary = aCountry("Hungary", "HU");
        serbia = aCountry("Serbia", "RS");

        List<Team> hungaryClubs = clubsIn(hungary, "HU", CLUBS_PER_COUNTRY, 90);
        List<Team> serbiaClubs = clubsIn(serbia, "RS", CLUBS_PER_COUNTRY, 50);

        // Prove the trap is armed: the lowest-id club in the world must be Hungarian.
        Team lowestId = teams.findClubTeamsForOperations().stream()
                .min(java.util.Comparator.comparing(Team::getId))
                .orElseThrow();
        assertTrue(hungaryClubs.stream().anyMatch(t -> t.getId().equals(lowestId.getId())),
                "this test no longer exercises the bug: the lowest-id club is Serbian, so a seeder "
                        + "that reads the country off the first row would still pass");

        Competition cup = aCup(serbia, "Serbian Cup");
        assertNotNull(cup, "the domestic cup must exist for the draw to have a target");

        int drawn = seeder.drawRoundForWeek(CupFixtureSeeder.CUP_WEEKS[0]);

        assertTrue(drawn > 0, "the cup drew nothing at all, so there is nothing to check");

        List<MatchFixture> round1 = fixtures.findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(
                cup.getId(), SEASON()).stream()
                .filter(f -> f.getWeekNumber() != null && f.getWeekNumber() == CupFixtureSeeder.CUP_WEEKS[0])
                .toList();

        assertTrue(!round1.isEmpty(), "round 1 recorded no fixtures");

        for (MatchFixture f : round1) {
            Long homeId = f.getHomeTeam() == null ? null : f.getHomeTeam().getId();
            Long awayId = f.getAwayTeam() == null ? null : f.getAwayTeam().getId();
            assertTrue(serbiaClubs.stream().anyMatch(t -> t.getId().equals(homeId)),
                    "a round-1 tie has a home club that is not Serbian: " + homeId);
            assertTrue(serbiaClubs.stream().anyMatch(t -> t.getId().equals(awayId)),
                    "a round-1 tie has an away club that is not Serbian: " + awayId);
        }
    }

    /**
     * The field is the clubs that have to qualify — the bottom 108 — and not the strongest.
     *
     * <p>The seeder's own comment says "ranks 203-310 enter in week 1". It once took {@code subList(0, 108)}
     * of a descending list, which is the <i>strongest</i> 108: the exact opposite of what it documented.
     */
    @Test
    @Transactional
    @DisplayName("round 1 is the weakest clubs, not the strongest")
    void roundOneIsTheWeakestClubs() {
        serbia = aCountry("Serbia", "RS");
        // Distinct ratings per club so "strongest" and "weakest" are unambiguous: club i has rating 40+i.
        // The index is remembered in a map rather than parsed back out of the name.
        indexById = new java.util.HashMap<>();
        for (int i = 0; i < CLUBS_PER_COUNTRY; i++) {
            Team club = aClub(serbia, "RS", String.format("RS-%03d", i), 40 + i);
            indexById.put(club.getId(), i);
        }
        Competition cup = aCup(serbia, "Serbian Cup");

        seeder.drawRoundForWeek(CupFixtureSeeder.CUP_WEEKS[0]);

        List<MatchFixture> round1 = fixtures.findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(
                cup.getId(), SEASON()).stream()
                .filter(f -> f.getWeekNumber() != null && f.getWeekNumber() == CupFixtureSeeder.CUP_WEEKS[0])
                .toList();
        assertTrue(!round1.isEmpty(), "round 1 recorded no fixtures");

        // Club i has rating 40+i, so a LOW index is a WEAK club: the weakest ENTRY_ROUND_TEAMS are
        // indexes 0..107 and those are exactly the ones that must play the preliminary. The strongest
        // club, index 259, is a direct entrant and must not appear.
        //
        // An earlier version of this assertion had the direction backwards and demanded the opposite,
        // so it failed on correct code: rankedClubs sorts strength descending, and subList(152, 260) of
        // a 260-club list is the tail - the weak end.
        for (MatchFixture f : round1) {
            for (Team side : List.of(f.getHomeTeam(), f.getAwayTeam())) {
                Integer index = indexById.get(side.getId());
                assertNotNull(index, side.getName() + " is not one of this test's clubs");
                assertTrue(index < CupFixtureSeeder.ENTRY_ROUND_TEAMS,
                        side.getName() + " (index " + index + ") is in the preliminary but is not one of "
                                + "the weakest " + CupFixtureSeeder.ENTRY_ROUND_TEAMS + " clubs");
            }
        }

        // And the strongest club must have been given a bye, stated directly rather than left implied.
        int strongestIndex = CLUBS_PER_COUNTRY - 1;
        Long strongestId = indexById.entrySet().stream()
                .filter(e -> e.getValue() == strongestIndex)
                .map(java.util.Map.Entry::getKey)
                .findFirst()
                .orElseThrow();
        for (MatchFixture f : round1) {
            assertTrue(!strongestId.equals(f.getHomeTeam().getId())
                            && !strongestId.equals(f.getAwayTeam().getId()),
                    "the strongest club was made to survive a preliminary it should have been exempt from");
        }
    }

    /**
     * A country too small for the <b>main draw</b> gets no main draw, and says so rather than inventing one.
     *
     * <p>This is the second half of the board's B2 note: MAIN_DRAW_TEAMS is 256 and ENTRY_ROUND_TEAMS is
     * 108, and both only close because Serbia happens to have 310 clubs. A country with 40 clubs still
     * plays a preliminary among those 40 - which is correct, and is why an earlier version of this test
     * asserting "no draw at all" was wrong rather than the code - but it can never assemble the 256-club
     * main draw, and must leave that round empty instead of fielding a handful of clubs in it.
     *
     * <p>The first version of this test asserted zero fixtures from week 1 and failed with 20. The
     * assertion was wrong.
     */
    @Test
    @Transactional
    @DisplayName("a country too small for the main draw is left out of it, not given a handful of clubs")
    void aSmallCountryIsLeftOutOfTheMainDraw() {
        serbia = aCountry("Serbia", "RS");
        clubsIn(serbia, "RS", 40, 50);
        Competition cup = aCup(serbia, "Serbian Cup");

        // Week 1 is the preliminary and legitimately draws the 40 clubs it has.
        int preliminary = seeder.drawRoundForWeek(CupFixtureSeeder.CUP_WEEKS[0]);
        assertTrue(preliminary > 0, "a 40-club preliminary should still be drawn");

        // Week 2 is where the 256-club main draw is assembled. 40 clubs cannot make 256.
        int mainDraw = seeder.drawRoundForWeek(CupFixtureSeeder.CUP_WEEKS[1]);
        assertEquals(0, mainDraw,
                "40 clubs were assembled into a main draw that requires " + CupFixtureSeeder.MAIN_DRAW_TEAMS
                        + ". Every tie in it is a fixture that should not exist.");
    }

    private int SEASON() {
        return seasonService.getActiveSeasonYear();
    }

    private Country aCountry(String name, String iso) {
        Country c = new Country();
        c.setName(name + " " + System.nanoTime());
        c.setIsoCode(iso);
        c.setState(CountryState.SIMULATED);
        return countries.save(c);
    }

    private List<Team> clubsIn(Country country, String prefix, int count, int baseRating) {
        List<Team> made = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            made.add(aClub(country, prefix, String.format("%s-%03d", prefix, i), baseRating + i));
        }
        return made;
    }

    private Team aClub(Country country, String prefix, String name, int rating) {
        Team team = new Team();
        team.setName(name + "-" + System.nanoTime() % 100000);
        team.setCountry(country);
        team.setReputation(50.0);
        team = teams.save(team);
        // One player is enough for averageSquadRating, and one query per club is what production does.
        Player player = new Player();
        player.setName(name + " forward " + playerNameSuffix());
        player.setTeam(team);
        player.setPosition(Position.ATT);
        player.setRating(rating);
        players.save(player);
        return team;
    }

    private int playerNameSuffix() {
        return (int) (System.nanoTime() % 100000);
    }

    private Competition aCup(Country country, String name) {
        Competition cup = new Competition();
        cup.setName(name + " " + System.nanoTime());
        cup.setType(CompetitionType.CUP);
        cup.setScope(CompetitionScope.NATIONAL);
        cup.setTeamType(CompetitionTeamType.CLUB);
        cup.setTier(1);
        cup.setCountry(country);
        return competitions.save(cup);
    }
}