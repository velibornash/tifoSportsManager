package org.example.footballmanager.newLogic.sim.controller;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
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
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A substitution rule that cannot fire is refused at save time, with the reason. (T1-16.)
 *
 * <p><b>The defect.</b> {@code PUT /api/sim/fixtures/{id}/substitution-plan} did
 * {@code String.valueOf(rules)} and saved it. The screen's dropdowns mean the UI cannot name somebody
 * who is not in the squad, but <b>the API can</b>, and it accepted anything.
 *
 * <p><b>Where the mistake used to surface, which is nowhere.</b> The engine catches it —
 * {@code ConditionalSubstitutionRules} marks the rule {@code VOID} with a {@code VoidReason} — but
 * that happens <b>during the match</b>, and nothing reads {@code voidReason} back. So a typo became a
 * silently dead instruction: the manager believed he had told his coach to bring on a player, the rule
 * sat there never firing, and the only evidence was a field in a database row nobody displays.
 *
 * <p><b>What stays legal.</b> An empty {@code playerOnId} or {@code playerOffId} means "let the engine
 * choose", which is a real instruction and the feature's main use. Refusing those would break it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.test.context.ActiveProfiles("test")
class SubstitutionRuleIsRefusedWhenItCannotFireTest {

    @Autowired MockMvc mvc;
    @Autowired TeamRepository teams;
    @Autowired PlayerRepository players;
    @Autowired CompetitionRepository competitions;
    @Autowired MatchFixtureRepository fixtures;
    @Autowired org.example.commonmanager.repository.UserRepository users;
    @Autowired org.example.footballmanager.newLogic.repository.SubstitutionPlanRepository substitutionPlans;

    private String bearer;
    private Team home;
    private Team away;
    private Player homePlayer;

    @BeforeEach
    void setUp() {
        String username = "subrule-" + System.nanoTime();
        org.example.commonmanager.model.User u = new org.example.commonmanager.model.User();
        u.setUsername(username);
        u.setEmail(username + "@test.local");
        u.setPassword("irrelevant");
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

        home = teams.save(namedTeam("Rule Home"));
        away = teams.save(namedTeam("Rule Away"));

        homePlayer = new Player();
        homePlayer.setName("Real Squad Player");
        homePlayer.setTeam(home);
        homePlayer.setPosition(org.example.footballmanager.newLogic.model.Position.DEF);
        homePlayer = players.save(homePlayer);
    }

    private Team namedTeam(String name) {
        Team t = new Team();
        t.setName(name + " " + System.nanoTime());
        return t;
    }

    private MatchFixture aFixture() {
        Competition competition = new Competition();
        competition.setName("Rule League " + System.nanoTime());
        competition.setType(CompetitionType.LEAGUE);
        MatchFixture f = new MatchFixture();
        f.setHomeTeam(home);
        f.setAwayTeam(away);
        f.setCompetition(competitions.save(competition));
        f.setSeasonYear(1);
        f.setWeekNumber(1);
        f.setDayNumber(3);
        f.setMatchDate(LocalDateTime.now().plusDays(3));
        return fixtures.save(f);
    }

