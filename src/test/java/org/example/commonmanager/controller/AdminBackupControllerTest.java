package org.example.commonmanager.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.commonmanager.model.UserRole;
import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.controller.ControllerAuthFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may back the database up, and who may put it back (owner, 2026-10-06).
 *
 * <p><b>Restore is the most destructive route in the application.</b> It drops the schema and replays an
 * archive over it. Everything else in {@code /admin} either rebuilds what is missing or writes rows; this
 * one can remove a season of results with one click, so it is held by the same role list as the rest of
 * the admin surface — and asserted here, because a role check that exists only in
 * {@code SecurityConfig} is a role check that survives one refactor too many.
 *
 * <p>Only the <em>refusals</em> are exercised. The happy path would dump whatever database the test
 * profile is pointed at, and the point of these tests is who gets through the door and what is said to
 * the ones who do not, not the archive format — which {@code DatabaseBackupRoundTripTest} proves against
 * a database of its own.
 */
@Import(ControllerAuthFixture.class)
class AdminBackupControllerTest extends BaseTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ControllerAuthFixture auth;

    @Autowired
    ObjectMapper json;

    @Test
    @DisplayName("a manager who is not an admin can neither list nor create nor restore")
    void aRegularManagerIsRefusedEverywhere() throws Exception {
        String token = auth.bearer(UserRole.REGULAR);

        mockMvc.perform(get("/admin/backups").header("Authorization", token))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/backups").header("Authorization", token))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/backups/2026-10-06-23-15-04.dump/restore").header("Authorization", token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an admin may list the backups, and the route answers even when there are none")
    void anAdminMayListBackups() throws Exception {
        mockMvc.perform(get("/admin/backups").header("Authorization", auth.bearer(UserRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backups").exists());
    }

    @Test
    @DisplayName("a restore of a name that is not a backup is refused in plain words, not a server error")
    void anInvalidBackupNameIsARefusal() throws Exception {
        mockMvc.perform(post("/admin/backups/not-a-backup/restore")
                        .header("Authorization", auth.bearer(UserRole.OWNER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("yyyy-mm-dd-HH-mm-ss.dump")));

        mockMvc.perform(post("/admin/backups/..%2F..%2Fetc%2Fpasswd/restore")
                        .header("Authorization", auth.bearer(UserRole.OWNER)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the restore route keeps the .dump extension, so the name is not truncated to its stem")
    void theDumpExtensionSurvivesTheRoute() throws Exception {
        // Spring can be configured to strip a file extension from a path variable, which would turn
        // "2026-10-06-23-15-04.dump" into "2026-10-06-23-15-04" and then every restore would be told the
        // name is not a backup. The refusal proves which of the two the controller received.
        String body = mockMvc.perform(post("/admin/backups/2026-10-06-23-15-04.dump/restore")
                        .header("Authorization", auth.bearer(UserRole.ADMIN)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("2026-10-06-23-15-04.dump"),
                "the controller saw the whole name, extension and all: " + body);
    }

    @Test
    @DisplayName("the payload shape the admin screen reads is what the server sends")
    void theListPayloadShape() throws Exception {
        String body = mockMvc.perform(get("/admin/backups").header("Authorization", auth.bearer(UserRole.DEV)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = json.readValue(body, Map.class);
        assertTrue(payload.containsKey("backups"), "the screen reads body.backups: " + body);
    }
}