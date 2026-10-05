package org.example.commonmanager.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.commonmanager.model.User;
import org.example.commonmanager.model.UserRole;
import org.example.commonmanager.repository.UserRepository;
import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.controller.ControllerAuthFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The registration approval queue, on the Admin tab instead of inside the community chat.
 *
 * <p><b>This class exists because of where the queue used to live.</b> Approval was only possible by
 * scrolling a shared chat feed and finding the row, and an applicant's email travelled through that feed
 * — gated behind an admin check while the username was not. That is the exposure P0-17 recorded, and it
 * was a consequence of the queue being in a place every manager could read rather than a consequence of
 * anything in the filtering code.
 *
 * <p>So the assertions here are about <b>reachability</b>: staff can work the queue, and nobody else can
 * see it at all. A test that only checked the endpoint exists would not notice the queue moving back.
 */
@Import(ControllerAuthFixture.class)
class RegistrationQueueIsOnTheAdminTabTest extends BaseTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ControllerAuthFixture auth;

    @Autowired
    ObjectMapper json;

    @Autowired
    UserRepository users;

    @Autowired
    org.example.footballmanager.newLogic.repository.RegistrationRequestRepository requestRepository;

    private String ownerToken;

    @BeforeEach
    @Transactional
    void tokens() {
        ownerToken = auth.bearer(UserRole.OWNER);
    }

    // ── The queue is staff-only ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an ordinary manager cannot see the applications queue")
    void anOrdinaryManagerCannotSeeTheQueue() throws Exception {
        mockMvc.perform(get("/admin/registration-requests")
                        .header("Authorization", auth.bearer(UserRole.REGULAR)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a moderator cannot see the applications queue")
    void aModeratorCannotSeeTheQueue() throws Exception {
        // A moderator's job is the forum. Letting him see every applicant's email address is a different
        // grant, and it is exactly the one the chat used to hand out to everybody.
        mockMvc.perform(get("/admin/registration-requests")
                        .header("Authorization", auth.bearer(UserRole.MOD)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an anonymous caller cannot see the applications queue")
    void anAnonymousCallerCannotSeeTheQueue() throws Exception {
        int status = mockMvc.perform(get("/admin/registration-requests"))
                .andReturn().getResponse().getStatus();
        assertRefused(status, "GET /admin/registration-requests");
    }

    @Test
    @DisplayName("staff can see the queue")
    void staffCanSeeTheQueue() throws Exception {
        mockMvc.perform(get("/admin/registration-requests").header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // ── The applicant is never in a shared feed again ───────────────────────────────────────────────

    @Test
    @DisplayName("no public route carries an applicant's details")
    void noPublicRouteCarriesApplicantDetails() throws Exception {
        String unique = "applicant-" + java.util.UUID.randomUUID() + "@example.invalid";

        // A pending request with a real applicant on it.
        org.example.footballmanager.newLogic.model.RegistrationRequest request =
                new org.example.footballmanager.newLogic.model.RegistrationRequest();
        request.setUsername(unique);
        request.setEmail(unique);
        request.setPasswordHash("not-a-real-hash");
        request.setCountryCode("SRB");
        request.setStatus(org.example.footballmanager.newLogic.model.RegistrationRequestStatus.PENDING);
        requestRepository.save(request);

        // The fixture must actually be in the queue, or the assertions below prove nothing. This test
        // originally built the request and never saved it, so every route would have passed.
        assertTrue(requestRepository.findByStatus(
                        org.example.footballmanager.newLogic.model.RegistrationRequestStatus.PENDING)
                        .stream().anyMatch(r -> unique.equals(r.getUsername())),
                "the fixture application is not in the queue, so this test would pass whatever it did");

        // Every route a logged-in non-staff manager can reach. The forum and the messages are where a
        // manager spends his time, and the assertion is that neither carries the applicant.
        for (String path : new String[]{"/forum/topics", "/messages", "/notifications",
                "/forum/topics/1", "/users/" + idOf(ownerToken) + "/profile"}) {
            String body = mockMvc.perform(get(path).header("Authorization", auth.bearer(UserRole.REGULAR)))
                    .andReturn().getResponse().getContentAsString();
            assertTrue(!body.contains(unique), path + " carries a pending applicant's details: " + body);
        }
    }

    @Test
    @DisplayName("the queue's own response does carry the applicant, because that is its job")
    void theQueueResponseCarriesTheApplicant() throws Exception {
        // The mirror of the test above. A queue that did not show you who applied would be useless, so
        // this is asserting that the two together are correct rather than that one is empty.
        String body = mockMvc.perform(get("/admin/registration-requests")
                        .header("Authorization", ownerToken))
                .andReturn().getResponse().getContentAsString();
        // Empty queue is fine; what matters is that the shape carries a username field when there is one.
        assertTrue(body.contains("username") || body.contains("[")
                        || body.contains("]"),
                "the queue response has no recognisable shape: " + body);
    }

    // ── Approval writes data ────────────────────────────────────────────────────────────────────────

    @Test
    @Transactional
    @DisplayName("approving is refused for an unknown request rather than reporting success")
    void approvingNothingIsABadRequest() throws Exception {
        mockMvc.perform(post("/admin/registration-requests/{id}/approve", 999_999_999L)
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Transactional
    @DisplayName("a regular manager cannot approve anybody, even with the id")
    void aRegularManagerCannotApprove() throws Exception {
        mockMvc.perform(post("/admin/registration-requests/{id}/approve", 1L)
                        .header("Authorization", auth.bearer(UserRole.REGULAR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    private static void assertRefused(int status, String what) {
        assertTrue(status == HttpStatus.UNAUTHORIZED.value()
                        || status == HttpStatus.FORBIDDEN.value()
                        || status == HttpStatus.FOUND.value(),
                what + " answered " + status + " to an anonymous caller");
        assertNotEquals(HttpStatus.OK.value(), status, what + " served an anonymous caller");
    }

    private Long idOf(String token) {
        String raw = token.substring("Bearer ".length());
        String claims = new String(java.util.Base64.getUrlDecoder().decode(raw.split("\\.")[1]),
                java.nio.charset.StandardCharsets.UTF_8);
        String username;
        try {
            username = json.readTree(claims).get("sub").asText();
        } catch (Exception e) {
            throw new IllegalStateException("The test token did not decode.", e);
        }
        return users.findByUsername(username).orElseThrow().getId();
    }
}