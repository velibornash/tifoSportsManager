package org.example.footballmanager.newLogic.sim.controller;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Conditional substitution plan persistence, keyed by <b>fixture</b>.
 *
 * <p><b>Why the key moved.</b> The plan was keyed by {@code matchId}, which meant it could only be
 * created once a {@code Match} row existed — that is, <b>after the match had been simulated</b>. The
 * owner's rule is that substitution decisions close an hour before kickoff, which is before any match
 * exists, so the key made the feature impossible to use as specified. There was no bug to fix here: the
 * endpoint worked, the plan round-tripped, and all four tests passed. It was keyed to a moment in the
 * lifecycle the feature is not about.
 *
 * <p><b>Why there is no ambiguity about which match the fixture became.</b> {@code MatchFixture.playedMatch}
 * is the only fixture → result path, is unique (one played match belongs to at most one fixture) and is
 * set by {@code SimMatchService.persist} in the same block that sets {@code played = true}. So a plan
 * written against a fixture is read by exactly one simulation — the one for that fixture.
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.test.context.ActiveProfiles("test")
class SubstitutionPlanFixtureKeyTest {

    @Autowired MockMvc mvc;
    @Autowired TeamRepository teams;
    @Autowired CompetitionRepository competitions;
    @Autowired MatchFixtureRepository fixtures;
    @Autowired org.example.commonmanager.repository.UserRepository users;

    private String bearer;

    /**
     * Every game endpoint sits behind JWT, so the tests carry a real token rather than mocking the filter
     * away — a mocked filter would not prove the endpoint is protected at all.
     */
    @BeforeEach
    void signIn() {
        String username = "plantest-" + System.nanoTime();
        org.example.commonmanager.model.User u = new org.example.commonmanager.model.User();
        u.setUsername(username);
        u.setEmail(username + "@test.local");
        u.setPassword("irrelevant-for-this-test");
        u.setRole(org.example.commonmanager.model.UserRole.REGULAR);
        users.save(u);
        bearer = "Bearer " + io.jsonwebtoken.Jwts.builder()
                .subject(username)
                .claim("role", "USER")
                .issuedAt(new java.util.Date())
                .expiration(new java.util.Date(System.currentTimeMillis() + 3600_000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        "VeljaTestSecretKeyVeryLongAndSecure12345678901234567890"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .compact();
    }

    /** A fixture kicking off far enough ahead that the plan is still open. */
    private MatchFixture aFixture(LocalDateTime kickoff) {
        Team home = new Team();
        home.setName("Plan Home " + System.nanoTime());
        Team away = new Team();
        away.setName("Plan Away " + System.nanoTime());
        Team savedHome = teams.save(home);
        Team savedAway = teams.save(away);

        Competition competition = new Competition();
        competition.setName("Plan League " + System.nanoTime());
        competition.setType(org.example.footballmanager.newLogic.model.CompetitionType.LEAGUE);
        competition = competitions.save(competition);

        MatchFixture fixture = new MatchFixture();
        fixture.setHomeTeam(savedHome);
        fixture.setAwayTeam(savedAway);
        fixture.setCompetition(competition);
        fixture.setSeasonYear(1);
        fixture.setWeekNumber(1);
        fixture.setDayNumber(3);
        fixture.setMatchDate(kickoff);
        return fixtures.save(fixture);
    }

    @Test
    @DisplayName("a plan is written before the match exists, which is the point of the key")
    void planExistsBeforeAnyMatch() throws Exception {
        // No Match row is created anywhere in this test. Under the old matchId key this request had
        // nowhere to go, because a Match is written by the simulation that consumes the plan.
        Long fixtureId = aFixture(LocalDateTime.now().plusDays(3)).getId();

        mvc.perform(put("/api/sim/fixtures/" + fixtureId + "/substitution-plan")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rules\":\"[{\\\"team\\\":\\\"HOME\\\",\\\"triggerMinute\\\":60,"
                                + "\\\"condition\\\":\\\"LOSING\\\",\\\"playerOnId\\\":\\\"\\\"}]\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fixtureId").value(fixtureId));

        mvc.perform(get("/api/sim/fixtures/" + fixtureId + "/substitution-plan")
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rulesJson").value(
                        org.hamcrest.Matchers.containsString("LOSING")));
    }

    @Test
    @DisplayName("an unknown fixture is a 404, not a crash")
    void unknownFixture() throws Exception {
        mvc.perform(get("/api/sim/fixtures/999999/substitution-plan").header("Authorization", bearer))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("saving twice replaces the plan rather than merging it")
    void saveReplaces() throws Exception {
        Long fixtureId = aFixture(LocalDateTime.now().plusDays(3)).getId();

        mvc.perform(put("/api/sim/fixtures/" + fixtureId + "/substitution-plan")
                .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rules\":\"[A]\"}")).andExpect(status().isOk());
        mvc.perform(put("/api/sim/fixtures/" + fixtureId + "/substitution-plan")
                .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rules\":\"[B]\"}")).andExpect(status().isOk());

        mvc.perform(get("/api/sim/fixtures/" + fixtureId + "/substitution-plan")
                .header("Authorization", bearer))
                .andExpect(jsonPath("$.rulesJson").value("[B]"));
    }

    @Test
    @DisplayName("a fixture with no plan reads as an empty, editable plan")
    void noPlanYet() throws Exception {
        Long fixtureId = aFixture(LocalDateTime.now().plusDays(3)).getId();
        mvc.perform(get("/api/sim/fixtures/" + fixtureId + "/substitution-plan")
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rulesJson").value("[]"))
                .andExpect(jsonPath("$.editable").value(true));
    }

    @Test
    @DisplayName("an hour before kickoff the plan is closed, and the refusal says why")
    void closesAnHourBeforeKickoff() throws Exception {
        // 30 minutes out: inside the window. The refusal has to carry the reason, because a silent 400
        // is indistinguishable from a broken endpoint.
        Long fixtureId = aFixture(LocalDateTime.now().plusMinutes(30)).getId();

        mvc.perform(get("/api/sim/fixtures/" + fixtureId + "/substitution-plan")
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.editable").value(false));

        mvc.perform(put("/api/sim/fixtures/" + fixtureId + "/substitution-plan")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rules\":\"[A]\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("PLAN_CLOSED"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("already picked")));
    }

    @Test
    @DisplayName("clearing a plan removes it")
    void clearRemoves() throws Exception {
        Long fixtureId = aFixture(LocalDateTime.now().plusDays(3)).getId();
        mvc.perform(put("/api/sim/fixtures/" + fixtureId + "/substitution-plan")
                .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rules\":\"[A]\"}")).andExpect(status().isOk());

        mvc.perform(delete("/api/sim/fixtures/" + fixtureId + "/substitution-plan")
                        .header("Authorization", bearer))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/sim/fixtures/" + fixtureId + "/substitution-plan")
                        .header("Authorization", bearer))
                .andExpect(jsonPath("$.rulesJson").value("[]"));
    }
}