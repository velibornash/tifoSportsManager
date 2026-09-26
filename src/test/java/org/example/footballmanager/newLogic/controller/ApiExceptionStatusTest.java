package org.example.footballmanager.newLogic.controller;

import org.example.footballmanager.BaseTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.example.commonmanager.model.User;
import org.example.commonmanager.repository.UserRepository;
import org.example.commonmanager.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A refusal the game decided on is not a server fault (Sprint 4 triage, 2026-09-26).
 *
 * <p>{@code ApiException} carries a deliberate status — 404, 409, 422 — and there was no
 * {@code @ExceptionHandler} for it, so every one fell through to the catch-all and reached the
 * browser as a 500. The game uses it for entirely ordinary outcomes: the transfer window is shut,
 * the club cannot afford the fee, the squad is full, the agreed price is below the asking price,
 * this week's training report does not exist.
 *
 * <p>All of those arrived looking like a broken server, which is what made the Training page replace
 * itself with "API Error" over one empty week.
 */
class ApiExceptionStatusTest extends BaseTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtUtil jwtUtil;

    @Autowired
    UserRepository users;

    /**
     * A real bearer token, so the request goes through the actual security filter chain.
     *
     * <p>Without one the filter answers a 302 to /login.html and the test measures the wrong thing
     * entirely - which is how a page-level "API Error" can be a permissions problem wearing a
     * costume.
     */
    private String bearer() {
        User user = users.findByUsernameOrEmail("velibor@example.com")
                .orElseGet(() -> users.findAll().stream().findFirst()
                        .orElseThrow(() -> new IllegalStateException("no user to test with")));
        return "Bearer " + jwtUtil.generateToken(user);
    }

    @Test
    @DisplayName("a missing training report answers 404, not 500")
    void missingReportIsFourOhFour() throws Exception {
        // Season 99 week 99 cannot exist, so this is a guaranteed miss with no test data needed.
        int status = mockMvc.perform(get("/training/weekly/team/1/reports/99/99").header("Authorization", bearer()))
                .andReturn().getResponse().getStatus();

        assertEquals(HttpStatus.NOT_FOUND.value(), status,
                "a week with no training run yet is an empty state; a 500 makes the whole Training "
                        + "page look broken and can log the manager out");
    }

    @Test
    @DisplayName("a missing player report answers 404 too")
    void missingPlayerReportIsFourOhFour() throws Exception {
        int status = mockMvc.perform(get("/training/weekly/team/1/player/1/reports/99/99").header("Authorization", bearer()))
                .andReturn().getResponse().getStatus();

        assertEquals(HttpStatus.NOT_FOUND.value(), status);
    }

    @Test
    @DisplayName("the error body carries the code and message the game chose")
    void theBodyIsTheGamesOwn() throws Exception {
        String body = mockMvc.perform(get("/training/weekly/team/1/reports/99/99").header("Authorization", bearer()))
                .andReturn().getResponse().getContentAsString();

        // The point is that the code reaches the client at all. Before this handler existed it was
        // flattened into a generic 500 body and the code was lost, so the frontend could not tell a
        // deliberate refusal from a fault.
        assertNotNull(body);
        assertTrue(body.contains("REPORT_NOT_FOUND"),
                "the response should name the refusal, got: " + body);
    }
}
