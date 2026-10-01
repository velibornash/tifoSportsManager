package org.example.footballmanager.newLogic.controller;

import org.example.footballmanager.BaseTest;
import org.example.commonmanager.model.User;
import org.example.commonmanager.model.UserRole;
import org.example.commonmanager.repository.UserRepository;
import org.example.commonmanager.util.JwtUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Advancing the world is an administrator action, and the server says so.
 *
 * <p><b>This exists because the protection was a hidden button.</b> {@code dashboard.js} hid the advance
 * buttons from non-administrators, and that was the whole of it: every endpoint took
 * {@code .anyRequest().authenticated()}, so any logged-in manager could move the world with a direct call —
 * every club in every country, not just his own. The codebase already writes the rule down, in
 * {@code CountryController}: <i>"a hidden button is not a permission."</i>
 *
 * <p>Two things are asserted that a naive role check would get wrong:
 *
 * <ul>
 *   <li><b>The owner is not locked out of his own game.</b> A fix that forbids the wrong set of roles is
 *       worse than the hole it closes, and "the owner can still run his world" has to be a test rather
 *       than an assumption.</li>
 *   <li><b>A huge {@code amount} is refused.</b> {@code amount = 2147483647} ran a two-billion-iteration
 *       loop, and {@code amount * 24} overflows int above 89,478,485 — so a large "days" request asked the
 *       clock to go <b>backwards</b>.</li>
 * </ul>
 */
class WorldAdvanceAuthorizationTest extends BaseTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtUtil jwtUtil;

    @Autowired
    UserRepository users;

    @Test
    @DisplayName("a regular manager cannot advance the world")
    void aRegularManagerIsRefused() throws Exception {
        mockMvc.perform(post("/api/game-clock/advance")
                        .header("Authorization", bearerAs(UserRole.REGULAR))
                        .param("unit", "hour")
                        .param("amount", "1"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a regular manager cannot run the jobs either")
    void aRegularManagerCannotRunJobs() throws Exception {
        mockMvc.perform(post("/api/jobs/run-due")
                        .header("Authorization", bearerAs(UserRole.REGULAR)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a regular manager cannot jump to a kickoff hour")
    void aRegularManagerCannotAdvanceToHour() throws Exception {
        mockMvc.perform(post("/api/game-clock/advance-to-hour")
                        .header("Authorization", bearerAs(UserRole.REGULAR))
                        .param("hour", "20"))
                .andExpect(status().isForbidden());
    }

    /** The owner's own world must still be his to run. */
    @Test
    @DisplayName("the owner is not locked out")
    void theOwnerCanStillAdvance() throws Exception {
        mockMvc.perform(post("/api/game-clock/advance")
                        .header("Authorization", bearerAs(UserRole.OWNER))
                        .param("unit", "hour")
                        .param("amount", "1"))
                .andExpect(status().isOk());
    }

    /**
     * The clock must not be asked to run for two billion iterations, and must not be sent backwards by an
     * {@code amount * 24} that overflows.
     */
    @Test
    @DisplayName("an absurd or overflowing advance is refused")
    void anAbsurdAdvanceIsRefused() throws Exception {
        mockMvc.perform(post("/api/game-clock/advance")
                        .header("Authorization", bearerAs(UserRole.OWNER))
                        .param("unit", "hour")
                        .param("amount", "2147483647"))
                .andExpect(status().isBadRequest());

        // 89,478,486 days * 24 overflows int. This is the request that used to move the clock backwards.
        mockMvc.perform(post("/api/game-clock/advance")
                        .header("Authorization", bearerAs(UserRole.OWNER))
                        .param("unit", "day")
                        .param("amount", "89478486"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an anonymous caller is refused")
    void anAnonymousCallerIsRefused() throws Exception {
        mockMvc.perform(post("/api/game-clock/advance")
                        .param("unit", "hour")
                        .param("amount", "1"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The accounts are created here rather than read from the seed.
     *
     * <p>An earlier version of this test looked up {@code kecko@example.com} and failed with "no such user"
     * — the {@code test} profile runs on H2 with its own seed, so a test that depends on a particular row
     * in a particular database passes on the developer's machine and fails in CI for a reason that has
     * nothing to do with authorization. A test about a role should build the role, not find it.
     */
    private User userWithRole(String email, UserRole role) {
        User user = new User();
        user.setUsername(email);
        user.setEmail(email);
        user.setPassword("test-password-not-a-real-hash");
        user.setDisplayName(email);
        user.setRole(role);
        user.setPlusSubscription(false);
        return users.save(user);
    }

    /**
     * A token for a freshly created user of the given role.
     *
     * <p>The address is unique per call because these tests share one database: {@code @SpringBootTest}
     * does not roll back between methods, so a fixed address made the third method's lookup return two
     * users and the JWT filter refused the token — every test failed at 401 for a reason that had nothing
     * to do with authorization. Creating the user each time, with a fresh address, is both correct and the
     * only version of this that can be run in any order.
     */
    private String bearerAs(UserRole role) {
        String email = "world-advance-" + role.name().toLowerCase() + "-" + UUID.randomUUID() + "@test.local";
        return "Bearer " + jwtUtil.generateToken(userWithRole(email, role));
    }
}
