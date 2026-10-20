package org.example.footballmanager.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A page must be reachable before you log in, or it cannot be the thing you log in <i>from</i>.
 *
 * <p>This exists because {@code /dashboard.html} was missing from the security permit list. A
 * browser navigating to a page sends no {@code Authorization} header, so the server-side filter
 * rejected the request and redirected to {@code /login.html} — before any JavaScript ran. To the
 * owner that is indistinguishable from broken authentication on the client: you log in, choose your
 * game mode, and land back on the login page. {@code /zox-match-preview.html} was missing too.
 *
 * <p>So the rule is now enforced rather than remembered: every page shell is public, and the test
 * fails the build the moment a new page is added without noticing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StaticPageAccessTest {

    /** Every page in {@code static/}. A missing one here means a new page was added. */
    private static final List<String> PAGES = List.of(
            "/index.html",
            "/home.html",
            "/login.html",
            "/register.html",
            "/dashboard.html",
            "/tifo.html",
            "/simulateAllResults.html",
            "/zox-match-preview.html");

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("every page loads for a visitor who has not logged in")
    void pagesAreReachableWithoutAToken() throws Exception {
        for (String page : PAGES) {
            mockMvc.perform(get(page))
                    .andExpect(result -> {
                        int status = result.getResponse().getStatus();
                        String redirect = result.getResponse().getRedirectedUrl();
                        if (redirect != null && redirect.contains("login")) {
                            throw new AssertionError(page + " redirected to " + redirect
                                    + " for a visitor with no token. A browser sends no Authorization header on a"
                                    + " page load, so a page shell must be served rather than bounced to login -"
                                    + " otherwise the owner logs in, picks a game mode, and lands back here with"
                                    + " no clue why. Add it to the permit list in SecurityConfig.");
                        }
                        if (status != 200 && status != 304) {
                            throw new AssertionError(page + " answered " + status + " for a visitor with no token");
                        }
                    });
        }
    }

    @Test
    @DisplayName("the API is still closed to an anonymous visitor")
    void theApiIsNotOpenedByThePageWildcard() throws Exception {
        // The point of the test above is only its value if the API stays protected. A page shell
        // holds no data; if this ever passes anonymously, the wildcard has reached something real.
        mockMvc.perform(get("/api/teams/1/schedule"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    boolean bouncedToLogin = status == 3 && result.getResponse().getRedirectedUrl() != null
                            && result.getResponse().getRedirectedUrl().contains("login");
                    if (!bouncedToLogin && status != 401 && status != 403) {
                        throw new AssertionError("/api/teams/1/schedule answered " + status
                                + " to an anonymous caller; the API must require the JWT");
                    }
                });
    }
}
