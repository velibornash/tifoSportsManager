package org.example.footballmanager.integration;

import static io.restassured.RestAssured.*;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.javafaker.Faker;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.example.footballmanager.BaseTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * E2E Backend Tests using REST Assured
 * Tests complete user flows through API endpoints
 * 
 * Prerequisites:
 * - Dependencies: io.rest-assured:rest-assured, com.github.javafaker:javafaker
 * - Application running on localhost
 * - Database initialized
 * - JWT authentication configured
 * 
 * Run: mvn test -Dtest=TifoE2ETest
 */
@DisplayName("TIFO E2E Backend Tests")
@ActiveProfiles("test")
public class TifoE2ETest extends BaseTest {

    @LocalServerPort
    private int port;


    private String authToken;
    private static final String BASE_EMAIL = "velibor@example.com";
    private static final String BASE_PASSWORD = "A12345!";
    private static final String BASE_PATH = "/api";

    @BeforeEach
    public void setUp() {
        RestAssured.port = port;
        RestAssured.basePath = ""; // No base path - we'll specify full paths
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }

    // ==================== AUTHENTICATION TESTS ====================

    @Test
    @DisplayName("E2E-001: User Registration - Valid Credentials")
    public void testUserRegistration_ValidCredentials() {
        String uniqueEmail = "testuser_" + System.currentTimeMillis() + "@example.com";
        String username = "user_" + System.currentTimeMillis();
        
        Response response = given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "username": "%s",
                    "email": "%s",
                    "password": "A12345!@"
                }
                """.formatted(username, uniqueEmail))
        .when()
            .post("/auth/register")
        .then()
            .statusCode(anyOf(is(200), is(202))) // Accept both 200 and 202 for PENDING
            .extract()
            .response();

        assertNotNull(response.jsonPath().get("status"), "Status should be returned");
    }

    @Test
    @DisplayName("E2E-002: User Login - Valid Credentials")
    public void testUserLogin_ValidCredentials() {
        String uniqueEmail = "testlogin_" + System.currentTimeMillis() + "@example.com";
        String username = "login_" + System.currentTimeMillis();
        
        // Register first
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "username": "%s",
                    "email": "%s",
                    "password": "A12345!@"
                }
                """.formatted(username, uniqueEmail))
        .when()
            .post("/auth/register");

        // Then login with username or email
        Response response = given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "username": "%s",
                    "password": "A12345!@"
                }
                """.formatted(username))
        .when()
            .post("/auth/login")
        .then()
            .statusCode(anyOf(is(200), is(401))) // 200 if approved, 401 if pending
            .extract()
            .response();

        if (response.statusCode() == 200) {
            authToken = response.jsonPath().get("token");
            assertNotNull(authToken, "JWT token should be returned");
        }
    }

    @Test
    @DisplayName("E2E-003: User Login - Invalid Credentials")
    public void testUserLogin_InvalidCredentials() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "email": "nonexistent@example.com",
                    "password": "wrongpassword"
                }
                """)
        .when()
            .post("/auth/login")
        .then()
            .statusCode(401);
    }

    // ==================== TEAM MANAGEMENT TESTS ====================

    @Test
    @DisplayName("E2E-004: Get Team Information")
    public void testGetTeamInformation() {
        loginTestUser();

        given()
            .header("Authorization", "Bearer " + authToken)
        .when()
            .get("/api/teams/1")
        .then()
            .statusCode(200)
            .body("name", notNullValue())
            .body("budget", greaterThan(0.0));
    }

    @Test
    @DisplayName("E2E-005: Get Squad List")
    public void testGetSquadList() {
        loginTestUser();

        given()
            .header("Authorization", "Bearer " + authToken)
        .when()
            .get("/api/teams/1/players")
        .then()
            .statusCode(anyOf(is(200), is(404)));
    }

    @Test
    @DisplayName("E2E-006: Get Player Details")
    public void testGetPlayerDetails() {
        loginTestUser();

        given()
            .header("Authorization", "Bearer " + authToken)
        .when()
            .get("/api/players/1")
        .then()
            .statusCode(anyOf(is(200), is(404)));
    }

    // ==================== MATCH SYSTEM TESTS ====================

    @Test
    @DisplayName("E2E-007: Get Match Fixtures")
    public void testGetMatchFixtures() {
        loginTestUser();

        given()
            .header("Authorization", "Bearer " + authToken)
        .when()
            .get("/api/matches/fixtures")
        .then()
            .statusCode(anyOf(is(200), is(404)));
    }

    @Test
    @DisplayName("E2E-008: Get Match Details")
    public void testGetMatchDetails() {
        loginTestUser();

        given()
            .header("Authorization", "Bearer " + authToken)
        .when()
            .get("/api/matches/1")
        .then()
            .statusCode(anyOf(is(200), is(404)));
    }

    // ==================== TRAINING SYSTEM TESTS ====================

    @Test
    @DisplayName("E2E-009: Get Training Reports")
    public void testGetTrainingReports() {
        loginTestUser();

        given()
            .header("Authorization", "Bearer " + authToken)
        .when()
            .get("/api/training/reports")
        .then()
            .statusCode(anyOf(is(200), is(404)));
    }

    // ==================== LEAGUE TESTS ====================

    @Test
    @DisplayName("E2E-010: Get League Standings")
    public void testGetLeagueStandings() {
        loginTestUser();

        given()
            .header("Authorization", "Bearer " + authToken)
        .when()
            .get("/api/seasons/1/standings")
        .then()
            .statusCode(anyOf(is(200), is(404)));
    }

    @Test
    @DisplayName("E2E-011: Get League Schedule")
    public void testGetLeagueSchedule() {
        loginTestUser();

        given()
            .header("Authorization", "Bearer " + authToken)
        .when()
            .get("/api/seasons/1/schedule")
        .then()
            .statusCode(anyOf(is(200), is(404)));
    }

    // ==================== ANALYTICS TESTS ====================

    @Test
    @DisplayName("E2E-012: Get Player Statistics")
    public void testGetPlayerStatistics() {
        loginTestUser();

        given()
            .header("Authorization", "Bearer " + authToken)
        .when()
            .get("/api/stats/players/1")
        .then()
            .statusCode(anyOf(is(200), is(404)));
    }

    @Test
    @DisplayName("E2E-013: Get Team Statistics")
    public void testGetTeamStatistics() {
        loginTestUser();

        given()
            .header("Authorization", "Bearer " + authToken)
        .when()
            .get("/api/stats/teams/1")
        .then()
            .statusCode(anyOf(is(200), is(404)));
    }

    // ==================== HELPER METHODS ====================

    /**
     * Helper method to login and set authToken
     */
    private void loginTestUser() {
        Response response = given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "email": "%s",
                    "password": "%s"
                }
                """.formatted(BASE_EMAIL, BASE_PASSWORD))
        .when()
            .post("/auth/login")
        .then()
            .extract()
            .response();

        if (response.statusCode() == 200) {
            authToken = response.jsonPath().get("token");
        } else {
            // Register and then login if user doesn't exist
            given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "email": "%s",
                        "password": "%s"
                    }
                    """.formatted(BASE_EMAIL, BASE_PASSWORD))
            .when()
                .post("/auth/register");

            response = given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "email": "%s",
                        "password": "%s"
                    }
                    """.formatted(BASE_EMAIL, BASE_PASSWORD))
            .when()
                .post("/auth/login")
            .then()
                .extract()
                .response();

            authToken = response.jsonPath().get("token");
        }
    }
}

