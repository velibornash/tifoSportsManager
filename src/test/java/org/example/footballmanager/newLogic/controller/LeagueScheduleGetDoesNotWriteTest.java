package org.example.footballmanager.newLogic.controller;

import org.example.commonmanager.model.User;
import org.example.commonmanager.model.UserRole;
import org.example.commonmanager.util.JwtUtil;
import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.CompetitionEntry;
import org.example.footballmanager.newLogic.model.SeasonCompetition;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.SeasonCompetitionRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reading a schedule must not build it.
 *
 * <p>{@code GET /countries/leagues/{id}/schedule} called {@code ensureEntriesForSeasonCompetition} and
 * {@code ensureDoubleRoundRobinSchedule}, so opening the page created the entries and every fixture of a
 * double round robin — thousands of rows — on a request that is supposed to be safe.
 *
 * <ul>
 *   <li>Any authenticated manager could write to the world just by opening a page.</li>
 *   <li>Two managers opening it at once raced each other into generating the same fixtures.</li>
 *   <li>Anything that caches a GET — a proxy, a CDN, the browser — could freeze the fixture list at the
 *       moment it first ran.</li>
 * </ul>
 *
 * <p>Both calls already happen where they belong: {@code PyramidBuilder} does them when a pyramid is built,
 * {@code SimulatedWorldSeeder} reaches it, and {@code AdminController} exposes that as the seeding action.
 *
 * <p>The test asserts on the row counts before and after, which is the only thing that distinguishes a read
 * from a write here — the response body looks identical either way, which is why this survived.
 */
class LeagueScheduleGetDoesNotWriteTest extends BaseTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtUtil jwtUtil;

    @Autowired
    CountryRepository countries;

    @Autowired
    TeamRepository teams;

    @Autowired
    CompetitionRepository competitions;

    @Autowired
    MatchFixtureRepository fixtures;

    @Autowired
    CompetitionEntryRepository entries;

    @Autowired
    SeasonCompetitionRepository seasonCompetitions;

    private Competition league;

    @BeforeEach
    @Transactional
    void aLeagueWithFourClubsAndNoSchedule() {
        Country country = new Country();
        country.setName("Read only " + UUID.randomUUID());
        country.setIsoCode("R" + UUID.randomUUID().toString().substring(0, 2).toUpperCase());
        country.setState(CountryState.SIMULATED);
        country = countries.save(country);

        league = new Competition();
        league.setName("Read only league " + UUID.randomUUID());
        league.setType(CompetitionType.LEAGUE);
        league.setScope(CompetitionScope.NATIONAL);
        league.setTeamType(CompetitionTeamType.CLUB);
        league.setTier(1);
        league.setCountry(country);
        league = competitions.save(league);

        // Four clubs **registered in the competition**, so a double round robin would be twelve fixtures if
        // anything generated them.
        //
        // The entries matter: ensureDoubleRoundRobinSchedule reads its entrants from
        // competitionEntryRepository, and with fewer than two it returns having done nothing. An earlier
        // version of this fixture created bare clubs, so the generator was a no-op and **the test passed
        // against the write-on-GET code it was written to catch** — a guard with nothing to guard.
        SeasonCompetition seasonCompetition = new SeasonCompetition();
        seasonCompetition.setCompetition(league);
        seasonCompetition.setSeasonYear(1);
        seasonCompetition = seasonCompetitions.save(seasonCompetition);

        for (int i = 0; i < 4; i++) {
            Team team = new Team();
            team.setName("Read only club " + i + " " + UUID.randomUUID());
            team.setCountry(country);
            team = teams.save(team);

            CompetitionEntry entry = new CompetitionEntry();
            entry.setSeasonCompetition(seasonCompetition);
            entry.setTeam(team);
            entry.setPoints(0);
            entry.setWins(0);
            entry.setDraws(0);
            entry.setLosses(0);
            entry.setGoalsScored(0);
            entry.setGoalsConceded(0);
            entries.save(entry);
        }
    }

    /**
     * The core assertion. Four clubs means a generated double round robin is twelve fixtures, and four
     * competition entries — so a write would be obvious rather than a rounding difference.
     */
    @Test
    @Transactional
    @DisplayName("reading the schedule creates no fixtures and no entries")
    void readingTheScheduleWritesNothing() throws Exception {
        long entriesBefore = entries.count();
        long fixturesBefore = fixtures.findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(
                league.getId(), 1).size();
        assertEquals(0, fixturesBefore, "this test needs a league with no fixtures to be meaningful");

        mockMvc.perform(get("/countries/leagues/{id}/schedule", league.getId())
                        .header("Authorization", bearer()))
                .andExpect(status().isOk());

        long fixturesAfter = fixtures.findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(
                league.getId(), 1).size();
        assertEquals(0, fixturesAfter,
                "a GET created " + fixturesAfter + " fixtures. Four clubs in a double round robin is twelve, so "
                        + "this is a generated schedule, not rounding. World building belongs in the seeding "
                        + "path, not in a page load.");

        // The fixture registers four entries, so the assertion is that the count did not grow.
        long entriesAfter = entries.count();
        assertEquals(entriesBefore, entriesAfter,
                "a GET changed the competition entries for a league from " + entriesBefore + " to "
                        + entriesAfter + ".");
    }

    /** Two reads in a row must be identical, which is what a cache assumes and a generator breaks. */
    @Test
    @Transactional
    @DisplayName("reading it twice is the same read twice")
    void readingItTwiceIsStable() throws Exception {
        String first = mockMvc.perform(get("/countries/leagues/{id}/schedule", league.getId())
                        .header("Authorization", bearer()))
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(get("/countries/leagues/{id}/schedule", league.getId())
                        .header("Authorization", bearer()))
                .andReturn().getResponse().getContentAsString();

        assertEquals(first, second,
                "two reads of an unbuilt league returned different bodies, so the first one built something");
        assertEquals("[]", first.replaceAll("\s", ""),
                "a league with no schedule should read as honestly empty, not be quietly generated for "
                        + "whoever looked at it first: " + first);
    }

    private String bearer() {
        User user = new User();
        user.setUsername("read-only-" + UUID.randomUUID() + "@test.local");
        user.setEmail(user.getUsername());
        user.setPassword("not-a-real-hash");
        user.setDisplayName("Read only tester");
        user.setRole(UserRole.REGULAR);
        user.setPlusSubscription(false);
        return "Bearer " + jwtUtil.generateToken(users.save(user));
    }

    @Autowired
    org.example.commonmanager.repository.UserRepository users;
}