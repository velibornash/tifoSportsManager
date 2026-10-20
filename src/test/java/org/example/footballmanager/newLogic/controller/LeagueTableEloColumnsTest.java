package org.example.footballmanager.newLogic.controller;

import org.example.commonmanager.model.User;
import org.example.commonmanager.model.UserRole;
import org.example.commonmanager.repository.UserRepository;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionEntry;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.SeasonCompetitionRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.service.SeasonService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The league table must carry a club's Elo and its movement (owner, 2026-10-01).
 *
 * <p>This is the read side of the club-rating work, and it exists because a ranking table that shows a
 * club's points but not its strength answers only half of what a manager is looking at. The columns were
 * added to the entity and to the replay; this is the part that would silently not arrive.
 *
 * <p><b>It asserts the values, not the keys.</b> A test that only checked {@code rating} exists in the
 * JSON would pass with the column permanently null — which is what a world whose replay has never run
 * looks like, and is exactly the state this task exists to end.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LeagueTableEloColumnsTest {

    @Autowired MockMvc mvc;
    @Autowired CompetitionRepository competitions;
    @Autowired CompetitionEntryRepository entries;
    @Autowired SeasonCompetitionRepository seasonRows;
    @Autowired TeamRepository teams;
    @Autowired CountryRepository countries;
    @Autowired SeasonService seasons;
    @Autowired UserRepository users;

    private String bearer;

    @BeforeEach
    void signIn() {
        String username = "elo-table-" + System.nanoTime();
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@test.local");
        u.setPassword("irrelevant-for-this-test");
        u.setRole(UserRole.REGULAR);
        users.save(u);
        bearer = "Bearer " + io.jsonwebtoken.Jwts.builder()
                .subject(username)
                .claim("role", "USER")
                .issuedAt(new java.util.Date())
                .expiration(new java.util.Date(System.currentTimeMillis() + 3600_000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        "VeljaTestSecretKeyVeryLongAndSecure12345678901234567890"
                                .getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    @Test
    @Transactional
    @DisplayName("the table carries each club's rating and the movement against its previous value")
    void theTableCarriesRatingAndDelta() throws Exception {
        League league = aLeagueWithTwoRatedClubs();

        mvc.perform(get("/countries/leagues/{id}/table", league.id()).header("Authorization", bearer))
                .andExpect(status().isOk())
                // Deliberately not `exists()`. A null rating satisfies exists() and is the state this
                // whole task is about.
                .andExpect(jsonPath("$[0].rating").value(1500))
                .andExpect(jsonPath("$[0].ratingDelta").value(-12))
                .andExpect(jsonPath("$[1].rating").value(1340))
                .andExpect(jsonPath("$[1].ratingDelta").value(0));
    }

    @Test
    @Transactional
    @DisplayName("a club nobody has rated is still listed, and reads as never rated rather than zero")
    void anUnratedClubIsListedAndReadsNull() throws Exception {
        League league = aLeagueWithTwoRatedClubs();

        String json = mvc.perform(get("/countries/leagues/{id}/table", league.id())
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> table = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {
                });
        Map<String, Object> unrated = table.stream()
                .filter(row -> String.valueOf(row.get("name")).contains("Unrated"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the unrated club is missing from its own league table, which is a worse lie than "
                                + "showing it without a rating. Rows were " + table));

        // Null, and specifically not 0. Zero is a rating a club can legitimately hold, so a table that
        // prints 0 for every unrated club invents one - and "never rated" is exactly the state the world
        // is in until the replay first runs.
        org.junit.jupiter.api.Assertions.assertNull(unrated.get("rating"),
                "an unrated club reads as " + unrated.get("rating") + ", which invents a rating for it");
        org.junit.jupiter.api.Assertions.assertNull(unrated.get("ratingDelta"),
                "an unrated club reports movement of " + unrated.get("ratingDelta"));

        // And a null in the column must not blank its neighbours.
        assertTrue(table.stream().anyMatch(row -> row.get("rating") != null),
                "every rating in the table is null, so the column is not reaching the endpoint at all");
    }

    private record League(Long id) {
    }

    /** A two-club division where one club has a rating and a delta and the other has neither. */
    private League aLeagueWithTwoRatedClubs() {
        Country country = new Country();
        country.setName("ZZ Elo Table " + System.nanoTime());
        // iso_code is three characters, and "EL" plus a number is four. The block is mine and unused
        // elsewhere - check it before reusing, because the whole suite shares one database.
        country.setIsoCode("EL" + (char) ('A' + Math.abs(System.nanoTime()) % 20));
        country.setState(CountryState.SIMULATED);
        country = countries.save(country);

        Competition division = new Competition();
        division.setName("ZZ Elo Table Div " + System.nanoTime());
        division.setType(CompetitionType.LEAGUE);
        division.setScope(CompetitionScope.NATIONAL);
        division.setTeamType(CompetitionTeamType.CLUB);
        division.setTier(1);
        division.setDivisionLevel(1);
        division.setTeamsPerCompetition(2);
        division.setCountry(country);
        division = competitions.save(division);

        int season = seasons.getActiveSeasonYear();
        seasons.ensureSeasonCompetition(division, season);

        var seasonRow = seasonRows.findByCompetitionAndSeasonYear(division, season).orElseThrow();

        // Rated, and moved: the winner of a match rises and the column says by how much.
        Team winner = club(country, "ZZ Elo Winner", division, 1500.0, 1512.0, -12.0, 3);
        // Rated and level: a club whose last match left it where it was must read as "no change" and not
        // as a gain, which is what a bare "+0" would say.
        Team level = club(country, "ZZ Elo Level", division, 1340.0, 1340.0, 0.0, 0);
        // Never rated: the honest third state.
        Team unrated = club(country, "ZZ Elo Unrated", division, null, null, null, 0);

        return new League(division.getId());
    }

    private Team club(Country country, String name, Competition division,
                      Double rating, Double previous, Double delta, int points) {
        Team club = new Team();
        club.setName(name + " " + System.nanoTime());
        club.setCountry(country);
        club.setCompetition(division);
        club.setHumanControlled(false);
        club.setReputation(50.0);
        club.setEloRating(rating);
        club.setEloPreviousRating(previous);
        club.setEloDelta(delta);
        club = teams.save(club);

        CompetitionEntry entry = new CompetitionEntry();
        entry.setSeasonCompetition(seasonRows.findByCompetitionAndSeasonYear(
                division, seasons.getActiveSeasonYear()).orElseThrow());
        entry.setTeam(club);
        entry.setPoints(points);
        entry.setWins(0);
        entry.setDraws(0);
        entry.setLosses(0);
        entry.setGoalsScored(0);
        entry.setGoalsConceded(0);
        entries.save(entry);
        return club;
    }
}