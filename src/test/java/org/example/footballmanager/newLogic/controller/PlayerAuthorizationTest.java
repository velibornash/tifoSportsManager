package org.example.footballmanager.newLogic.controller;

import org.example.commonmanager.model.UserRole;
import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /players} — one route is gated correctly, one route leaks, and neither fact was under test.
 *
 * <p><b>The gate that works, asserted so it cannot rot.</b> {@code POST /players/create} is the only route on
 * this controller that states a rule, and the rule is right: minting a player at a chosen rating is an
 * administrator's action. The test below was proven able to fail by deleting the annotation — the request
 * answered 200 and the player was written.
 *
 * <p><b>The leak, which is a worse finding than a missing guard.</b> {@code GET /players/paged} returned
 * {@code Page<Player>} — the raw entity — for <b>every player in the world</b>, to any logged-in manager. A raw
 * {@code Player} carries {@code talent}, {@code earnings}, the whole injury record, {@code personality} and the
 * raw {@code skills} object. {@code talent} is the number this codebase built {@code PlusFeatureService} to
 * withhold: a scouting subscription pays for exactly that. The sibling endpoint was closed for precisely this
 * reason and given a test, and this one was missed because nobody called it.
 *
 * <p><b>It has no frontend caller.</b> {@code grep} over {@code static/js} finds no request to it, which is why
 * the leak has never been seen from the browser. That is recorded as a finding rather than used as an excuse:
 * unreachable is not the same as harmless, and the day something does call it the hole is live.
 */
