package org.example.footballmanager.newLogic.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.commonmanager.model.User;
import org.example.commonmanager.model.UserRole;
import org.example.commonmanager.repository.UserRepository;
import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.dto.LeagueTableDTO;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballtextmanager.model.CTeam;
import org.example.footballtextmanager.repository.CSTeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The owner asked for a path that did not exist: click a club, see who runs it, click him, see his profile.
 *
 * <p>Three surfaces carry the manager's name, and each is a different route with its own chance to
 * report a plausible wrong answer:
 *
 * <ul>
 *   <li>{@code GET /teams/{id}/profile} — the club profile</li>
 *   <li>{@code LeagueTableDTO} — the standings, which is where you actually meet other managers</li>
 *   <li>{@code GET /users/{id}/profile} — covered by {@code UserProfileControllerTest}</li>
 * </ul>
 *
 * <p><b>The columns are asserted rather than the JSON.</b> A standings row is built by positional
 * constructor call, and three fields appended to that record in the wrong place would put
 * {@code points} into {@code goalDifference} without any test noticing — a table that still looks like a
 * table and is quietly wrong. That is why {@code theStandingsColumnsDidNotShift} reads the record's
 * accessors rather than the body.
 */
@Import(ControllerAuthFixture.class)
class ManagerIsVisibleOnAClubTest extends BaseTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository users;

    @Autowired
    TeamRepository teams;

    @Autowired
    CSTeamRepository csTeams;

    @Autowired
    ControllerAuthFixture auth;

    // ── The club profile ────────────────────────────────────────────────────────────────────────────

    @Test
    @Transactional
    @DisplayName("a club profile names its manager and carries the id needed to open his profile")
    void aClubProfileNamesItsManager() throws Exception {
        Team club = auth.club("Managed");
        User manager = aManagerOf(club, "Velja");

        mockMvc.perform(get("/teams/{id}/profile", club.getId()).header("Authorization", auth.bearer(UserRole.OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managerUserId").value(manager.getId()))
                .andExpect(jsonPath("$.managerName").value("Velja"))
                .andExpect(jsonPath("$.managerHasChosenName").value(true));
    }

    @Test
    @Transactional
    @DisplayName("an AI-run club says so rather than showing an empty manager")
    void anAiRunClubSaysSo() throws Exception {
        Team bot = auth.club("Bot");
        bot.setHumanControlled(false);
        teams.save(bot);

        String body = mockMvc.perform(get("/teams/{id}/profile", bot.getId())
                        .header("Authorization", auth.bearer(UserRole.OWNER)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(!body.contains("managerUserId"),
                "an AI-run club reported a manager: " + body);
    }

    @Test
    @Transactional
    @DisplayName("the manager comes from the foreign key, not a stale club name")
    void theManagerComesFromTheForeignKeyAndNotTheName() throws Exception {
        // The regression this fixture exists for. The account's legacy CTeam points at a club name
        // that no longer exists, so any lookup that goes through the name-join returns nobody — and
        // the club profile renders with no manager at all, or worse, with somebody else's.
        Team club = auth.club("Renamed club");
        User manager = aManagerOf(club, "Velja");

        String body = mockMvc.perform(get("/teams/{id}/profile", club.getId())
                        .header("Authorization", auth.bearer(UserRole.OWNER)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertNotNull(manager);
        // Regex rather than `contains("\"managerUserId\":" + id)`: Jackson's pretty printer inserts
        // spaces around the colon, so a substring match would fail on a body that is entirely correct.
        // A body assertion has to be about the value, not about the serialiser's whitespace.
        assertTrue(body.matches("(?s).*\"managerUserId\"\\s*:\\s*" + manager.getId() + ".*"),
                "the club profile did not name its manager, because it followed the stale name: " + body);
    }

    @Test
    @Transactional
    @DisplayName("a manager who never chose a name shows his login, flagged so the page can say why")
    void anUnnamedManagerShowsHisLoginAndIsFlagged() throws Exception {
        Team club = auth.club("Unnamed");
        aManagerOf(club, null);

        mockMvc.perform(get("/teams/{id}/profile", club.getId()).header("Authorization", auth.bearer(UserRole.OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managerHasChosenName").value(false))
                .andExpect(jsonPath("$.managerName").value(org.hamcrest.Matchers.containsString("@")));
    }

    // ── The league table ───────────────────────────────────────────────────────────────────────────

    /**
     * The three manager fields were <b>appended</b> to a positional record, so every column has to be
     * checked.
     *
     * <p>{@code LeagueTableDTO} is constructed positionally in {@code CountryController}. Had the
     * fields been inserted after {@code points} rather than appended, the compiler would have been
     * perfectly happy, {@code points} would have arrived as {@code goalDifference}, and the table
     * would still have rendered — just wrong, in a way nobody reads a test for.
     */
    @Test
    @DisplayName("the standings columns did not shift when three manager fields were appended")
    void theStandingsColumnsDidNotShift() {
        LeagueTableDTO row = new LeagueTableDTO(
                7L,                    // teamId
                "Club",                 // name
                61,                     // points
                44,                     // goalsScored
                21,                     // goalsConceded
                23,                     // goalDifference
                19,                     // wins
                8,                      // draws
                3,                      // losses
                2,                      // position
                true,                   // humanControlled
                1540.0,                 // rating
                -3.0,                   // ratingDelta
                42L,                    // managerUserId
                "Velja",                // managerName
                true                    // managerHasChosenName
        );

        assertEquals(61, row.points());
        assertEquals(44, row.goalsScored());
        assertEquals(21, row.goalsConceded());
        assertEquals(23, row.goalDifference());
        assertEquals(19, row.wins());
        assertEquals(8, row.draws());
        assertEquals(3, row.losses());
        assertEquals(2, row.position());
        assertEquals(1540.0, row.rating());
        assertEquals(-3.0, row.ratingDelta());

        // And the new fields landed in the order they were appended.
        assertEquals(42L, row.managerUserId());
        assertEquals("Velja", row.managerName());
        assertEquals(Boolean.TRUE, row.managerHasChosenName());
    }

    @Test
    @DisplayName("a row with no manager is expressible, which a bot club requires")
    void aRowWithNoManagerIsExpressible() {
        LeagueTableDTO botRow = new LeagueTableDTO(
                8L, "Bot club", 10, 5, 5, 0, 3, 1, 2, 9, false, null, null, null, null, null);

        assertNull(botRow.managerUserId(), "'AI-run' must be expressible rather than a blank cell");
        assertNull(botRow.rating(), "never rated is a real state and must not be zero");
    }

    // ── Fixtures ───────────────────────────────────────────────────────────────────────────────────

    private User aManagerOf(Team club, String displayName) {
        User user = new User();
        user.setUsername("manager-" + java.util.UUID.randomUUID() + "@test.local");
        user.setEmail(user.getUsername());
        user.setPassword("not-a-real-hash");
        user.setDisplayName(displayName);
        user.setRole(UserRole.REGULAR);
        user.setPlusSubscription(false);
        user.setFootballTeam(club);

        // The legacy name-join too — but pointing at NOTHING.
        //
        // <p>This is the correction. The fixture originally set the legacy CTeam's name to the club's
        // real name, which meant a name-join and a foreign-key lookup returned the same answer and the
        // test could not tell which one the code used. Swapping the name-join for a direct
        // repository call left all five tests green; so did swapping it for the exact
        // `User.cTeam.name == Team.name` join that P0-18 was about.
        //
        // <p>Two sources that disagree is the only fixture that can tell them apart, so the legacy
        // name is deliberately wrong. A real account looks like this whenever a club is renamed, which
        // is the whole reason the FK exists.
        CTeam legacy = new CTeam();
        legacy.setName(club.getName() + "-renamed-ages-ago");
        user.setCTeam(csTeams.save(legacy));

        return users.save(user);
    }
}