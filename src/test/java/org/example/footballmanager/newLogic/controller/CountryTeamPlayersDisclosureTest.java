package org.example.footballmanager.newLogic.controller;

import org.example.commonmanager.model.User;
import org.example.commonmanager.model.UserRole;
import org.example.commonmanager.util.JwtUtil;
import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.dto.PlayerDTO;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A club's players may be listed, but not fully disclosed to a stranger.
 *
 * <p>{@code GET /countries/teams/{teamId}/players} returned raw {@code Player} entities for <b>any</b>
 * {@code teamId}, with no ownership or country check. A raw entity carries {@code skills}, {@code talent},
 * {@code earnings}, the whole injury record and {@code personality} — so any logged-in manager could read
 * any rival's entire squad.
 *
 * <p>{@code talent} is the serious one. It is the number this codebase has {@code PlusFeatureService} built
 * to withhold: a scouting subscription pays for exactly this information. A raw entity gave it away on an
 * endpoint with no entitlement check at all.
 *
 * <p>The endpoint now does what {@code TeamController.getPlayers} does — the same DTO and the same
 * {@code PlusFeatureService} gate — so there is one rule for what a viewer may see about a player.
 */
class CountryTeamPlayersDisclosureTest extends BaseTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtUtil jwtUtil;

    @Autowired
    CountryRepository countries;

    @Autowired
    TeamRepository teams;

    private Team rivalClub;

    @BeforeEach
    @Transactional
    void aRivalClubWithASquad() {
        Country country = new Country();
        country.setName("Disclosure " + System.nanoTime());
        country.setIsoCode("DS" + (char) ('A' + Math.abs(System.nanoTime()) % 20));
        country.setState(CountryState.SIMULATED);
        country = countries.save(country);

        rivalClub = new Team();
        rivalClub.setName("Disclosure rival " + System.nanoTime());
        rivalClub.setCountry(country);
        rivalClub = teams.save(rivalClub);

        // A player with a talent worth hiding, an injury, and earnings.
        Player player = new Player();
        player.setName("Disclosure secret " + System.nanoTime());
        player.setTeam(rivalClub);
        player.setPosition(Position.ATT);
        player.setRating(80);
        player.setTalent(9.87);
        player.setEarnings(125_000);
        player.setInjured(true);
        player.setInjuryDaysRemaining(21);
        // PlayerDTO.from reads player.getSkills().getRatingScore(position) with no null check, and every
        // real player is given skills by the seeder - so a fixture without them is an incomplete fixture,
        // not a product bug. Noted because the NPE it produced looked exactly like one.
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
        player.setSkills(skills);
        playerRepository.save(player);
    }

    @Autowired
    org.example.footballmanager.newLogic.repository.PlayerRepository playerRepository;

    /**
     * The leak itself: a stranger's squad used to arrive with talent, earnings and injuries on it.
     */
    @Test
    @Transactional
    @DisplayName("a rival's squad is listed without talent, earnings, personality or raw skills")
    void aRivalsSquadDisclosesNothingSecret() throws Exception {
        String body = mockMvc.perform(get("/countries/teams/{teamId}/players", rivalClub.getId())
                        .header("Authorization", bearerFor(UserRole.REGULAR, null)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertNotNull(body);
        assertEquals(true, body.contains("Disclosure secret"),
                "the squad should still be listed: " + body);

        // **The value, not the key.** PlayerDTO always serialises "talent", and it travels as null for a
        // rival. An earlier version of this asserted the key was absent, which failed against correct code:
        // the shape of the DTO is a contract with the frontend and null is how it says "not yours".
        assertEquals(false, body.replace(" ", "").contains("\"talent\":9.87"),
                "a stranger can read a rival's exact scouting value: " + body);
        assertEquals(true, body.replace(" ", "").contains("\"talent\":null"),
                "the talent should be present and null for a rival, not omitted: " + body);
        assertEquals(false, body.contains("earnings"), "earnings should not be in a player DTO: " + body);
        assertEquals(false, body.contains("personality"), "personality should not be in a player DTO: " + body);
        assertEquals(false, body.contains("\"skills\""), "the raw skills object should not travel: " + body);

        // **Injury is deliberately NOT asserted, and that is a finding rather than an oversight.**
        //
        // PlayerDTO carries `injured` and `injuryDaysRemaining`, so this endpoint discloses them — and so
        // does TeamController.getPlayers, the sibling the board calls correct. The board lists injuries
        // among what C2 leaks, but does not say the correct sibling hides them.
        //
        // Both fields are read by the player's own profile page, so narrowing them means changing a shared
        // DTO with other consumers. Whether a rival may see that a player is out for three weeks is a
        // product decision, not a bug fix. **Reported to the owner, not decided here.**
    }

    /**
     * A manager's own club may see what they are entitled to, so the fix is narrowing and not a lockout.
     */
    @Test
    @Transactional
    @DisplayName("the owner still sees their own club")
    void theOwnerStillSeesTheirOwnClub() throws Exception {
        String body = mockMvc.perform(get("/countries/teams/{teamId}/players", rivalClub.getId())
                        .header("Authorization", bearerFor(UserRole.OWNER, rivalClub)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertEquals(true, body.contains("Disclosure secret"),
                "an administrator must still be able to read a squad: " + body);
    }

    @Test
    @Transactional
    @DisplayName("an anonymous caller gets no squad")
    void anAnonymousCallerIsRefused() throws Exception {
        // The security config sends an unauthenticated request to /login.html, so this is a 302 rather
        // than a 401. Asserting 401 was wrong: the important part is that no squad is disclosed, and the
        // security config already decides how to say no.
        int code = mockMvc.perform(get("/countries/teams/{teamId}/players", rivalClub.getId()))
                .andReturn().getResponse().getStatus();
        org.junit.jupiter.api.Assertions.assertNotEquals(200, code,
                "an anonymous caller was served the squad");
        org.junit.jupiter.api.Assertions.assertTrue(code == 302 || code == 401,
                "expected a redirect or a refusal, got " + code);
    }

    /**
     * The principal is built here rather than seeded, because the {@code test} profile runs on H2 with its
     * own data and a test that depends on a particular account passes on one machine and fails in CI.
     */
    private String bearerFor(UserRole role, Team managedTeam) {
        User user = new User();
        user.setUsername("disclosure-" + UUID.randomUUID() + "@test.local");
        user.setEmail(user.getUsername());
        user.setPassword("not-a-real-hash");
        user.setDisplayName("Disclosure tester");
        user.setRole(role);
        user.setPlusSubscription(false);
        if (managedTeam != null) {
            // PlusFeatureService.viewerTeamId falls back to finding a Team by the name on the user's
            // CTeam, so a CTeam carrying the club's name is enough to make the viewer its manager.
            org.example.footballtextmanager.model.CTeam cTeam =
                    new org.example.footballtextmanager.model.CTeam();
            cTeam.setName(managedTeam.getName());
            // It has to be saved: an unsaved transient instance fails the FK on flush and the request 401s
            // with "object references an unsaved transient instance", which reads like an auth problem and
            // is not one.
            cTeam = csTeams.save(cTeam);
            user.setCTeam(cTeam);
            user.setTifoCTeam(cTeam);
        }
        return "Bearer " + jwtUtil.generateToken(users.save(user));
    }

    @Autowired
    org.example.commonmanager.repository.UserRepository users;

    @Autowired
    org.example.footballtextmanager.repository.CSTeamRepository csTeams;
}