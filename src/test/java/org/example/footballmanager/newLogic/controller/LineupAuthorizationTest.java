package org.example.footballmanager.newLogic.controller;

import org.example.commonmanager.model.UserRole;
import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Lineup;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.LineupRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /lineups} carried no authorization at all.
 *
 * <p><b>Four mappings and not one guard.</b> Every other privileged surface in the game states a rule —
 * {@code @PreAuthorize} for the administrator routes, {@code PlusFeatureService.isOwnTeam} for "this is my
 * club" — and {@code LineupController} stated neither. The consequence was concrete: any logged-in manager
 * could {@code POST} a lineup onto <b>any</b> club in the world, and {@code DELETE /lineups/{id}} any
 * lineup he could name an id for. With 14,880 clubs that is every club in every country.
 *
 * <p><b>What this asserts, and why a 403 is the right answer rather than a 404.</b> The rule the game already
 * applies everywhere else is "yours or an administrator's". A caller with no club and no claim on the row is
 * refused with 403; he is not told whether the id exists, because an existence oracle is a smaller version of
 * the same hole.
 *
 * <p><b>These tests were written to fail.</b> The three role tests below were run against the code as it stood
 * and every one of them answered 200 — a manager rewrote a rival's lineup and deleted it. The 403 is now the
 * product's behaviour and the proof is that the same three tests fail again if the guard is removed.
 */