    private org.springframework.test.web.servlet.ResultActions save(Long fixtureId, String rulesJson)
            throws Exception {
        return mvc.perform(put("/api/sim/fixtures/" + fixtureId + "/substitution-plan")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rules\":" + quote(rulesJson) + "}"));
    }

    private String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ── the exit criterion the board names verbatim ──────────────────────────────────────────────

    @Test
    @DisplayName("posting an unknown playerOnId returns 400 and not 200")
    void unknownPlayerOnIdIsRefused() throws Exception {
        Long fixtureId = aFixture().getId();

        save(fixtureId, "[{\"team\":\"HOME\",\"triggerMinute\":60,\"condition\":\"LOSING\","
                + "\"playerOnId\":\"99999999\"}]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("RULE_CANNOT_FIRE"))
                .andExpect(jsonPath("$.rejections[0].code").value("PLAYER_NOT_IN_SQUAD"));

        // And nothing was written — a refused plan must not leave a half-saved one behind.
        mvc.perform(get("/api/sim/fixtures/" + fixtureId + "/substitution-plan")
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rulesJson").value("[]"));
    }

    @Test
    @DisplayName("a rule naming a player who is not in the squad is refused with the reason")
    void theReasonIsSentBack() throws Exception {
        Long fixtureId = aFixture().getId();

        save(fixtureId, "[{\"team\":\"HOME\",\"triggerMinute\":60,\"condition\":\"LOSING\","
                + "\"playerOnId\":\"99999999\"}]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.rejections[0].message").value(
                        org.hamcrest.Matchers.containsString("not in the")));
    }

    // ── and the valid plans must still work ────────────────────────────────────────────────────────

    @Test
    @DisplayName("a rule naming a real squad player is accepted")
    void realPlayerIsAccepted() throws Exception {
        Long fixtureId = aFixture().getId();

        save(fixtureId, "[{\"team\":\"HOME\",\"triggerMinute\":60,\"condition\":\"LOSING\","
                + "\"playerOnId\":\"" + homePlayer.getId() + "\"}]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fixtureId").value(fixtureId));
    }

    @Test
    @DisplayName("empty player ids mean 'the engine chooses', and stay legal")
    void engineChosenPlayerStaysLegal() throws Exception {
        Long fixtureId = aFixture().getId();

        // This is the feature's main use and the board says explicitly it is legal. Refusing it would
        // be "fixing" the endpoint into uselessness.
        save(fixtureId, "[{\"team\":\"HOME\",\"triggerMinute\":60,\"condition\":\"LOSING\","
                + "\"playerOnId\":\"\",\"playerOffId\":\"\"}]")
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the team is the side HOME or AWAY, exactly as the engine reads it")
    void sidesAreHomeAndAway() throws Exception {
        Long fixtureId = aFixture().getId();

        // Nearly shipped wrong: an earlier version of the validator matched `team` against the club
        // NAMES and would have rejected every plan the UI can produce. `substitution-plan-view.js`
        // sends team: 'HOME' and `ConditionalSubstitutionRules.scoreFor` compares against those
        // literals, so this is the only shape that works.
        save(fixtureId, "[{\"team\":\"AWAY\",\"triggerMinute\":70,\"condition\":\"LEADING\","
                + "\"playerOnId\":\"\"}]")
                .andExpect(status().isOk());

        save(fixtureId, "[{\"team\":\"NEUTRAL\",\"triggerMinute\":70,\"condition\":\"ANYTIME\","
                + "\"playerOnId\":\"\"}]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.rejections[0].code").value("UNKNOWN_TEAM"));
    }

    // ── the other conditions the board lists ───────────────────────────────────────────────────────

    @Test
    @DisplayName("a condition the engine has never heard of is refused rather than read as ANYTIME")
    void unknownConditionIsRefused() throws Exception {
        Long fixtureId = aFixture().getId();

        // The engine's switch ends in `default -> true`, so an unknown condition silently becomes
        // ANYTIME. A rule that says what it wants must not be reinterpreted into something else.
        save(fixtureId, "[{\"team\":\"HOME\",\"triggerMinute\":60,\"condition\":\"WHEN_I_FEEL_LIKE_IT\","
                + "\"playerOnId\":\"\"}]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.rejections[0].code").value("UNKNOWN_CONDITION"));
    }

    @Test
    @DisplayName("a minute past the end of the match is refused")
    void minuteOutOfRangeIsRefused() throws Exception {
        Long fixtureId = aFixture().getId();

        save(fixtureId, "[{\"team\":\"HOME\",\"triggerMinute\":95,\"condition\":\"ANYTIME\","
                + "\"playerOnId\":\"\"}]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.rejections[0].code").value("MINUTE_OUT_OF_RANGE"));
    }

    @Test
    @DisplayName("every offending rule is reported, not just the first")
    void allRejectionsComeBack() throws Exception {
        Long fixtureId = aFixture().getId();

        // A manager fixing one typo at a time through a form that kept accepting it is the experience
        // this task exists to end.
        save(fixtureId, "[{\"team\":\"NEUTRAL\",\"triggerMinute\":60,\"condition\":\"LOSING\","
                + "\"playerOnId\":\"\"},"
                + "{\"team\":\"HOME\",\"triggerMinute\":95,\"condition\":\"ANYTIME\",\"playerOnId\":\"\"}]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.rejections.length()").value(2));
    }

    @Test
    @DisplayName("an empty plan is still legal, because clearing is not a mistake")
    void emptyPlanIsLegal() throws Exception {
        Long fixtureId = aFixture().getId();
        save(fixtureId, "[]").andExpect(status().isOk());
    }

    // ── the other half: what happened to the rules afterwards ──────────────────────────────────────

    @Test
    @DisplayName("an unplayed fixture reports no outcome, which is honest rather than empty")
    void noOutcomeBeforeTheMatch() throws Exception {
        Long fixtureId = aFixture().getId();
        save(fixtureId, "[{\"team\":\"HOME\",\"triggerMinute\":60,\"condition\":\"LOSING\","
                + "\"playerOnId\":\"\"}]").andExpect(status().isOk());

        mvc.perform(get("/api/sim/fixtures/" + fixtureId + "/substitution-plan")
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                // Nothing is known about how an instruction went until there is a match for it to have
                // gone in. An empty list here would read as "your rules all worked".
                .andExpect(jsonPath("$.outcomeJson").doesNotExist());
    }

    @Test
    @DisplayName("a recorded outcome is returned verbatim, so the screen can show fired or void")
    void aRecordedOutcomeIsVisible() throws Exception {
        Long fixtureId = aFixture().getId();
        save(fixtureId, "[{\"team\":\"HOME\",\"triggerMinute\":60,\"condition\":\"LOSING\","
                + "\"playerOnId\":\"\"}]").andExpect(status().isOk());

        // Stand in for what the simulation writes at the final whistle: the engine assigns each rule a
        // VoidReason when it cannot fire, and this is the first time anything has read it back.
        String outcome = "[{\"team\":\"HOME\",\"triggerMinute\":60,\"condition\":\"LOSING\","
                + "\"status\":\"VOID\",\"voidReason\":\"NO_SUBS_LEFT\",\"firedAtMinute\":-1}]";
        var plan = substitutionPlans.findByFixtureId(fixtureId).orElseThrow();
        plan.setOutcomeJson(outcome);
        substitutionPlans.save(plan);

        mvc.perform(get("/api/sim/fixtures/" + fixtureId + "/substitution-plan")
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcomeJson").value(
                        org.hamcrest.Matchers.containsString("NO_SUBS_LEFT")));
    }

    @Test
    @DisplayName("a plan that is not a list at all is refused rather than stored as text")
    void unreadablePlanIsRefused() throws Exception {
        Long fixtureId = aFixture().getId();

        // The old code did String.valueOf(rules) and stored whatever came in, so this was a 200 with
        // a plan the engine could never parse.
        save(fixtureId, "{\"not\":\"a list\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("PLAN_UNREADABLE"));
    }
}