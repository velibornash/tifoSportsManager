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
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A cup tie is only readable through the country that owns it.
 *
 * <p>{@code GET /countries/{iso}/cup/fixture/{fixtureId}} called {@code requireCountry(isoCode)} and
 * **threw the result away**, then loaded the tie by id alone. So any logged-in manager could read any
 * country's cup tie — lineups, ratings, and the result if it had been played — by guessing an id.
 *
 * <p>The country check ran and did nothing, which is worse than not having it: the code reads as though the
 * route were scoped. It is the same shape as the fixture/match id collision, where a route that looked
 * scoped was actually unscoped.
 */
class CountryCupFixtureScopingTest extends BaseTest {

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

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private Country serbia;
    private Country hungary;
    private MatchFixture hungarianTie;
    private MatchFixture serbianTie;

    @BeforeEach
    @Transactional
    void twoCountriesEachWithACupTie() {
        // iso_code is unique and two characters here, and @BeforeEach does not roll back between methods,
        // so a fixed code made the second test fail on the unique index. An earlier version of a different
        // test in this package had the same problem for the same reason.
        // A counter, because a random two-character code collides often enough to matter across four
        // methods: two-letter space is 676 and this suite has already hit a unique-index collision once.
        int n = SEQUENCE.incrementAndGet();
        serbia = aCountry("Scoping Serbia", String.format("S%02d", n));
        hungary = aCountry("Scoping Hungary", String.format("H%02d", n));

        hungarianTie = aTie(hungary, "Scoping Hungarian tie");
        serbianTie = aTie(serbia, "Scoping Serbian tie");
    }

    /** The leak: any authenticated manager, any country, any tie id. */
    @Test
    @DisplayName("a tie from another country is not readable through this country's path")
    void aTieFromAnotherCountryIsNotReadable() throws Exception {
        mockMvc.perform(get("/countries/{iso}/cup/fixture/{id}", hungary.getIsoCode(), hungarianTie.getId())
                        .header("Authorization", bearer()))
                // Reads it: this is the country's own cup.
                .andExpect(status().isOk());

        mockMvc.perform(get("/countries/{iso}/cup/fixture/{id}", serbia.getIsoCode(), hungarianTie.getId())
                        .header("Authorization", bearer()))
                // Must not. Same manager, same tie, different country in the path.
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("each country's own tie still reads")
    void eachCountrysOwnTieStillReads() throws Exception {
        mockMvc.perform(get("/countries/{iso}/cup/fixture/{id}", serbia.getIsoCode(), serbianTie.getId())
                        .header("Authorization", bearer()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a tie that does not exist is a 404, not a 500")
    void anUnknownTieIsNotFound() throws Exception {
        mockMvc.perform(get("/countries/{iso}/cup/fixture/{id}", serbia.getIsoCode(), 999_999_999L)
                        .header("Authorization", bearer()))
                .andExpect(status().isNotFound());
    }

    /**
     * An unknown country. {@code requireCountry} throws {@code IllegalArgumentException}, which the exception
     * handler now maps to 400 — a client error, and defensible, but "no such country" is a 404 and this route
     * is about looking a resource up. Asserted as "not the squad and not a 500" rather than pinning the code,
     * because the status is a wider decision than this test.
     */
    @Test
    @DisplayName("an unknown country gets no tie and no server error")
    void anUnknownCountryIsRefused() throws Exception {
        int code = mockMvc.perform(get("/countries/{iso}/cup/fixture/{id}", "QQ", serbianTie.getId())
                        .header("Authorization", bearer()))
                .andReturn().getResponse().getStatus();
        org.junit.jupiter.api.Assertions.assertTrue(code == 404 || code == 400,
                "expected a refusal for an unknown country, got " + code);
        org.junit.jupiter.api.Assertions.assertNotEquals(500, code,
                "an unknown country was reported as a server fault");
    }

    private Country aCountry(String name, String iso) {
        Country country = new Country();
        country.setName(name + " " + System.nanoTime());
        country.setIsoCode(iso);
        country.setState(CountryState.SIMULATED);
        return countries.save(country);
    }

    private MatchFixture aTie(Country country, String label) {
        Competition cup = new Competition();
        cup.setName(label + " cup");
        cup.setType(CompetitionType.CUP);
        cup.setScope(CompetitionScope.NATIONAL);
        cup.setTeamType(CompetitionTeamType.CLUB);
        cup.setTier(1);
        cup.setCountry(country);
        cup = competitions.save(cup);

        Team home = new Team();
        home.setName(label + " home");
        home.setCountry(country);
        home = teams.save(home);
        Team away = new Team();
        away.setName(label + " away");
        away.setCountry(country);
        away = teams.save(away);

        MatchFixture fixture = new MatchFixture();
        fixture.setCompetition(cup);
        fixture.setHomeTeam(home);
        fixture.setAwayTeam(away);
        fixture.setRoundNumber(1);
        fixture.setWeekNumber(1);
        fixture.setDayNumber(5);
        fixture.setSeasonYear(1);
        fixture.setPlayed(false);
        return fixtures.save(fixture);
    }

    private String bearer() {
        User user = new User();
        user.setUsername("cup-scoping-" + UUID.randomUUID() + "@test.local");
        user.setEmail(user.getUsername());
        user.setPassword("not-a-real-hash");
        user.setDisplayName("Cup scoping tester");
        user.setRole(UserRole.REGULAR);
        user.setPlusSubscription(false);
        return "Bearer " + jwtUtil.generateToken(users.save(user));
    }

    @Autowired
    org.example.commonmanager.repository.UserRepository users;
}