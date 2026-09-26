package org.example.footballmanager.newLogic.sim.controller;

import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Conditional substitution plan persistence.
 *
 * <p>The plan has to be server-side and keyed by match. Holding it in the browser means a reload at
 * minute 55 silently loses every rule set at minute 0, and the rules then appear to fire at random.
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.test.context.ActiveProfiles("test")
class SubstitutionPlanControllerTest {

    @Autowired MockMvc mvc;
    @Autowired MatchRepository matches;
    @Autowired TeamRepository teams;
    @Autowired org.example.commonmanager.repository.UserRepository users;

    /**
     * Every game endpoint sits behind JWT (S0.7), so the tests carry a real token rather than
     * bypassing security. spring-security-test is not on the classpath, and a test that mocked the
     * filter away would not prove the endpoint is actually protected.
     */
    /**
     * The filter resolves the token's subject through UserDetailsService, so a token for a user who
     * is not in the database is rejected even when the signature is valid. Register one first.
     */
    private String bearer;

    private String registerUser() {
        String username = "plan-tester-" + System.nanoTime();
        org.example.commonmanager.model.User u = new org.example.commonmanager.model.User();
        u.setUsername(username);
        u.setEmail(username + "@test.local");
        u.setPassword("irrelevant-for-this-test");
        u.setRole(org.example.commonmanager.model.UserRole.REGULAR);
        users.save(u);
        return "Bearer " + io.jsonwebtoken.Jwts.builder()
            .subject(username)
            .claim("role", "USER")
            .issuedAt(new java.util.Date())
            .expiration(new java.util.Date(System.currentTimeMillis() + 3600_000))
            .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                    "VeljaTestSecretKeyVeryLongAndSecure12345678901234567890"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .compact();
    }

    @org.junit.jupiter.api.BeforeEach
    void signIn() {
        bearer = registerUser();
    }

    private Long aMatch() {
        Team home = new Team();
        home.setName("Plan Home " + System.nanoTime());
        Team away = new Team();
        away.setName("Plan Away " + System.nanoTime());
        // Both sides must be persisted before the Match references them.
        Team savedHome = teams.save(home);
        Team savedAway = teams.save(away);
        Match m = new Match();
        m.setHomeTeam(savedHome);
        m.setAwayTeam(savedAway);
        return matches.save(m).getId();
    }

    @Test
    @DisplayName("an unknown match is a 404, not a crash")
    void unknownMatch() throws Exception {
        mvc.perform(get("/api/sim/matches/999999/substitution-plan").header("Authorization", bearer))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a plan survives being read back - the reason it is server-side")
    void planRoundTrips() throws Exception {
        Long id = aMatch();

        mvc.perform(put("/api/sim/matches/" + id + "/substitution-plan").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rules\":\"[{\\\"team\\\":\\\"HOME\\\",\\\"triggerMinute\\\":60,"
                                + "\\\"condition\\\":\\\"LOSING\\\"}]\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchId").value(id));

        // A different request, as a page reload would be.
        mvc.perform(get("/api/sim/matches/" + id + "/substitution-plan").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rulesJson").value(
                        org.hamcrest.Matchers.containsString("LOSING")));
    }

    @Test
    @DisplayName("saving twice replaces the plan rather than merging it")
    void saveReplaces() throws Exception {
        Long id = aMatch();

        mvc.perform(put("/api/sim/matches/" + id + "/substitution-plan").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rules\":\"[A]\"}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/sim/matches/" + id + "/substitution-plan").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rules\":\"[B]\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/sim/matches/" + id + "/substitution-plan").header("Authorization", bearer))
                .andExpect(jsonPath("$.rulesJson").value("[B]"));
    }

    @Test
    @DisplayName("a match with no plan reads as an empty plan, not an error")
    void noPlanYet() throws Exception {
        mvc.perform(get("/api/sim/matches/" + aMatch() + "/substitution-plan")
                .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rulesJson").value("[]"));
    }
}
