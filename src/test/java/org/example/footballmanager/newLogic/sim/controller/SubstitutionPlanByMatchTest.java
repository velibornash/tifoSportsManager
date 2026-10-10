package org.example.footballmanager.newLogic.sim.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.SubstitutionPlanRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The plan reached from the <b>match</b>, which is the only direction a manager has after the whistle
 * (T0-UI-4b).
 *
 * <p>The plan is keyed by fixture. That is correct — the owner closes substitution decisions an hour
 * before kickoff, before any {@code Match} row exists — but it left the feature with a front door nobody
 * could use: a manager who has just watched a game stands on the match view holding a match id, and
 * there was no route from there to the plan. The outcome record was written for every played match and
 * could not be fetched from any screen a manager reaches.
 *
 * <p><b>The link.</b> {@code MatchFixture.playedMatch} is unique, so match → fixture is an exact answer
 * rather than a guess. An exhibition has no fixture at all and is refused with 404 rather than answered
 * with an empty plan, because an empty plan is exactly what "no conditions set" looks like, and those are
 * different situations.
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.test.context.ActiveProfiles("test")
class SubstitutionPlanByMatchTest {

    @Autowired MockMvc mvc;
    @Autowired TeamRepository teams;
    @Autowired CompetitionRepository competitions;
    @Autowired MatchFixtureRepository fixtures;
    @Autowired MatchRepository matches;
    @Autowired SubstitutionPlanRepository plans;
    @Autowired org.example.commonmanager.repository.UserRepository users;

    private static final ObjectMapper JSON = new ObjectMapper();

    private String bearer;
    private Team home;
    private Team away;
    private Competition competition;

    @BeforeEach
    void setUp() {
        String username = "planbymatch-" + System.nanoTime();
        org.example.commonmanager.model.User user = new org.example.commonmanager.model.User();
        user.setUsername(username);
        user.setEmail(username + "@test.local");
        user.setPassword("irrelevant-for-this-test");
        user.setRole(org.example.commonmanager.model.UserRole.REGULAR);
        users.save(user);
        bearer = "Bearer " + io.jsonwebtoken.Jwts.builder()
                .subject(username)
                .claim("role", "USER")
                .issuedAt(new java.util.Date())
                .expiration(new java.util.Date(System.currentTimeMillis() + 3600_000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        "VeljaTestSecretKeyVeryLongAndSecure12345678901234567890"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .compact();

        home = teams.save(team("ByMatch Home"));
        away = teams.save(team("ByMatch Away"));

        competition = new Competition();
        competition.setName("ByMatch League " + System.nanoTime());
        competition.setType(org.example.footballmanager.newLogic.model.CompetitionType.LEAGUE);
        competition = competitions.save(competition);
    }

    private Team team(String prefix) {
        Team team = new Team();
        team.setName(prefix + " " + System.nanoTime());
        return team;
    }

    private MatchFixture anUpcomingFixture() {
        MatchFixture fixture = new MatchFixture();
        fixture.setHomeTeam(home);
        fixture.setAwayTeam(away);
        fixture.setCompetition(competition);
        fixture.setSeasonYear(1);
        fixture.setWeekNumber(1);
        fixture.setDayNumber(3);
        fixture.setMatchDate(LocalDateTime.now().plusDays(3));
        return fixtures.save(fixture);
    }

    /** Plays the fixture the way the simulation does: a Match row, then the unique link back. */
    private Match playTheFixture(MatchFixture fixture) {
        Match match = new Match();
        match.setHomeTeam(home);
        match.setAwayTeam(away);
        match.setCompetition(competition);
        match.setSeasonYear(1);
        match.setWeekNumber(1);
        match.setDayNumber(3);
        match.setMatchDate(fixture.getMatchDate());
        match.setHomeGoals(2);
        match.setAwayGoals(1);
        match.setPlayed(true);
        match = matches.save(match);

        fixture.setPlayedMatch(match);
        fixture.setPlayed(true);
        fixtures.save(fixture);
        return match;
    }

    private String putPlan(MatchFixture fixture, String rules) throws Exception {
        mvc.perform(put("/api/sim/fixtures/" + fixture.getId() + "/substitution-plan")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rules\":" + JSON.writeValueAsString(rules) + "}"))
                .andExpect(status().isOk());
        return rules;
    }

    @Test
    @DisplayName("a played match serves the plan of the fixture it came from, outcome and all")
    void theMatchServesItsFixturePlan() throws Exception {
        MatchFixture fixture = anUpcomingFixture();

        // Written before kickoff, which is the real sequence and the reason the plan is keyed by fixture.
        String rules = "[{\"team\":\"HOME\",\"triggerMinute\":60,\"condition\":\"LOSING\","
                + "\"playerOnId\":\"\",\"playerOffId\":\"\"}]";
        putPlan(fixture, rules);

        Match match = playTheFixture(fixture);

        // The outcome is written by the simulation, not through this endpoint. Setting it here makes the
        // assertion below about the *route* rather than about who filled the column.
        String outcome = "[{\"team\":\"HOME\",\"triggerMinute\":60,\"condition\":\"LOSING\","
                + "\"playerOnId\":\"\",\"playerOffId\":\"\",\"status\":\"FIRED\",\"voidReason\":\"NONE\","
                + "\"firedAtMinute\":63}]";
        var plan = plans.findByFixtureId(fixture.getId()).orElseThrow();
        plan.setOutcomeJson(outcome);
        plans.save(plan);

        mvc.perform(get("/api/sim/matches/" + match.getId() + "/substitution-plan")
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fixtureId").value(fixture.getId()))
                .andExpect(jsonPath("$.homeTeam").value(home.getName()))
                .andExpect(jsonPath("$.rulesJson").value(rules))
                .andExpect(jsonPath("$.outcomeJson").value(outcome))
                .andExpect(jsonPath("$.editable").value(false));
    }

    @Test
    @DisplayName("the fixture and the match serve the same plan for the same game")
    void bothRoutesAgree() throws Exception {
        MatchFixture fixture = anUpcomingFixture();
        putPlan(fixture, "[{\"team\":\"HOME\",\"triggerMinute\":70,\"condition\":\"ANYTIME\","
                + "\"playerOnId\":\"\",\"playerOffId\":\"\"}]");
        Match match = playTheFixture(fixture);

        String viaFixture = mvc.perform(get("/api/sim/fixtures/" + fixture.getId() + "/substitution-plan")
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String viaMatch = mvc.perform(get("/api/sim/matches/" + match.getId() + "/substitution-plan")
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Only `updatedAt` can differ, and it must not: both routes read one row.
        org.junit.jupiter.api.Assertions.assertEquals(
                viaFixture.replaceAll("\"updatedAt\":\"[^\"]*\"", ""),
                viaMatch.replaceAll("\"updatedAt\":\"[^\"]*\"", ""),
                "the two routes disagreed about the same game's plan");
    }

    @Test
    @DisplayName("a match with no fixture is refused, not answered with an empty plan")
    void aMatchWithNoFixtureIsRefused() throws Exception {
        // An exhibition is simulated inline and never had a fixture. Returning an empty plan would be
        // indistinguishable from "the manager set no conditions", which is a different thing entirely.
        mvc.perform(get("/api/sim/matches/999999/substitution-plan")
                        .header("Authorization", bearer))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the match route is behind the same authentication as the fixture route")
    void theMatchRouteIsProtected() throws Exception {
        mvc.perform(get("/api/sim/matches/1/substitution-plan"))
                .andExpect(status().isUnauthorized());
    }
}