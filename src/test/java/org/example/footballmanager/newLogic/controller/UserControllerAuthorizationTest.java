package org.example.footballmanager.newLogic.controller;

import org.example.commonmanager.model.UserRole;
import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /auth} is {@code permitAll} — and that is correct for two of its three routes and wrong for the third.
 *
 * <p><b>Why this controller needs a test at all.</b> It sits on the one prefix the security config opens to
 * everybody, {@code /auth/**}, because {@code login} and {@code register} must work before anyone has an
 * account. A prefix that is public for the login form is a prefix where a new endpoint is <b>public by
 * default</b>, silently. Nothing on this controller states a rule, so nothing states the rule for the next
 * route somebody adds to it.
 *
 * <p><b>{@code /auth/me} is the interesting one.</b> It is under a public prefix, so it reaches the method with
 * no principal at all, and it is the method — not the filter chain — that answers 401:
 * {@code if (user == null) return ResponseEntity.status(401).build();}. That is load-bearing rather than
 * incidental, and it is invisible from the security config, so it is asserted here directly. If somebody
 * removes that null check, a {@code NullPointerException} replaces a 401 and the SPA sees a 500.
 *
 * <p><b>Nothing here returns another user's data, and that is the guarantee worth having.</b> Every read
 * resolves the caller from the token, so there is no id in any of these routes for a caller to change. A test
 * that only proved "it needs a token" would not notice a future {@code /auth/me/{id}}.
 */
@Import(ControllerAuthFixture.class)
class UserControllerAuthorizationTest extends BaseTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ControllerAuthFixture auth;

    private Team myClub;

    @BeforeEach
    @Transactional
    void aClubToBeTheManagerOf() {
        myClub = auth.club("Auth");
    }

    // ── /auth/me: public prefix, method-level guard ─────────────────────────────────────────────────

    @Test
    @DisplayName("an anonymous caller gets 401 from /auth/me, not a redirect and not a 500")
    void anAnonymousCallerGets401FromMe() throws Exception {
        int code = mockMvc.perform(get("/auth/me")).andReturn().getResponse().getStatus();

        // Exactly 401. Not "401 or 302": the prefix is permitAll, so this 401 is the method's own and nothing
        // else could produce it. A redirect here would mean the security config had started guarding the
        // prefix, which would break the login form.
        assertEquals(401, code, "/auth/me is under a public prefix, so its 401 is the method's own");
    }

    @Test
    @DisplayName("an anonymous caller gets 401 from /auth/me even with an Accept header of HTML")
    void anAnonymousCallerGets401FromMeOnAGet() throws Exception {
        int code = mockMvc.perform(get("/auth/me").accept(MediaType.TEXT_HTML))
                .andReturn().getResponse().getStatus();

        assertEquals(401, code,
                "a GET under a public prefix must not be redirected to the login page: the method guards it");
    }

    @Test
    @Transactional
    @DisplayName("a logged-in manager gets his own account, not a redirect")
    void aManagerGetsHisOwnAccount() throws Exception {
String body = mockMvc.perform(get("/auth/me")
                .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub)))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();

 // Whitespace is stripped before matching. Jackson pretty-prints in a Spring Boot default, so the
 // response reads `"role" : "REGULAR"` and an exact substring never matches. Asserting on pretty-printed
 // spacing is a test that fails for a reason that has nothing to do with authorization.
 assertTrue(tight(body).contains("\"role\":\"REGULAR\""),
 "the response should carry the caller's own role: " + body);
assertTrue(tight(body).contains("\"" + myClub.getName() + "\""),
 "the response should name the club the caller runs: " + body);
}

    /**
     * The caller is taken from the token, so there is nothing to forge.
     *
     * <p>Asserted as "an unknown manager's club is not in my answer" rather than as a field count, because a
     * field count would break the moment a field is added for an unrelated reason.
     */
    @Test
    @Transactional
    @DisplayName("a manager cannot read another account by asking")
    void aManagerCannotReadAnotherAccount() throws Exception {
        Team somebodyElses = auth.club("Not mine");

        String body = mockMvc.perform(get("/auth/me")
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(tight(body).contains("\"" + myClub.getName() + "\""),
                "his own club should be in the answer: " + body);
        assertTrue(!tight(body).contains("\"" + somebodyElses.getName() + "\""),
                "another manager's club appeared in the answer: " + body);
    }

    @Test
    @Transactional
    @DisplayName("an administrator is not locked out of /auth/me")
    void anAdministratorIsNotLockedOut() throws Exception {
        mockMvc.perform(get("/auth/me").header("Authorization", auth.bearer(UserRole.OWNER)))
                .andExpect(status().isOk());
    }

    // ── The two public routes, asserted so "public" stays deliberate ───────────────────────────────

    @Test
    @DisplayName("login stays reachable without a token — a guard here would lock everybody out")
    void loginStaysPublic() throws Exception {
        int code = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody@example.com\",\"password\":\"wrong\"}"))
                .andReturn().getResponse().getStatus();

        assertTrue(code != 302,
                "the login form was redirected, which means the public prefix stopped being public");
        assertTrue(code == 401 || code == 400 || code == 500,
                "a bad password should be refused, got " + code);
    }

    @Test
    @DisplayName("registration stays reachable without a token")
    void registrationStaysPublic() throws Exception {
        int code = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn().getResponse().getStatus();

        assertTrue(code != 302,
                "the registration form was redirected, which means the public prefix stopped being public");
    }

    /**
     * The password never comes back out.
     *
     * <p>{@code /auth/me} builds a {@code UserDTO} by hand rather than serialising the entity, so this is a
     * property of that DTO and not of the endpoint. A regression that returned the entity would publish the
     * bcrypt hash, and nothing else on this controller would notice.
     */
    @Test
    @Transactional
    @DisplayName("no response on this controller carries a password hash")
    void noResponseCarriesAPassword() throws Exception {
        String body = mockMvc.perform(get("/auth/me")
                        .header("Authorization", auth.bearerManaging(UserRole.REGULAR, myClub)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(!tight(body).contains("\"password\""),
                "the account response carries a password field: " + body);
    }

    /** Jackson pretty-prints in a Spring Boot default, so every substring match here ignores whitespace. */
    private static String tight(String body) {
        return body.replace(" ", "").replace("\n", "").replace("\r", "");
    }
}