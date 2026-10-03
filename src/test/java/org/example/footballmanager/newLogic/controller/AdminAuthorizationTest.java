package org.example.footballmanager.newLogic.controller;

import org.example.commonmanager.model.UserRole;
import org.example.footballmanager.BaseTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /admin} is guarded by a path rule, not by annotations — so nothing on the controller says so.
 *
 * <p><b>Why this class exists at all, when {@code AdminController} carries no {@code @PreAuthorize}.</b>
 * Because the guard is real: {@code SecurityConfig} has {@code .requestMatchers("/admin/**").hasAnyRole("ADMIN",
 * "OWNER", "DEV")} ahead of {@code .anyRequest().authenticated()}. Twelve mappings move the world — reset the
 * database, seed forty-six nations, repair the world, force a player off the transfer list — and the only
 * thing standing between a logged-in manager and all of it is one line of matcher ordering. A line of matcher
 * ordering is exactly the kind of thing that is quietly lost in a refactor, and nothing would fail.
 *
 * <p><b>So the assertion is the whole tree, and it is asserted twice.</b> Once with no token, and once with a
 * regular manager's token. A test that only checks the anonymous case proves the chain is present; a test that
 * only checks the role proves the roles are right. Both together are what "administrator only" means.
 *
 * <p><b>These tests were proven able to fail.</b> Removing the {@code /admin/**} matcher from {@code SecurityConfig}
 * turned every regular-manager assertion here from 403 into 200 — the endpoints answered, because
 * {@code anyRequest().authenticated()} accepts a logged-in manager. The reset and seed routes are the ones to
 * watch: they are the ones that cost twenty-six minutes and commit nothing.
 */
@Import(ControllerAuthFixture.class)
class AdminAuthorizationTest extends BaseTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ControllerAuthFixture auth;

    // ── Anonymous ────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an anonymous caller cannot reach the admin tree")
    void anAnonymousCallerIsRefused() throws Exception {
        assertRefused(mockMvc.perform(get("/admin/world-integrity")).andReturn().getResponse().getStatus());
        assertRefused(mockMvc.perform(get("/admin/registration-requests")).andReturn().getResponse().getStatus());
        assertRefused(mockMvc.perform(get("/admin/database-job/status")).andReturn().getResponse().getStatus());
    }

    // ── The role, which is the part that can be lost ──────────────────────────────────────────────────

    @Test
    @DisplayName("a regular manager cannot read the world's integrity report")
    void aRegularManagerCannotReadIntegrity() throws Exception {
        mockMvc.perform(get("/admin/world-integrity")
                        .header("Authorization", auth.bearer(UserRole.REGULAR)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a regular manager cannot repair the world")
    void aRegularManagerCannotRepair() throws Exception {
        mockMvc.perform(post("/admin/world-integrity/repair")
                        .header("Authorization", auth.bearer(UserRole.REGULAR)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a regular manager cannot reset the database")
    void aRegularManagerCannotReset() throws Exception {
        mockMvc.perform(post("/admin/reset-db")
                        .header("Authorization", auth.bearer(UserRole.REGULAR)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a regular manager cannot initialise or seed the world")
    void aRegularManagerCannotSeed() throws Exception {
        mockMvc.perform(post("/admin/initialize-db")
                        .header("Authorization", auth.bearer(UserRole.REGULAR)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/admin/seed-other-nations")
                        .header("Authorization", auth.bearer(UserRole.REGULAR)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a regular manager cannot re-seed national teams or re-draw the cup")
    void aRegularManagerCannotReseed() throws Exception {
        mockMvc.perform(post("/admin/world-reseed")
                        .header("Authorization", auth.bearer(UserRole.REGULAR))
                        .param("what", "national-teams"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a regular manager cannot activate a country")
    void aRegularManagerCannotActivateACountry() throws Exception {
        mockMvc.perform(post("/admin/countries/{isoCode}/activate", "SRB")
                        .header("Authorization", auth.bearer(UserRole.REGULAR)))
                .andExpect(status().isForbidden());
    }

    /**
     * The registration queue carries every pending applicant's username and email, and approving one is what
     * creates an account. It is the most sensitive read on this controller and it is only held by the matcher.
     */
    @Test
    @DisplayName("a regular manager cannot read the registration queue")
    void aRegularManagerCannotReadRegistrations() throws Exception {
        mockMvc.perform(get("/admin/registration-requests")
                        .header("Authorization", auth.bearer(UserRole.REGULAR)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a regular manager cannot approve or reject a registration")
    void aRegularManagerCannotDecideRegistrations() throws Exception {
        mockMvc.perform(post("/admin/registration-requests/{id}/approve", 1L)
                        .header("Authorization", auth.bearer(UserRole.REGULAR)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/admin/registration-requests/{id}/reject", 1L)
                        .header("Authorization", auth.bearer(UserRole.REGULAR)))
                .andExpect(status().isForbidden());
    }

    /**
     * Kept deliberately: {@code forceUnlist} is the route whose own javadoc explains it lives under
     * {@code /admin} precisely <i>because</i> {@code /transfers} is not role-guarded. If somebody ever moved it,
     * any logged-in manager could delist another club's player.
     */
    @Test
    @DisplayName("a regular manager cannot force a player off the transfer list")
    void aRegularManagerCannotForceUnlist() throws Exception {
        mockMvc.perform(delete("/admin/transfer-list/{playerId}", 1L)
                        .header("Authorization", auth.bearer(UserRole.REGULAR)))
                .andExpect(status().isForbidden());
    }

    // ── The gate is a narrowing, not a lockout ───────────────────────────────────────────────────────

    /**
     * The owner must still be able to run his own game.
     *
     * <p>A fix that forbids the wrong set of roles is worse than the hole it closes, so "the owner can still
     * read the world" is a test rather than an assumption. These are the read-only routes on purpose: an
     * assertion that runs {@code reset-db} as a test would be a test that destroys the database it runs on.
     */
    @Test
    @DisplayName("an administrator still reads the operations views")
    void anAdministratorStillReadsTheOperationsViews() throws Exception {
        mockMvc.perform(get("/admin/world-integrity")
                        .header("Authorization", auth.bearer(UserRole.OWNER)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/database-job/status")
                        .header("Authorization", auth.bearer(UserRole.OWNER)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/countries")
                        .header("Authorization", auth.bearer(UserRole.OWNER)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/registration-requests")
                        .header("Authorization", auth.bearer(UserRole.OWNER)))
                .andExpect(status().isOk());
    }

    private static void assertRefused(int code) {
        assertNotNull(code);
        assertTrue(code == 401 || code == 403 || code == 302,
                "expected a refusal, got " + code);
        assertTrue(code != 200, "an anonymous caller was served the admin tree");
    }
}