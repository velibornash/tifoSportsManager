package org.example.commonmanager.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.commonmanager.model.User;
import org.example.commonmanager.model.UserRole;
import org.example.commonmanager.repository.UserRepository;
import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.controller.ControllerAuthFixture;
import org.example.footballmanager.newLogic.model.Team;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Somebody else's profile: what it shows, and what it must never show.
 *
 * <p><b>The absence assertions are the point of this class.</b> A public profile is the surface where the
 * old community chat's mistake would repeat: {@code CommunityController.toDto} gates an applicant's
 * <b>email</b> behind {@code adminViewer} and still let the <b>username</b> through, which is what P0-17
 * was about. The structural answer used here is that {@code /users/{id}/profile} has <b>no email field
 * to gate</b> — so these tests assert on absence, not on a boolean.
 *
 * <p>A test that asserted "the email is not 200" would pass against a body that omits the key entirely.
 * Asserting the <b>body string does not contain the address</b> is what catches a field added back.
 */
@Import(ControllerAuthFixture.class)
class UserProfileControllerTest extends BaseTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ControllerAuthFixture auth;

    @Autowired
    ObjectMapper json;

    @Autowired
    UserRepository users;

    private String ownerToken;
    private Long subjectId;
    private Team subjectClub;

    @BeforeEach
    @Transactional
    void aManagerWithAClub() throws Exception {
        ownerToken = auth.bearer(UserRole.OWNER);
        subjectClub = auth.club("Profile subject");
        subjectId = users.save(aUser(subjectClub, "Velja", "velja@example.com")).getId();
    }

    // ── The profile ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a manager's profile shows his club, name and role")
    void aProfileShowsWhoHeIs() throws Exception {
        mockMvc.perform(get("/users/{id}/profile", subjectId)
                        .header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Velja"))
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.clubId").value(subjectClub.getId()))
                .andExpect(jsonPath("$.clubName").value(subjectClub.getName()))
                .andExpect(jsonPath("$.hasChosenName").value(true));
    }

    @Test
    @DisplayName("the email address is not in the body anywhere, not merely gated")
    void theEmailIsAbsentRatherThanGated() throws Exception {
        String body = mockMvc.perform(get("/users/{id}/profile", subjectId)
                        .header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("velja@example.com"),
                "the profile leaked the email address: " + body);
        // And the username, which the old chat exposed alongside a correctly-gated email (P0-17).
        assertFalse(body.contains("username"),
                "the profile carries a username field, which is the other half of P0-17: " + body);
        assertFalse(body.contains("lastSeenAt"),
                "the profile carries a last-seen timestamp nobody asked for: " + body);
    }

    @Test
    @DisplayName("an account with no chosen name shows its login, and says it has none")
    void noChosenNameFallsBackAndIsLabelled() throws Exception {
        User saved = aUser(null, null, "nameless@example.com");
        Long id = users.save(saved).getId();
        assertNull(saved.getDisplayName(), "the fixture must have no name, or nothing is being tested");

        // The fallback is the USERNAME, not the email — those are the same string for a real account
        // (username is an address that doubles as the login) and different ones in this fixture, which
        // is what makes the assertion about the wrong field rather than passing by coincidence.
        mockMvc.perform(get("/users/{id}/profile", id).header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value(saved.getUsername()))
                .andExpect(jsonPath("$.hasChosenName").value(false));
    }

    @Test
    @DisplayName("an anonymous caller cannot read a profile")
    void anonymousIsRefused() throws Exception {
        int status = mockMvc.perform(get("/users/{id}/profile", subjectId))
                .andReturn().getResponse().getStatus();
        assertRefused(status, "GET /users/{id}/profile");
    }

    @Test
    @DisplayName("an unknown account is a 404")
    void unknownIsANotFound() throws Exception {
        mockMvc.perform(get("/users/{id}/profile", 999_999_999L).header("Authorization", ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an ordinary manager may read another manager's profile, like a club profile")
    void aRegularManagerMayRead() throws Exception {
        String regular = auth.bearer(UserRole.REGULAR);
        mockMvc.perform(get("/users/{id}/profile", subjectId).header("Authorization", regular))
                .andExpect(status().isOk());
    }

    // ── The display name, which was never writable ────────────────────────────────────────────────

    @Test
    @Transactional
    @DisplayName("a manager can set his own name, which nothing in the codebase ever allowed")
    void aManagerCanSetHisName() throws Exception {
        String token = auth.bearerManaging(UserRole.REGULAR, subjectClub);

        mockMvc.perform(patch("/users/me/display-name")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("displayName", "Velja"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Velja"));

        assertEquals("Velja", users.findById(subjectId).orElseThrow().getDisplayName());
    }

    @Test
    @DisplayName("an empty name is refused rather than stored")
    void anEmptyNameIsRefused() throws Exception {
        String token = auth.bearerManaging(UserRole.REGULAR, subjectClub);

        for (String bad : new String[]{"", "   ", "\t"}) {
            mockMvc.perform(patch("/users/me/display-name")
                            .header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("displayName", bad))))
                    .andExpect(status().isBadRequest());
        }

        // The important one: an empty displayName must not overwrite a real one, because every
        // reader falls back to the username on blank — so storing "" would look like it worked and
        // change the name on screen.
        assertEquals("Velja", users.findById(subjectId).orElseThrow().getDisplayName(),
                "a rejected blank name still overwrote the real one");
    }

    @Test
    @DisplayName("an over-long name is refused rather than truncated by the column")
    void anOverlongNameIsRefused() throws Exception {
        String token = auth.bearerManaging(UserRole.REGULAR, subjectClub);

        mockMvc.perform(patch("/users/me/display-name")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("displayName", "x".repeat(41)))))
                .andExpect(status().isBadRequest());

        assertEquals("Velja", users.findById(subjectId).orElseThrow().getDisplayName());
    }

    @Test
    @Transactional
    @DisplayName("a userId in the body is ignored, which is the copy-paste this route is built to refuse")
    void aUserIdInTheBodyIsIgnored() throws Exception {
        // The realistic attack on /users/me/display-name is not changing the path — it is somebody
        // adding \"userId\" to the body because a neighbouring endpoint takes one. If the handler ever
        // reads that key it becomes a rename-anyone endpoint with no new route and no new review.
        User victim = users.save(aUser(null, "Victim", "victim@example.com"));
        String callerToken = auth.bearerManaging(UserRole.REGULAR, subjectClub);

        mockMvc.perform(patch("/users/me/display-name")
                        .header("Authorization", callerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("displayName", "Renamed", "userId", victim.getId()))))
                .andExpect(status().isOk());

        assertEquals("Victim", users.findById(victim.getId()).orElseThrow().getDisplayName(),
                "a userId in the request body renamed somebody else's account");
    }

    @Test
    @DisplayName("a caller cannot set somebody else's name, because the route has no id to change")
    void oneCannotRenameAnother() throws Exception {
        // There is no /users/{id}/display-name. That is the whole design: an id in the path is an
        // invitation to a future copy-paste, and the caller's own name never needs to travel as one.
        mockMvc.perform(patch("/users/{id}/display-name", subjectId)
                        .header("Authorization", auth.bearer(UserRole.OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("displayName", "Hijacked"))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an anonymous caller cannot set a name")
    void anonymousCannotSetAName() throws Exception {
        int status = mockMvc.perform(patch("/users/me/display-name")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("displayName", "Nobody"))))
                .andReturn().getResponse().getStatus();
        assertRefused(status, "PATCH /users/me/display-name");
    }

    /**
     * 401, 403 or a redirect are all correct; the {@code != 200} is the part that matters.
     *
     * <p>Asserting one exact code would pin whichever of those the chain happens to choose and tell
     * the next reader nothing.
     */
    private static void assertRefused(int status, String what) {
        assertTrue(status == HttpStatus.UNAUTHORIZED.value()
                        || status == HttpStatus.FORBIDDEN.value()
                        || status == HttpStatus.FOUND.value(),
                what + " answered " + status + " to an anonymous caller");
        assertNotEquals(HttpStatus.OK.value(), status, what + " served an anonymous caller");
    }

    private User aUser(Team club, String displayName, String email) {
        User user = new User();
        user.setUsername("profile-" + java.util.UUID.randomUUID() + "@test.local");
        user.setEmail(email);
        user.setPassword("not-a-real-hash");
        user.setDisplayName(displayName);
        user.setRole(UserRole.OWNER);
        user.setPlusSubscription(false);
        user.setFootballTeam(club);
        return user;
    }
}