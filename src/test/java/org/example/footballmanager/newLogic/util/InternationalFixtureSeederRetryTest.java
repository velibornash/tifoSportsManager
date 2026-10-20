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
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B3: the international seeder must be able to run again after it failed to draw.
 *
 * <p>It could not. The method opened with <em>"does any INTERNATIONAL competition exist"</em> and saved
 * that competition on the very next lines. So the first run created it, found fewer than two senior
 * sides with a squad, logged at info and returned — <b>with the competition committed</b>. Every run after
 * that took the guard and returned immediately, and the seeder could never draw anything, for ever.
 *
 * <p>Nothing reported a failure. {@code MatchdayJob} then found the empty competition each week, found no
 * fixtures, logged at debug and was marked <b>DONE</b> — so {@code job_run} recorded a successful
 * international matchday that played nothing, once a week, indefinitely.
 *
 * <p><b>The guard this file exists for is the second run.</b> A test that only calls the seeder once and
 * asserts a competition exists passes against the broken code, because the broken code does create the
 * competition. It is the <em>retry</em> that has to work, and that is what the old guard prevented.
 */
class InternationalFixtureSeederRetryTest extends BaseTest {

    private static final int SEASON = 1;

    @Autowired private CompetitionRepository competitions;
    @Autowired private org.example.footballmanager.newLogic.repository.CountryRepository countries;
    @Autowired private org.example.footballmanager.newLogic.repository.GameClockRepository clocks;
    @Autowired private MatchFixtureRepository fixtures;
    @Autowired private TeamRepository teams;
    @Autowired private PlayerRepository players;

    @Test
    @Transactional
    @DisplayName("a seeder that could not draw leaves nothing behind that would stop it retrying")
    void aFailedDrawLeavesNothingBehind() {
        // One side with a squad, so the draw cannot happen: two are needed.
        Country lonely = countries.save(aCountry("Lonely"));
        seniorSideWithSquad(lonely);

        newSeeder().seedIfMissing(SEASON);

        assertEquals(0, nationalInternationals().size(),
                "the seeder created the internationals competition on a path where it drew nothing. That "
                        + "empty competition is what stops every later run — the old guard asked whether it "
                        + "existed — and it is also what MatchdayJob finds and records as a successful "
                        + "matchday with no fixtures.");
        assertEquals(0, drawnTies(),
                "fixtures were drawn with only one side in the world");
    }

    @Test
    @Transactional
    @DisplayName("once more sides have squads, a later run draws — the seeder is not stuck")
    void aLaterRunDrawsOnceThereAreSides() {
        Country first = countries.save(aCountry("First"));
        seniorSideWithSquad(first);

        InternationalFixtureSeeder seeder = newSeeder();
        seeder.seedIfMissing(SEASON);
        assertEquals(0, drawnTies(), "nothing could be drawn from one side");

        // The world grows — this is what activating a country does.
        Country second = countries.save(aCountry("Second"));
        seniorSideWithSquad(second);

        seeder.seedIfMissing(SEASON);

        assertEquals(1, drawnTies(),
                "two sides with squads now exist and the seeder still drew nothing. Under the old guard the "
                        + "first run had already created the competition, so this run returned at the top "
                        + "without looking at a single side.");
    }

    @Test
    @Transactional
    @DisplayName("a drawn competition is not drawn again")
    void aDrawnCompetitionIsNotDrawnTwice() {
        // The counterweight. "Always redraw" satisfies both tests above perfectly while duplicating the
        // whole first round every time the seeder runs — which is a half-drawn tournament, and the board
        // calls that worse than an obviously empty one.
        Country first = countries.save(aCountry("First"));
        Country second = countries.save(aCountry("Second"));
        seniorSideWithSquad(first);
        seniorSideWithSquad(second);

        InternationalFixtureSeeder seeder = newSeeder();
        seeder.seedIfMissing(SEASON);
        long afterFirst = drawnTies();
        assertTrue(afterFirst > 0, "the first run drew nothing, so the idempotence below proves nothing");

        seeder.seedIfMissing(SEASON);
        seeder.seedIfMissing(SEASON);

        assertEquals(afterFirst, drawnTies(),
                "the draw was repeated. The fixtures are the record of whether it happened — the same rule "
                        + "CupFixtureSeeder and NationalTeamSeeder both use — and not a flag.");
    }

    // --- wiring ---

    private InternationalFixtureSeeder newSeeder() {
        return new InternationalFixtureSeeder(competitions, fixtures, teams, players, clocks);
    }

    // --- helpers ---

    private List<Competition> nationalInternationals() {
        return competitions.findByType(CompetitionType.INTERNATIONAL).stream()
                .filter(c -> c.getScope() != CompetitionScope.INTERNATIONAL)
                .toList();
    }

    private long drawnTies() {
        return nationalInternationals().stream()
                .mapToLong(c -> fixtures.countByCompetitionIdAndSeasonYearAndRoundNumberAndPlayedFalse(
                        c.getId(), SEASON, 1))
                .sum();
    }

    private void seniorSideWithSquad(Country country) {
        Team side = new Team();
        // A senior side is called after its country alone, and the country points at it. The seeder
        // recognises a senior side by that reference, not by the name it used to end with.
        side.setName(country.getName());
        side.setCountry(country);
        side.setType(CompetitionTeamType.NATIONAL_TEAM);
        side = teams.save(side);
        country.setSeniorNationalTeam(side);
        countries.save(country);

        Player player = new Player();
        player.setName("ZZ International " + UUID.randomUUID());
        player.setTeam(side);
        player.setAge(23);
        players.save(player);
    }

    private Country aCountry(String label) {
        Country country = new Country();
        country.setName("ZZ " + label + " " + UUID.randomUUID());
        country.setIsoCode("I" + UUID.randomUUID().toString().substring(0, 2).toUpperCase());
        country.setState(CountryState.SIMULATED);
        return countries.save(country);
    }
}
