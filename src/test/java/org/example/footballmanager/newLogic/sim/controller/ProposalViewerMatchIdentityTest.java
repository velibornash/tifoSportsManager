package org.example.footballmanager.newLogic.sim.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.commonmanager.model.User;
import org.example.commonmanager.util.JwtUtil;
import org.example.footballmanager.BaseTest;
import org.example.commonmanager.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
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
 *     controller was mapped at {@code /api/proposal}. Both prefixes must resolve.
 *  2. {@code writeMatchFile} wrote to {@code src/main/resources/static/...},
 *     but Spring serves static files from the CLASSPATH
 *     ({@code target/classes/static/...}), so the downloaded file was the match
 *     from the last BUILD, never the one just generated.
 *  3. The viewer discarded the generate response and re-fetched that stale file.
 *
 * The contract locked in here: the generate response and {@code /latest} must
 * describe the SAME match, and the response must carry the full replay payload.
 *
 * <p>Sprint 0.7: these endpoints moved behind JWT (they were in the permitAll list,
 * along with the entire game API). Every request therefore carries a real signed
 * token, so the actual {@code JwtAuthenticationFilter} is exercised rather than a
 * mock, and {@link #proposalApiRejectsUnauthenticatedRequests()} locks the 401 in.
 */
@AutoConfigureMockMvc
class ProposalViewerMatchIdentityTest extends BaseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private UserRepository userRepository;

    /**
     * Performs the request with a real signed token so the real filter validates it.
     *
     * <p>JwtAuthenticationFilter calls {@code loadUserByUsername}, so the subject must exist in
     * the database - a token for a non-existent user is silently dropped and yields 401. Uses the
     * owner account seeded by DatabaseInitializer.
     */
    private ResultActions auth(MockHttpServletRequestBuilder builder) throws Exception {
        User user = userRepository.findByUsernameOrEmail("velibor@example.com")
                .orElseThrow(() -> new IllegalStateException("seeded owner account is missing"));
        return mockMvc.perform(builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtUtil.generateToken(user)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) throws Exception {
        return MAPPER.readValue(json, Map.class);
    }

    @Test
    void proposalApiRejectsUnauthenticatedRequests() throws Exception {
        mockMvc.perform(get("/proposal/api/latest"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/proposal/api/generate?seed=1").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void generateAndLatestDescribeTheSameMatchUnderBothPathPrefixes() throws Exception {
        // /proposal/api/generate - the path the viewer actually calls.
        String body = auth(post("/proposal/api/generate?seed=20260925")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seed").value(20260925))
                .andExpect(jsonPath("$.matchId").exists())
                .andExpect(jsonPath("$.snapshots").isArray())
                .andExpect(jsonPath("$.events").isArray())
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> generated = parse(body);

        // The response must be the replay itself, not a summary - otherwise the
        // viewer has to fall back to the stale static file.
        assertTrue(generated.get("snapshots") instanceof List,
                "generate must return the full replay payload the viewer plays");
        assertNotNull(generated.get("events"), "generate must return the event stream");
        assertTrue(((List<?>) generated.get("snapshots")).size() > 0, "replay must contain snapshots");

        // /latest must be the very same match, so a refresh or the "Play Match"
        // button can never show a different one.
        String latest = auth(get("/proposal/api/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seed").value(20260925))
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> latestMatch = parse(latest);

        assertEquals(generated.get("matchId"), latestMatch.get("matchId"),
                "latest must be the match just generated, not an older one");
        assertEquals(generated.get("finalScore"), latestMatch.get("finalScore"),
                "latest must carry the same score as the generated match");
        assertEquals(((List<?>) generated.get("events")).size(),
                ((List<?>) latestMatch.get("events")).size(),
                "latest must carry the same events as the generated match");

        // Health exposes the identity too, so a mismatch is diagnosable.
        auth(get("/proposal/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasMatch").value(true))
                .andExpect(jsonPath("$.seed").value(20260925));
    }

    @Test
    void generateIsReachableUnderTheSpringApiPrefixToo() throws Exception {
        auth(post("/api/proposal/generate?seed=7").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seed").value(7))
                .andExpect(jsonPath("$.snapshots").isArray());
    }

    @Test
    void latestIsTheSameMatchForEitherPrefix() throws Exception {
        auth(post("/api/proposal/generate?seed=99"))
                .andExpect(status().isOk());

        String a = auth(get("/api/proposal/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seed").value(99))
                .andReturn().getResponse().getContentAsString();
        String b = auth(get("/proposal/api/latest"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertNotNull(a);
        assertEquals(a.length(), b.length(), "both prefixes must serve the same match");
    }
}
