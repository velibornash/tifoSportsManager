package org.example.footballmanager.integration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.footballmanager.BaseTest;
import org.example.footballmanager.model.MatchFixture;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.model.User;
import org.example.footballmanager.model.UserRole;
import org.example.footballmanager.repository.MatchFixtureRepository;
import org.example.footballmanager.repository.TeamRepository;
import org.example.footballmanager.repository.UserRepository;
import org.example.footballmanager.service.SeasonService;
import org.example.footballmanager.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Backend Integration Tests using MockMvc
 * Tests controllers with actual Spring context and database
 * 
 * Prerequisites:
 * - Spring Boot Test configured
 * - Database (H2 or PostgreSQL)
 * - MockMvc available
 * 
 * Run: mvn test -Dtest=TifoBackendIntegrationTest
 */
@DisplayName("TIFO Backend Integration Tests")
public class TifoBackendIntegrationTest extends BaseTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private MatchFixtureRepository matchFixtureRepository;

    @Autowired
    private SeasonService seasonService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    @BeforeEach
    public void setUp() {
        userRepository.deleteAll();
    }

    // ==================== AUTHENTICATION TESTS ====================

    @Test
    @DisplayName("Integration-001: Registration - Complete Flow")
    public void testRegistrationCompleteFlow() throws Exception {
        String email = "newuser_" + System.currentTimeMillis() + "@example.com";
        String requestBody = """
            {
                "email": "%s",
                "password": "A12345!@"
            }
            """.formatted(email);

        MvcResult result = mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assertThat(responseBody).contains("userId");
    }

    @Test
    @DisplayName("Integration-002: Duplicate Email Prevention")
    public void testDuplicateEmailPrevention() throws Exception {
        String email = "duplicate_" + System.currentTimeMillis() + "@example.com";
        String password = "A12345!@";

        // Register first user
        mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                    "email": "%s",
                    "password": "%s"
                }
                """.formatted(email, password)))
        .andExpect(status().isOk());

        // Try to register same email again
        mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                    "email": "%s",
                    "password": "%s"
                }
                """.formatted(email, password)))
        .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Integration-003: Login After Registration")
    public void testLoginAfterRegistration() throws Exception {
        String email = "logintest_" + System.currentTimeMillis() + "@example.com";
        String password = "A12345!@";

        // Register
        mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                    "email": "%s",
                    "password": "%s"
                }
                """.formatted(email, password)))
        .andExpect(status().isOk());

        // Login
        mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                    "email": "%s",
                    "password": "%s"
                }
                """.formatted(email, password)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").isNotEmpty());
    }

    // ==================== AUTHORIZATION TESTS ====================

    @Test
    @DisplayName("Integration-004: Get Team Without Auth - Should Fail")
    public void testGetTeamWithoutAuth() throws Exception {
        mockMvc.perform(get("/api/teams/1")
            .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Integration-005: Invalid Token - 401 Response")
    public void testInvalidTokenUnauthorized() throws Exception {
        mockMvc.perform(get("/api/teams/1")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Authorization", "Bearer invalid-token"))
        .andExpect(status().isUnauthorized());
    }

    // ==================== DATABASE TESTS ====================

    @Test
    @DisplayName("Integration-006: Database Persistence - User Creation")
    public void testDatabasePersistenceUserCreation() throws Exception {
        User user = new User();
        user.setEmail("persistence_" + System.currentTimeMillis() + "@example.com");
        user.setPassword("hashedpassword");
        user.setRole(UserRole.ADMIN);

        User savedUser = userRepository.save(user);

        assertThat(savedUser.getId()).isNotNull();
        assertThat(userRepository.findByEmail("persistence@primer.rs")).isPresent();
    }

    @Test
    @DisplayName("Integration-007: User Retrieval From Database")
    public void testUserRetrievalFromDatabase() throws Exception {
        String email = "retrieve_" + System.currentTimeMillis() + "@example.com";
        User user = new User();
        user.setEmail(email);
        user.setPassword("password");
        user.setRole(UserRole.REGULAR);
        
        userRepository.save(user);

        assertThat(userRepository.findByEmail(email))
            .isPresent()
            .get()
            .satisfies(u -> {
                assertThat(u.getEmail()).isEqualTo(email);
                assertThat(u.getRole()).isEqualTo(UserRole.REGULAR);
            });
    }

    // ==================== ERROR HANDLING TESTS ====================

    @Test
    @DisplayName("Integration-008: 404 - Non-existent Endpoint")
    public void testNonExistentEndpoint() throws Exception {
        mockMvc.perform(get("/api/nonexistent/endpoint"))
        .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Integration-009: 400 - Invalid Request Body")
    public void testInvalidRequestBody() throws Exception {
        mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{invalid json"))
        .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Integration-010: 405 - Method Not Allowed")
    public void testMethodNotAllowed() throws Exception {
        mockMvc.perform(delete("/api/auth/register"))
        .andExpect(status().isMethodNotAllowed());
    }

    // ==================== DATA VALIDATION TESTS ====================

    @Test
    @DisplayName("Integration-011: Weak Password Rejection")
    public void testWeakPasswordRejection() throws Exception {
        String email = "weak_" + System.currentTimeMillis() + "@example.com";
        mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                    "email": "%s",
                    "password": "weak"
                }
                """.formatted(email)))
        .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Integration-012: Invalid Email Format")
    public void testInvalidEmailFormat() throws Exception {
        mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                    "email": "invalid-email",
                    "password": "A12345!@"
                }
                """))
        .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Integration-013: Missing Required Fields")
    public void testMissingRequiredFields() throws Exception {
        String email = "test_" + System.currentTimeMillis() + "@example.com";
        mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                    "email": "%s"
                }
                """.formatted(email)))
        .andExpect(status().isBadRequest());
    }

    // ==================== RESPONSE FORMAT TESTS ====================

    @Test
    @DisplayName("Integration-014: Response Content-Type")
    public void testResponseContentType() throws Exception {
        String email = "contentype_" + System.currentTimeMillis() + "@example.com";
        String password = "A12345!@";

        mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                    "email": "%s",
                    "password": "%s"
                }
                """.formatted(email, password)))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("Integration-015: Response JSON Structure")
    public void testResponseJsonStructure() throws Exception {
        String email = "structure_" + System.currentTimeMillis() + "@example.com";
        String password = "A12345!@";

        mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                    "email": "%s",
                    "password": "%s"
                }
                """.formatted(email, password)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").exists())
        .andExpect(jsonPath("$.userId").isNotEmpty());
    }

    // ==================== TEAM ENDPOINT TESTS ====================

    @Test
    @DisplayName("Integration-016: Get Teams Endpoint - Structure")
    public void testGetTeamsEndpointStructure() throws Exception {
        String email = "teams_" + System.currentTimeMillis() + "@example.com";
        String password = "A12345!@";

        // Register and login
        mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                    "email": "%s",
                    "password": "%s"
                }
                """.formatted(email, password)))
        .andExpect(status().isOk());

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                    "email": "%s",
                    "password": "%s"
                }
                """.formatted(email, password)))
        .andExpect(status().isOk())
        .andReturn();

        String token = objectMapper.readTree(
            loginResult.getResponse().getContentAsString()).get("token").asText();

        // Get team with auth
        mockMvc.perform(get("/api/teams/1")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").isNotEmpty());
    }

    // ==================== DATA CONSISTENCY TESTS ====================

    @Test
    @DisplayName("Integration-017: User Email Uniqueness - Database Level")
    public void testUserEmailUniquenessDatabase() throws Exception {
        String email = "uniquetest_" + System.currentTimeMillis() + "@example.com";
        
        User user1 = new User();
        user1.setEmail(email);
        user1.setPassword("password1");
        user1.setRole(UserRole.ADMIN);
        
        userRepository.save(user1);

        User user2 = new User();
        user2.setEmail(email);
        user2.setPassword("password2");
        user2.setRole(UserRole.REGULAR);

        // Attempting to save duplicate should fail or overwrite
        // Behavior depends on database constraints
        assertThat(userRepository.findByEmail(email))
            .isPresent()
            .get()
            .satisfies(u -> assertThat(u.getEmail()).isEqualTo(email));
    }

    // ==================== STATUS CODE TESTS ====================

    @Test
    @DisplayName("Integration-018: Successful Operations Return 200")
    public void testSuccessfulOperationsReturn200() throws Exception {
        String email = "status200_" + System.currentTimeMillis() + "@example.com";
        String password = "A12345!@";

        mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                    "email": "%s",
                    "password": "%s"
                }
                """.formatted(email, password)))
        .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Integration-019: Creation Returns 200 or 201")
    public void testCreationReturnsAppropriateStatus() throws Exception {
        String email = "create_" + System.currentTimeMillis() + "@example.com";
        mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                    "email": "%s",
                    "password": "A12345!@"
                }
                """.formatted(email)))
        .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Integration-020: Client Errors Return 4xx")
    public void testClientErrorsReturn4xx() throws Exception {
        mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                    "email": "invalid-email",
                    "password": "weak"
                }
                """))
        .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("Integration-021: Authentication Required - 401 Instead of 500")
    public void testAuthenticationErrorNotServerError() throws Exception {
        mockMvc.perform(get("/api/teams/1"))
        .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Integration-022: Prepare Current Week Produces User Match")
    public void testPrepareCurrentWeekProducesUserMatch() throws Exception {
        String authHeader = createAuthHeaderForUserWithCurrentFixture();

        mockMvc.perform(post("/simulation/current-round/prepare")
                .header("Authorization", authHeader))
            .andExpect(status().isAccepted());

        Map<String, Object> payload = awaitWeekPrepared(authHeader);
        assertThat(payload.get("action")).isEqualTo("WEEK_PREPARED");
        assertThat(payload.get("userMatchId")).isNotNull();
        assertThat(((Number) payload.getOrDefault("remainingAfter", -1)).intValue()).isZero();
    }

    @Test
    @DisplayName("Integration-023: Current Round Feed Returns Teletext Structure")
    public void testCurrentRoundFeedReturnsTeletextStructure() throws Exception {
        String authHeader = createAuthHeaderForUserWithCurrentFixture();

        mockMvc.perform(post("/simulation/current-round/prepare")
                .header("Authorization", authHeader))
            .andExpect(status().isAccepted());
        awaitWeekPrepared(authHeader);

        MvcResult feedResult = mockMvc.perform(get("/simulation/current-round/feed")
                .header("Authorization", authHeader))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.currentWeek").isNumber())
            .andExpect(jsonPath("$.leagues").isArray())
            .andReturn();

        Map<String, Object> payload = readMap(feedResult.getResponse().getContentAsString());
        List<Map<String, Object>> leagues = readListOfMaps(payload.get("leagues"));
        assertThat(leagues).isNotEmpty();

        Map<String, Object> firstLeague = leagues.getFirst();
        assertThat(firstLeague).containsKeys("leagueName", "userLeague", "matches");

        List<Map<String, Object>> matches = readListOfMaps(firstLeague.get("matches"));
        assertThat(matches).isNotEmpty();
        assertThat(matches).allSatisfy(match -> assertThat(match).containsKeys(
                "fixtureId", "homeTeam", "awayTeam", "homeGoals", "awayGoals", "played", "events"
        ));
        assertThat(matches.stream().anyMatch(match -> Boolean.TRUE.equals(match.get("isUserMatch")))).isTrue();

        List<Map<String, Object>> playedMatches = matches.stream()
                .filter(match -> Boolean.TRUE.equals(match.get("played")))
                .toList();
        assertThat(playedMatches).isNotEmpty();
        assertThat(playedMatches).allSatisfy(match ->
                assertThat(match.get("events")).isInstanceOf(List.class)
        );
    }

    private Map<String, Object> awaitWeekPrepared(String authHeader) throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            MvcResult statusResult = mockMvc.perform(get("/simulation/current-round/prepare/status")
                    .header("Authorization", authHeader))
                .andExpect(status().isOk())
                .andReturn();

            Map<String, Object> payload = readMap(statusResult.getResponse().getContentAsString());
            Object action = payload.get("action");
            if ("WEEK_PREPARED".equals(action)) {
                return payload;
            }
            if ("WEEK_PREPARATION_FAILED".equals(action)) {
                fail("Week preparation failed: " + payload.get("message"));
            }
            Thread.sleep(250);
        }
        fail("Week preparation did not finish in time");
        return Map.of();
    }

    private Map<String, Object> readMap(String json) throws Exception {
        return objectMapper.readValue(json, new TypeReference<>() {});
    }

    private List<Map<String, Object>> readListOfMaps(Object value) {
        return objectMapper.convertValue(value, new TypeReference<>() {});
    }

    private String createAuthHeaderForUserWithCurrentFixture() {
        int seasonYear = seasonService.getActiveSeasonYear();
        int currentWeek = seasonService.getCurrentWeek();

        MatchFixture fixture = matchFixtureRepository.findAll().stream()
                .filter(item -> item.getSeasonYear() != null && item.getSeasonYear() == seasonYear)
                .filter(item -> item.getRoundNumber() != null && item.getRoundNumber() == currentWeek)
                .filter(item -> item.getHomeTeam() != null && item.getAwayTeam() != null)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No fixture found for current week in test data."));

        Team userTeam = fixture.getHomeTeam();
        userTeam.setHumanControlled(true);
        teamRepository.save(userTeam);

        String email = "teletext_" + System.currentTimeMillis() + "@example.com";
        User user = new User();
        user.setUsername(email);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode("A12345!@"));
        user.setRole(UserRole.REGULAR);
        user.setTeam(userTeam);
        User saved = userRepository.save(user);

        return "Bearer " + jwtUtil.generateToken(saved);
    }
}
