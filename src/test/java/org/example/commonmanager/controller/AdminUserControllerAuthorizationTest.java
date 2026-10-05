package org.example.commonmanager.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.commonmanager.model.UserRole;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may appoint moderators and ban managers (owner, 2026-10-05).
 *
 * <p><b>The role split is the thing this test exists for.</b> {@code UserRoles.mayModerate} includes
 * {@code MOD} so a moderator can delete a post and apply a forum ban. {@code UserRoles.isStaff} excludes
 * it, because changing an account's role is world administration — and a moderator who could promote
 * himself to {@code ADMIN} would be promoting himself to the ability to reset the database.
 *
 * <p>A moderator reaching {@code POST /admin/users/{id}/role} would not be refused by
 * {@code SecurityConfig} alone either: the {@code /admin/**} matcher lists {@code ADMIN}, {@code OWNER}
 * and {@code DEV}, so it happens to hold. That is a coincidence of two independently-written lists, and
 * {@code AdminAuthorizationTest} already exists because that one matcher is the only thing standing
 * between a logged-in manager and the admin surface. This class asserts the <i>intent</i>, so widening
 * the matcher to include {@code MOD} without deciding what a moderator may administer fails here rather
 * than shipping.
 *
 * <p>Every principal is built by {@link ControllerAuthFixture} rather than read from the seed: a test
 * about a role must build the role.
 */
@Import(ControllerAuthFixture.class)
class AdminUserControllerAuthorizationTest extends BaseTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ControllerAuthFixture auth;

    @Autowired
    ObjectMapper json;

    @Autowired
    org.example.commonmanager.repository.UserRepository users;

    @Autowired
    jakarta.persistence.EntityManager entityManager;

    private String moderatorToken;
    private String ownerToken;
    private String regularToken;
    private Long targetId;

    @BeforeEach
    @Transactional
    void principals() throws Exception {
        ownerToken = auth.bearer(UserRole.OWNER);
        moderatorToken = auth.bearer(UserRole.MOD);
        regularToken = auth.bearer(UserRole.REGULAR);

        targetId = idOf(regularToken);
    }

    // ── Anonymous ───────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an anonymous caller cannot list accounts")
    void anAnonymousCallerCannotListAccounts() throws Exception {
        assertRefused(mockMvc.perform(get("/admin/users"))
                .andReturn().getResponse().getStatus(), "/admin/users");
    }

    @Test
    @DisplayName("an anonymous caller cannot ban anybody")
    void anAnonymousCallerCannotBan() throws Exception {
        int status = mockMvc.perform(post("/admin/users/{id}/forum-ban", targetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"days\":7,\"reason\":\"no\"}"))
                .andReturn().getResponse().getStatus();
        assertRefused(status, "POST /admin/users/{id}/forum-ban");
    }

    /**
     * A refusal, whatever the chain decides to call it.
     *
     * <p>401, 403 and a 302 to the login page are all correct answers — the chain and the entry point
     * configuration decide which, and none of them hands the data over. Asserting one exact code would
     * pin an implementation detail and tell the next reader nothing. The {@code != 200} is the part that
     * matters, and it is the part the assertion keeps.
     */
    private static void assertRefused(int status, String what) {
        assertTrue(status == HttpStatus.UNAUTHORIZED.value()
                        || status == HttpStatus.FORBIDDEN.value()
                        || status == HttpStatus.FOUND.value(),
                what + " answered " + status + " to an anonymous caller instead of refusing");
        assertNotEquals(HttpStatus.OK.value(), status, what + " let an anonymous caller through");
    }

    // ── A REGULAR manager ───────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a regular manager cannot list accounts")
    void aRegularManagerCannotListAccounts() throws Exception {
        mockMvc.perform(get("/admin/users").header("Authorization", regularToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a regular manager cannot ban anybody")
    void aRegularManagerCannotBan() throws Exception {
        mockMvc.perform(post("/admin/users/{id}/forum-ban", targetId)
                        .header("Authorization", regularToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"days\":7,\"reason\":\"because\"}"))
                .andExpect(status().isForbidden());
    }

    // ── A MOD ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a moderator is refused the account list")
    void aModeratorCannotListAccounts() throws Exception {
        // The list carries every account's ban state and email address. A moderator's job is the
        // forum, not the register.
        mockMvc.perform(get("/admin/users").header("Authorization", moderatorToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a moderator cannot change a role, or it could promote itself to ADMIN")
    void aModeratorCannotChangeARole() throws Exception {
        mockMvc.perform(post("/admin/users/{id}/role", targetId)
                        .header("Authorization", moderatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("role", "ADMIN"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a moderator cannot repair club links, which writes every account row")
    void aModeratorCannotRepairClubLinks() throws Exception {
        mockMvc.perform(post("/admin/users/repair-club-links").header("Authorization", moderatorToken))
                .andExpect(status().isForbidden());
    }

    // ── An ADMIN and the OWNER ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an admin can list accounts and appoint a moderator")
    void anAdminCanAppointAModerator() throws Exception {
        String adminToken = auth.bearer(UserRole.ADMIN);

        mockMvc.perform(get("/admin/users").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        mockMvc.perform(post("/admin/users/{id}/role", targetId)
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("role", "MOD"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MOD"));
    }

    @Test
    @DisplayName("an admin can apply a forum ban and the response says what actually happened")
    void anAdminCanBan() throws Exception {
        String adminToken = auth.bearer(UserRole.ADMIN);

        mockMvc.perform(post("/admin/users/{id}/forum-ban", targetId)
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("days", 5, "reason", "Spam"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.forumBanned").value(true))
                .andExpect(jsonPath("$.forumBanReason").value("Spam"))
                .andExpect(jsonPath("$.forumBanDaysLeft").value(5));

        mockMvc.perform(post("/admin/users/{id}/forum-ban/lift", targetId)
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.forumBanned").value(false));
    }

    @Test
    @DisplayName("the owner's role cannot be changed through the endpoint, in either direction")
    void theOwnersRoleIsNotChangeable() throws Exception {
        Long ownerId = idOf(ownerToken);

        mockMvc.perform(post("/admin/users/{id}/role", ownerId)
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("role", "REGULAR"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a bad role is a 400, not a 500 and not a silent success")
    void aBadRoleIsRejected() throws Exception {
        mockMvc.perform(post("/admin/users/{id}/role", targetId)
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("role", "SUPREME"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an unknown account is a 404")
    void anUnknownAccountIsANotFound() throws Exception {
        mockMvc.perform(post("/admin/users/{id}/forum-ban", 999_999_999L)
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("days", 3, "reason", "x"))))
                .andExpect(status().isNotFound());
    }

    /**
     * The response is not the evidence — the row is.
     *
     * <p>Every other test in this class asserts what the endpoint <i>said</i>. That is exactly the
     * weakness this repository has been bitten by: a handler that builds a correct-looking body and
     * never calls the service passes every status and JSON assertion. Mutating the ban endpoint to
     * return a hand-built "forumBanned: true" without touching the database was caught here only by the
     * 404 test, for the wrong reason.
     *
     * <p>So this one reads the account back after the request and asks whether it is actually banned.
     */
    @Test
    @Transactional
    @DisplayName("a ban applied through the endpoint is on the account afterwards")
    void theBanIsActuallyPersisted() throws Exception {
        mockMvc.perform(post("/admin/users/{id}/forum-ban", targetId)
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("days", 9, "reason", "Read back"))))
                .andExpect(status().isOk());

        var banned = reloaded(targetId);
        assertTrue(banned.isForumBanned(),
                "the endpoint answered 200 with forumBanned=true and the database row says otherwise");
        assertEquals("Read back", banned.getForumBanReason());
        assertEquals(UserRole.REGULAR, banned.getRole(), "the ban changed the account's role");

        mockMvc.perform(post("/admin/users/{id}/forum-ban/lift", targetId)
                        .header("Authorization", ownerToken))
                .andExpect(status().isOk());

        assertFalse(reloaded(targetId).isForumBanned(),
                "the lift endpoint answered 200 and the account is still banned");
    }

    @Test
    @Transactional
    @DisplayName("a role changed through the endpoint is on the account afterwards")
    void theRoleChangeIsActuallyPersisted() throws Exception {
        mockMvc.perform(post("/admin/users/{id}/role", targetId)
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("role", "MOD"))))
                .andExpect(status().isOk());

        assertEquals(UserRole.MOD, reloaded(targetId).getRole(),
                "the endpoint reported MOD and the account still holds its old role");
    }

    /**
     * The role change survives the request — a committed row, read outside the request's transaction.
     *
     * <p>Transactional tests cannot answer this. They share a persistence context with the request, so
     * {@code findById} returns the very instance the service mutated, dirty or not.
     *
     * <p><b>Recorded honestly: this test still does not catch a missing {@code save}, and no test
     * around this service will.</b> {@code changeRole} is {@code @Transactional} and the entity arrives
     * managed from {@code findById}, so JPA dirty checking flushes the change at commit whether or not
     * {@code users.save} is called. Deleting that {@code save} was tried against this class and
     * {@code ModerationServiceTest} — all 31 tests stayed green. That is an equivalent mutant rather
     * than a coverage hole: the {@code save} is redundant for a managed entity, and the row is written
     * either way. It would stop being redundant the moment the lookup left the transaction.
     *
     * <p>What the test does establish, and what a transactional one cannot: the change is on disk once
     * the request is over, so the next request reads it.
     */
    @Test
    @DisplayName("a role change is committed, not just held in memory for the request")
    void theRoleIsWrittenAndCommitted() throws Exception {
        Long userId = aStandaloneUser();

        mockMvc.perform(post("/admin/users/{userId}/role", userId)
                        .header("Authorization", auth.bearer(UserRole.OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("role", "ADMIN"))))
                .andExpect(status().isOk());

        // Its own transaction: nothing left over from the request, and nothing this test can roll back.
        assertEquals(UserRole.ADMIN, users.findById(userId).orElseThrow().getRole(),
                "the role change did not survive the request — it was never written");
    }

    /** An account that already exists on disk before this test begins, and is not rolled back after. */
    private Long aStandaloneUser() {
        org.example.commonmanager.model.User user = new org.example.commonmanager.model.User();
        user.setUsername("committed-" + java.util.UUID.randomUUID() + "@test.local");
        user.setEmail(user.getUsername());
        user.setPassword("not-a-real-hash");
        user.setDisplayName("Committed tester");
        user.setRole(UserRole.REGULAR);
        user.setPlusSubscription(false);
        return users.save(user).getId();
    }

    /**
     * Reads an account back from the database with the persistence context cleared first.
     *
     * <p><b>Without the clear, this asserts nothing.</b> The test method is transactional and the request
     * shares that transaction, so the {@code User} instance the service mutated is still in the
     * persistence context and {@code findById} hands the same object straight back.
     *
     * <p><b>And this still cannot catch a missing {@code save}, which is worth being honest about.</b>
     * Removing {@code users.save(...)} from {@code changeRole} leaves this test green: the entity is
     * managed and the test transaction flushes it at the end, so the row is written either way. JPA
     * dirty checking, not the explicit call, is what persists it here. The assertion below is therefore
     * about the <i>effect</i> — the row says MOD — and {@code theRoleIsWrittenAndCommitted} is the test
     * that proves it survives the request.
     */
    private org.example.commonmanager.model.User reloaded(Long id) {
        entityManager.flush();
        entityManager.clear();
        return users.findById(id).orElseThrow();
    }

    /**
     * The id behind a token, read from the subject.
     *
     * <p>{@code JwtUtil} puts the username in {@code sub} and carries no user id, so this decodes and
     * looks the account up rather than assuming a claim that is not there. The alternative — capturing
     * the id inside the fixture — would need the fixture to return it, and it returns a token because
     * that is all the eight existing authorization tests need.
     */
    private Long idOf(String token) throws Exception {
        String raw = token.substring("Bearer ".length());
        String claims = new String(java.util.Base64.getUrlDecoder().decode(raw.split("\\.")[1]),
                java.nio.charset.StandardCharsets.UTF_8);
        String username;
        try {
            username = json.readTree(claims).get("sub").asText();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("The test token did not decode; JwtUtil has no user-id claim "
                    + "and its subject must remain the username.", e);
        }
        return users.findByUsername(username).orElseThrow().getId();
    }
}