package org.example.footballmanager.newLogic.controller;

import org.example.commonmanager.model.UserRole;
import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The community board: four routes, no ownership question, and one that is not really about ownership.
 *
 * <p><b>This controller is the good case, and it is here because "no {@code @PreAuthorize}" is not
 * itself a finding.</b> Every route resolves its caller from the token — there is no id in the path for a
 * caller to change — and the one write takes {@code recipientUserId}, which is a choice of recipient rather
 * than a claim over somebody else's data. So the tests below assert what is true, and that is the point:
 * a security sweep that only reports holes teaches nothing about which surfaces were checked.
 *
 * <p><b>One risk is recorded rather than asserted.</b> {@code shouldHideFromNonAdmin} decides whether a
 * message is visible to a non-administrator, and a message attached to a <b>pending registration
 * request</b> carries an applicant's username and email. If that predicate were dropped, those would appear
 * in the chat of every logged-in manager. Pinning it needs a message bound to a pending request, and
 * building one here would assert almost nothing about authorization — so it is written up as a board item
 * instead of shipped as a test that cannot fail.
 *
 * <p>Reads are open to any manager, which is the point of a community board.
 */
@Import(ControllerAuthFixture.class)
class CommunityControllerAuthorizationTest extends BaseTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ControllerAuthFixture auth;

    private Team myClub;

    @BeforeEach
    @Transactional
    void aClubToBeAManagerOf() {
        myClub = auth.club("Community");
    }

    // ── Anonymous ────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an anonymous caller cannot read the chat")
    void anAnonymousCallerCannotReadTheChat() throws Exception {
        assertRefused(mockMvc.perform(get("/community/chat")).andReturn().getResponse().getStatus());
    }

    @Test
    @DisplayName("an anonymous caller cannot read the summary")
    void anAnonymousCallerCannotReadTheSummary() throws Exception {
        assertRefused(mockMvc.perform(get("/community/summary")).andReturn().getResponse().getStatus());
    }

    @Test
    @DisplayName("an anonymous caller cannot list recipients")
    void anAnonymousCallerCannotListRecipients() throws Exception {
        assertRefused(mockMvc.perform(get("/community/recipients")).andReturn().getResponse().getStatus());
    }

    @Test
    @DisplayName("an anonymous caller cannot post a message")
    void anAnonymousCallerCannotPost() throws Exception {
        assertRefused(mockMvc.perform(post("/community/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\"}"))
                .andReturn().getResponse().getStatus());
    }

    // ── The successful paths, so the refusals above cannot all be satisfied by a broken controller ──────

    @Test
    @DisplayName("a manager can read the chat")
    void aManagerCanReadTheChat() throws Exception {
        mockMvc.perform(get("/community/chat")
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a manager can read the summary")
    void aManagerCanReadTheSummary() throws Exception {
        mockMvc.perform(get("/community/summary")
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a manager can post a message")
    void aManagerCanPost() throws Exception {
        mockMvc.perform(post("/community/chat")
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Good luck this season.\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an administrator can post too")
    void anAdministratorCanPost() throws Exception {
        mockMvc.perform(post("/community/chat")
                        .header("Authorization", auth.bearer(UserRole.OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Welcome.\"}"))
                .andExpect(status().isOk());
    }

    private static void assertRefused(int code) {
        assertNotNull(code);
        assertTrue(code == 401 || code == 403 || code == 302,
                "expected a refusal, got " + code);
        assertTrue(code != 200, "an anonymous caller was served the community board");
    }
}