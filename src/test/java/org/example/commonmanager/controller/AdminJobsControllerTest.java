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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The jobs panel is admin-only and answers with every job (owner, 2026-10-07).
 *
 * <p>It is the surface that makes "did training run?" answerable, so it carries the same role guard as
 * the rest of {@code /admin}: the rows include which jobs exist, when they last ran, and their failure
 * messages — which is operational detail about the running world.
 */
@Import(ControllerAuthFixture.class)
class AdminJobsControllerTest extends BaseTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ControllerAuthFixture auth;

    @Autowired
    ObjectMapper json;

    @Test
    @DisplayName("a manager who is not an admin cannot read the jobs")
    void aRegularManagerIsRefused() throws Exception {
        mockMvc.perform(get("/admin/jobs").header("Authorization", auth.bearer(UserRole.REGULAR)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an admin gets every job, with a trigger and a next trigger")
    void anAdminGetsTheJobList() throws Exception {
        String body = mockMvc.perform(get("/admin/jobs").header("Authorization", auth.bearer(UserRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobs").isArray())
                .andReturn().getResponse().getContentAsString();

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = json.readValue(body, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> jobs = (List<Map<String, Object>>) payload.get("jobs");

        assertTrue(!jobs.isEmpty(), "the panel lists the jobs: " + body);
        Map<String, Object> first = jobs.get(0);
        for (String field : new String[]{"key", "trigger", "nextTrigger"}) {
            assertTrue(first.containsKey(field), "every job carries '" + field + "': " + first);
        }
        assertTrue(payload.containsKey("failing"),
                "and the panel says whether anything has failed - that is the badge: " + payload);
    }
}