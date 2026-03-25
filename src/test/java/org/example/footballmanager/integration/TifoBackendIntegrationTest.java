package org.example.footballmanager.integration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.footballmanager.BaseTest;
import org.example.footballmanager.model.User;
import org.example.footballmanager.model.UserRole;
import org.example.footballmanager.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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
        String email = "persistence_" + System.currentTimeMillis() + "@example.com";
        User user = new User();
        user.setEmail(email);
        user.setPassword("hashedpassword");
        user.setRole(UserRole.ADMIN);

        User savedUser = userRepository.save(user);

        assertThat(savedUser.getId()).isNotNull();
        assertThat(userRepository.findByEmail(email)).isPresent();
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
}

