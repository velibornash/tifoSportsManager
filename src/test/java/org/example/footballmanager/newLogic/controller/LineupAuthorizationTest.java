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
 * Squad sheets are read-only here.
 *
 * <p><b>This controller used to be writable and wide open</b> — four mappings, not one guard, so any
 * logged-in manager could file a lineup onto any club in the world and delete any lineup he could name an id
 * for. P0-1a closed that, and in doing so found that {@code POST /lineups} had <b>never accepted a request
 * body at all</b>: it took the raw {@code Lineup} entity, which Jackson cannot deserialise, so every caller
 * got {@code HttpMediaTypeNotSupportedException} before the controller was entered.
 *
 * <p><b>Both writes are now deleted</b>, on the owner's decision. Neither had a frontend caller: the game
 * files a squad sheet through {@code TeamController}'s {@code lineup-template}, so the duplication was the
 * problem rather than the routes' existence. The reads stay, because they are reached.
 *
 * <p>What remains is therefore the smaller half of the original surface, and it is asserted as before —
 * anonymous callers refused, and a manager can read. The guarantee that survives is the one that was always
 * true: <b>who is in a rival's eleven is a league-table fact</b>, so reads are open to any manager.
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



    // ── The hole: a manager rewriting and deleting a rival's lineup ───────────────────────────────────



    // ── The guard is a narrowing, not a lockout ───────────────────────────────────────────────────────


    @Test
    @DisplayName("a manager can still read lineups")
    void aManagerCanStillRead() throws Exception {
        mockMvc.perform(get("/lineups")
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub)))
                .andExpect(status().isOk());
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