@Import(ControllerAuthFixture.class)
class PlayerAuthorizationTest extends BaseTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ControllerAuthFixture auth;

    @Autowired
    PlayerRepository players;

    private Team aClub;

    @BeforeEach
    @Transactional
    void aClubToMintInto() {
        aClub = auth.club("Players");
    }

    // ── Anonymous ────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an anonymous caller cannot list players")
    void anAnonymousCallerCannotList() throws Exception {
        assertRefused(mockMvc.perform(get("/players")).andReturn().getResponse().getStatus());
    }

    @Test
    @DisplayName("an anonymous caller cannot read one player")
    void anAnonymousCallerCannotRead() throws Exception {
        assertRefused(mockMvc.perform(get("/players/{id}", 1L)).andReturn().getResponse().getStatus());
    }

    @Test
    @DisplayName("an anonymous caller cannot read the paged list")
    void anAnonymousCallerCannotReadPaged() throws Exception {
        assertRefused(mockMvc.perform(get("/players/paged")).andReturn().getResponse().getStatus());
    }

    @Test
    @DisplayName("an anonymous caller cannot mint a player")
    void anAnonymousCallerCannotCreate() throws Exception {
        assertRefused(mockMvc.perform(post("/players/create")
                        .param("teamId", String.valueOf(aClub.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Anonymous\",\"position\":\"ATT\"}"))
                .andReturn().getResponse().getStatus());
    }

    // ── The administrator gate ────────────────────────────────────────────────────────────────────────

    @Test
    @Transactional
    @DisplayName("a regular manager cannot mint a player")
    void aRegularManagerCannotCreate() throws Exception {
        long before = players.count();

        mockMvc.perform(post("/players/create")
                        .header("Authorization", auth.bearer(UserRole.REGULAR))
                        .param("teamId", String.valueOf(aClub.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Forged\",\"position\":\"ATT\"}"))
                .andExpect(status().isForbidden());

        assertEquals(before, players.count(), "a refused create still wrote a player");
    }

    @Test
    @Transactional
    @DisplayName("an administrator can mint a player, and the player exists afterwards")
    void anAdministratorCanCreate() throws Exception {
        mockMvc.perform(post("/players/create")
                        .header("Authorization", auth.bearer(UserRole.OWNER))
                        .param("teamId", String.valueOf(aClub.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Minted\",\"position\":\"ATT\"}"))
                .andExpect(status().isOk());

        assertTrue(players.findAll().stream().anyMatch(p -> "Minted".equals(p.getName())),
                "the create answered 200 and no player was written");
    }

    /**
     * The gate is a refusal, not a silent success — and it refuses for the stated reason rather than because
     * the body happened to be unusable. A test that posts a body the controller would reject anyway proves
     * nothing about the role check, so the body is deliberately valid.
     */
    @Test
    @Transactional
    @DisplayName("a refused create says why, and does not fall through to a validation error")
    void aRefusedCreateIsForbiddenRatherThanABadRequest() throws Exception {
        int code = mockMvc.perform(post("/players/create")
                        .header("Authorization", auth.bearer(UserRole.REGULAR))
                        .param("teamId", String.valueOf(aClub.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Forged\",\"position\":\"ATT\"}"))
                .andReturn().getResponse().getStatus();

        assertEquals(403, code,
                "403 is the authorization answer; 400 would mean the body was rejected instead of the caller");
    }

    // ── The disclosure hole ───────────────────────────────────────────────────────────────────────────

    @Test
    @Transactional
    @DisplayName("the paged list discloses no talent, earnings, personality or raw skills")
    void thePagedListDisclosesNothingSecret() throws Exception {
        Player secret = new Player();
        secret.setName("Paged secret " + System.nanoTime());
        secret.setTeam(aClub);
        secret.setPosition(Position.ATT);
        secret.setRating(80);
        secret.setTalent(9.87);
        secret.setEarnings(125_000);
        secret.setInjured(true);
        secret.setInjuryDaysRemaining(21);
        // PlayerDTO.from reads player.getSkills().getRatingScore(position) with no null check and the seeder
        // always supplies skills, so a fixture without them is an incomplete fixture rather than a product
        // bug. The NPE this produced looked exactly like one, which is worth writing down.
        secret.setSkills(skills());
        players.save(secret);

        String body = mockMvc.perform(get("/players/paged")
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, aClub)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String tight = body.replace(" ", "");

        // **The value, not the key.** A DTO always serialises the field and travels null for a rival, so a
        // test asserting the key is absent fails against correct code.
        assertFalse(tight.contains("\"talent\":9.87"),
                "a scouting value is readable straight off the paged list: " + body);
        assertTrue(tight.contains("\"talent\":null"),
                "talent should be present and null, not omitted: " + body);
        assertFalse(body.contains("earnings"), "earnings should not travel in a player list: " + body);
        assertFalse(body.contains("personality"), "personality should not travel in a player list: " + body);
        assertFalse(tight.contains("\"skills\""), "the raw skills object should not travel: " + body);
    }

    @Test
    @DisplayName("a manager can still list players")
    void aManagerCanStillList() throws Exception {
        mockMvc.perform(get("/players")
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, aClub)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a manager can still read the paged list")
    void aManagerCanStillReadPaged() throws Exception {
        mockMvc.perform(get("/players/paged")
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, aClub)))
                .andExpect(status().isOk());
    }

    private static org.example.footballmanager.newLogic.model.Skills skills() {
        org.example.footballmanager.newLogic.model.Skills skills =
                new org.example.footballmanager.newLogic.model.Skills();
        skills.setSkill(org.example.footballmanager.newLogic.model.SkillName.PACE, 70);
        skills.setSkill(org.example.footballmanager.newLogic.model.SkillName.STRIKER, 75);
        skills.setSkill(org.example.footballmanager.newLogic.model.SkillName.PASSING, 68);
        skills.setSkill(org.example.footballmanager.newLogic.model.SkillName.TECHNIQUE, 72);
        skills.setSkill(org.example.footballmanager.newLogic.model.SkillName.DEFENDER, 40);
        skills.setSkill(org.example.footballmanager.newLogic.model.SkillName.STAMINA, 66);
        skills.setSkill(org.example.footballmanager.newLogic.model.SkillName.PLAYMAKER, 60);
        skills.setSkill(org.example.footballmanager.newLogic.model.SkillName.GOALKEEPER, 5);
        return skills;
    }

    private static void assertRefused(int code) {
        assertNotNull(code);
        assertTrue(code == 401 || code == 403 || code == 302,
                "expected a refusal, got " + code);
        assertTrue(code != 200, "an anonymous caller was served the players");
    }
}