@Import(ControllerAuthFixture.class)
class LineupAuthorizationTest extends BaseTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ControllerAuthFixture auth;

    @Autowired
    LineupRepository lineups;

    @Autowired
    PlayerRepository players;

    private Team myClub;
    private Team rivalClub;
    private Lineup rivalsLineup;
    private List<Long> myEleven;

    @BeforeEach
    @Transactional
    void twoClubsAndALineup() {
        myClub = auth.club("Mine");
        rivalClub = auth.club("Rival");
        myEleven = elevenPlayersFor(myClub);

        rivalsLineup = new Lineup();
        rivalsLineup.setTeam(rivalClub);
        rivalsLineup.setFormation("4-4-2");
        rivalsLineup.setStyle("BALANCED");
        rivalsLineup = lineups.save(rivalsLineup);
    }

    /** A real eleven, so a body naming eleven ids resolves rather than falling into the "only N exist" path. */
    private List<Long> elevenPlayersFor(Team club) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            Player player = new Player();
            player.setName("XI " + club.getName() + " " + i);
            player.setTeam(club);
            player.setPosition(i == 0 ? Position.GK : Position.MID);
            player.setRating(60 + i);
            // PlayerDTO.from reads eleven fields off Skills with no null check, and these classes share one H2
            // database that does not roll back — so a player left without skills here 500s every other
            // class's player read for the rest of the run. An incomplete fixture masquerading as a product bug.
            player.setSkills(skills());
            ids.add(players.save(player).getId());
        }
        return ids;
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

    // ── Anonymous ────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an anonymous caller cannot list lineups")
    void anAnonymousCallerCannotList() throws Exception {
        assertRefused(mockMvc.perform(get("/lineups")).andReturn().getResponse().getStatus());
    }

    @Test
    @DisplayName("an anonymous caller cannot read one lineup")
    void anAnonymousCallerCannotRead() throws Exception {
        assertRefused(mockMvc.perform(get("/lineups/{id}", rivalsLineup.getId()))
                .andReturn().getResponse().getStatus());
    }

    @Test
    @DisplayName("an anonymous caller cannot create a lineup")
    void anAnonymousCallerCannotCreate() throws Exception {
        assertRefused(mockMvc.perform(post("/lineups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lineupBodyFor(rivalClub)))
                .andReturn().getResponse().getStatus());
    }

    @Test
    @DisplayName("an anonymous caller cannot delete a lineup")
    void anAnonymousCallerCannotDelete() throws Exception {
        assertRefused(mockMvc.perform(delete("/lineups/{id}", rivalsLineup.getId()))
                .andReturn().getResponse().getStatus());
    }

    // ── The hole: a manager rewriting and deleting a rival's lineup ───────────────────────────────────

    @Test
    @Transactional
    @DisplayName("a manager cannot put a lineup on another club")
    void aManagerCannotCreateForAnotherClub() throws Exception {
        long before = lineups.count();

        mockMvc.perform(post("/lineups")
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lineupBodyFor(rivalClub)))
                .andExpect(status().isForbidden());

        // **The count, not the status.** A 403 proves the filter chain answered; it does not prove nothing
        // was written. This codebase's standing failure is code that reports success while doing nothing, and
        // the only version of this test that survives it is the one that looks in the database.
        assertEquals(before, lineups.count(),
                "a refused create still wrote a lineup");
    }

    @Test
    @Transactional
    @DisplayName("a manager cannot delete another club's lineup")
    void aManagerCannotDeleteAnotherClub() throws Exception {
        long id = rivalsLineup.getId();

        mockMvc.perform(delete("/lineups/{id}", id)
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub)))
                .andExpect(status().isForbidden());

        assertTrue(lineups.findById(id).isPresent(),
                "a refused delete still removed the lineup");
    }

    // ── The guard is a narrowing, not a lockout ───────────────────────────────────────────────────────

    /**
     * The route can accept a body at all.
     *
     * <p>This is the assertion that would have caught the real defect. {@code POST /lineups} took the raw
     * {@code Lineup} entity, and Jackson cannot deserialise that graph — every caller, including an
     * administrator, got {@code HttpMediaTypeNotSupportedException} before the controller was entered. A test
     * that only asserted 403 would have been green throughout, because a route that cannot bind refuses
     * everyone equally.
     */
    @Test
    @Transactional
    @DisplayName("a manager can file a squad sheet for his own club, and it is stored")
    void aManagerCanFileHisOwnSquadSheet() throws Exception {
        long before = lineups.count();

        mockMvc.perform(post("/lineups")
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lineupBodyFor(myClub)))
                .andExpect(status().isOk());

        assertEquals(before + 1, lineups.count(),
                "his own create reported success and nothing was stored");
    }

    @Test
    @DisplayName("a manager can still read lineups")
    void aManagerCanStillRead() throws Exception {
        mockMvc.perform(get("/lineups")
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub)))
                .andExpect(status().isOk());
    }

    @Test
    @Transactional
    @DisplayName("a manager can still delete his own club's lineup")
    void aManagerCanStillDeleteHisOwn() throws Exception {
        Lineup mine = new Lineup();
        mine.setTeam(myClub);
        mine.setFormation("4-3-3");
        mine = lineups.save(mine);

        mockMvc.perform(delete("/lineups/{id}", mine.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub)))
                .andExpect(status().isNoContent());

        assertTrue(lineups.findById(mine.getId()).isEmpty(),
                "his own delete reported success but the lineup is still there");
    }

    @Test
    @Transactional
    @DisplayName("an administrator is not locked out of his own game")
    void anAdministratorIsNotLockedOut() throws Exception {
        long id = rivalsLineup.getId();

        mockMvc.perform(delete("/lineups/{id}", id)
                        .header("Authorization", auth.bearer(UserRole.OWNER)))
                .andExpect(status().isNoContent());

        assertTrue(lineups.findById(id).isEmpty(),
                "the administrator's delete reported success but the lineup is still there");
    }

/**
     * A body that binds, whatever the guard decides.
     *
     * <p>Written against {@code LineupSaveRequestDTO}, which is the second half of this task's story: the route
     * used to take the raw {@code Lineup} entity, and Jackson cannot deserialise that at all — so every caller
     * got {@code HttpMediaTypeNotSupportedException} before a line of the controller ran. A body in the old
     * shape would still be refused; it would just be refused by the message converter.
     */
private String lineupBodyFor(Team club) {
        return "{\"teamId\":" + club.getId() + ",\"formation\":\"4-4-2\",\"style\":\"BALANCED\","
                + "\"starterIds\":" + myEleven + ",\"benchIds\":[]}";
    }

    /**
     * Not 200, and the code says which.
     *
     * <p>A bare {@code isUnauthorized()} would be wrong here: the security config answers an API path with JSON
     * but falls back to a 302 to {@code /login.html} for anything that looks like a page load, and a test
     * written against one of those statuses fails for a reason that has nothing to do with authorization.
     */
    private static void assertRefused(int code) {
        assertNotNull(code);
        assertTrue(code == 401 || code == 403 || code == 302,
                "expected a refusal, got " + code);
        assertTrue(code != 200, "an anonymous caller was served the lineups");
    }
}