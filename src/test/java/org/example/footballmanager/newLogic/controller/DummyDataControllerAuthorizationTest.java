package org.example.footballmanager.newLogic.controller;

import org.example.commonmanager.model.UserRole;
import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Eighteen fabricated routes, and the finding is about the callers rather than the routes.
 *
 * <p>{@code /demo} is hardcoded fake data — every path carries a literal {@code 1}, there is no
 * {@code @PathVariable} anywhere in the class, and nothing touches the database. The board records it as
 * "fake data, hardcoded to team 1, awaiting an owner decision. Do not wire it to anything."
 *
 * <p><b>So the routes are all fine and the callers are not.</b> Five frontend files still fetch
 * {@code /demo/teams/${teamId}/profile} and friends, and because the {@code 1} is a literal in the mapping
 * rather than a variable, that request <b>404s for every club except team 1</b>. A manager opening his own
 * club page gets nothing; a manager whose club happens to be id 1 gets a fabricated profile presented as
 * his own. Both are consequences of the same missing variable, and neither is visible from the controller.
 *
 * <p><b>Authentication is asserted because it is the one property of this surface that is real.</b>
 * {@code /demo/service/ui/**} is on the permit list; the JSON tree is not, so every route here needs a token.
 * Nothing tested that, and {@code DummyDataController} had no test at all.
 *
 * <p><b>The 404 asymmetry is not fixed here.</b> Wiring this to the database is exactly what the board
 * forbids, and deleting the routes would break five pages. It is recorded as P0-16 for the owner.
 */
@Import(ControllerAuthFixture.class)
class DummyDataControllerAuthorizationTest extends BaseTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ControllerAuthFixture auth;

    private Team myClub;

    @BeforeEach
    @Transactional
    void aClubThatIsNotTeamOne() {
        // Deliberately not id 1. The asymmetry below only means anything if the caller's own club is a
        // different club from the hardcoded one.
        myClub = auth.club("Real");
    }

    // ── The whole tree needs a token ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an anonymous caller cannot read the fabricated team profile")
    void anAnonymousCallerCannotReadTheProfile() throws Exception {
        assertRefused(mockMvc.perform(get("/demo/teams/1/profile")).andReturn().getResponse().getStatus());
    }

    @Test
    @DisplayName("an anonymous caller cannot read the fabricated league table")
    void anAnonymousCallerCannotReadTheTable() throws Exception {
        assertRefused(mockMvc.perform(get("/demo/leagues/1/table")).andReturn().getResponse().getStatus());
    }

    @Test
    @DisplayName("an anonymous caller cannot read the fabricated fixtures")
    void anAnonymousCallerCannotReadFixtures() throws Exception {
        assertRefused(mockMvc.perform(get("/demo/matches/teams/1/upcoming"))
                .andReturn().getResponse().getStatus());
    }

    @Test
    @DisplayName("an anonymous caller cannot read the fabricated squad")
    void anAnonymousCallerCannotReadTheSquad() throws Exception {
        assertRefused(mockMvc.perform(get("/demo/stats/teams/1/players"))
                .andReturn().getResponse().getStatus());
    }

    // ── It is fabricated, and it says so ──────────────────────────────────────────────────────────────

    /**
     * The body is a constant, and that is the honest property to pin.
     *
     * <p>Not a security assertion — a <i>truthfulness</i> one. If a future change made this route read the
     * database, the frontend would stop showing a manager somebody else's club, which would be an
     * improvement; but it would also mean the board's "do not wire it to anything" had been quietly undone
     * by whoever made the change, and nothing else would say so.
     */
    @Test
    @DisplayName("the profile is a constant, not a real club")
    void theProfileIsFabricated() throws Exception {
        String body = mockMvc.perform(get("/demo/teams/1/profile")
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("Omladinac FC"),
                "this surface is documented as hardcoded fake data, so its body should be the constant");
        assertTrue(!body.contains(myClub.getName()),
                "a fabricated route answered with a real club's name, which means it has been wired up");
    }

    /**
     * The reason the five frontend callers are broken, pinned.
     *
     * <p>{@code /demo/teams/1/profile} has a literal {@code 1} in its mapping and no {@code @PathVariable},
     * so the caller's own club id is not in the URL that reaches it. Five files call
     * {@code /demo/teams/${teamId}/profile}; every one of them but team 1 gets a 404 and renders nothing.
     */
    @Test
    @DisplayName("the club id in the path is ignored, so any club but team 1 gets nothing")
    void theClubIdInThePathIsIgnored() throws Exception {
        String mine = auth.bearerManaging(UserRole.REGULAR, myClub);

        int teamOne = mockMvc.perform(get("/demo/teams/1/profile").header("Authorization", mine))
                .andReturn().getResponse().getStatus();
        int myClubId = mockMvc.perform(get("/demo/teams/{teamId}/profile", myClub.getId())
                        .header("Authorization", mine))
                .andReturn().getResponse().getStatus();

        assertTrue(teamOne == 200, "the hardcoded route should answer, got " + teamOne);
        assertTrue(myClubId != 200,
                "a per-club id answered, so the mapping is no longer hardcoded and the board's "
                        + "\"do not wire it to anything\" needs revisiting");
    }

    // ── Reads are reachable by a manager ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a manager can read the fabricated table")
    void aManagerCanReadTheTable() throws Exception {
        mockMvc.perform(get("/demo/leagues/1/table")
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an administrator can read it too")
    void anAdministratorCanReadItToo() throws Exception {
        mockMvc.perform(get("/demo/leagues/1/table").header("Authorization", auth.bearer(UserRole.OWNER)))
                .andExpect(status().isOk());
    }

    private static void assertRefused(int code) {
        assertNotNull(code);
        assertTrue(code == 401 || code == 403 || code == 302,
                "expected a refusal, got " + code);
        assertTrue(code != 200, "an anonymous caller was served fabricated data");
    }
}