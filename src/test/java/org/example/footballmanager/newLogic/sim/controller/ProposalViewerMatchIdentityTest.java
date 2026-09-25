package org.example.footballmanager.newLogic.sim.controller;

import org.example.footballmanager.BaseTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * USER-REPORTED BUG (highest priority, 2026-09-25): the viewer UI showed an
 * entirely DIFFERENT match than the one the engine had just simulated and
 * logged. Three independent causes lived in the request/response path:
 *
 *  1. The viewer POSTed to {@code /proposal/api/generate}, but the Spring
 *     controller was mapped at {@code /api/proposal} — and
 *     {@code /proposal/api/**} was not in the security permit list either, so
 *     the call 404'd/401'd. Both prefixes must resolve now.
 *  2. {@code writeMatchFile} wrote to {@code src/main/resources/static/...},
 *     but Spring serves static files from the CLASSPATH
 *     ({@code target/classes/static/...}). The file the browser downloads was
 *     therefore the match from the last BUILD, never the one just generated.
 *  3. The viewer discarded the generate response and re-fetched that stale
 *     file, so even a correct write path would not have helped.
 *
 * The contract locked in here: the generate response and {@code /latest} must
 * describe the SAME match, and the response must carry the full replay payload
 * (so the viewer never needs the static file).
 */
@AutoConfigureMockMvc
class ProposalViewerMatchIdentityTest extends BaseTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void generateAndLatestDescribeTheSameMatchUnderBothPathPrefixes() throws Exception {
        // /proposal/api/generate — the path the viewer actually calls.
        String body = mockMvc.perform(post("/proposal/api/generate?seed=20260925")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seed").value(20260925))
                .andExpect(jsonPath("$.matchId").exists())
                .andExpect(jsonPath("$.snapshots").isArray())
                .andExpect(jsonPath("$.events").isArray())
                .andReturn().getResponse().getContentAsString();

        @SuppressWarnings("unchecked")
        Map<String, Object> generated = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(body, Map.class);

        // The response must be the replay itself, not a summary — otherwise the
        // viewer has to fall back to the stale static file.
        assertTrue(generated.get("snapshots") instanceof java.util.List,
                "generate must return the full replay payload the viewer plays");
        assertNotNull(generated.get("events"), "generate must return the event stream");
        assertTrue(((java.util.List<?>) generated.get("snapshots")).size() > 0,
                "replay must contain snapshots");

        // /latest must be the very same match, so a page refresh or the
        // "Play Match" button can never show a different one.
        String latest = mockMvc.perform(get("/proposal/api/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seed").value(20260925))
                .andReturn().getResponse().getContentAsString();

        @SuppressWarnings("unchecked")
        Map<String, Object> latestMatch = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(latest, Map.class);

        assertEquals(generated.get("matchId"), latestMatch.get("matchId"),
                "latest must be the match just generated, not an older one");
        assertEquals(generated.get("finalScore"), latestMatch.get("finalScore"),
                "latest must carry the same score as the generated match");
        assertEquals(((java.util.List<?>) generated.get("events")).size(),
                ((java.util.List<?>) latestMatch.get("events")).size(),
                "latest must carry the same events as the generated match");

        // Health exposes the identity too, so a mismatch is diagnosable.
        mockMvc.perform(get("/proposal/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasMatch").value(true))
                .andExpect(jsonPath("$.seed").value(20260925));
    }

    @Test
    void generateIsReachableUnderTheSpringApiPrefixToo() throws Exception {
        mockMvc.perform(post("/api/proposal/generate?seed=7")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seed").value(7))
                .andExpect(jsonPath("$.snapshots").isArray());
    }

    @Test
    void latestIsTheSameMatchForEitherPrefix() throws Exception {
        mockMvc.perform(post("/api/proposal/generate?seed=99"))
                .andExpect(status().isOk());
        String a = mockMvc.perform(get("/api/proposal/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seed").value(99))
                .andReturn().getResponse().getContentAsString();
        String b = mockMvc.perform(get("/proposal/api/latest"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertNotNull(a);
        assertEquals(a.length(), b.length(), "both prefixes must serve the same match");
    }
}
