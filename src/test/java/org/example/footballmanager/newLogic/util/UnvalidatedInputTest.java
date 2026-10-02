package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.BaseTest;
import org.example.commonmanager.model.User;
import org.example.commonmanager.model.UserRole;
import org.example.commonmanager.util.JwtUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A caller cannot choose which column to sort by, and cannot mint a club or a player.
 *
 * <p><b>C6, two halves.</b>
 *
 * <p>The first: four controllers took a {@code sortBy} parameter straight into {@code Sort.by(sortBy)}, which
 * hands the caller the column name. They could order by a column the page never intended to expose, order by
 * an unindexed column — a two-character request turning into a full sort of every player of every club in
 * every country — and a bad name failed deep inside Hibernate with a message naming the entity and its columns.
 *
 * <p>The second: {@code PlayerController} and {@code TeamController} each had a create endpoint that took a
 * raw entity, saved it with no administrator check and no validation, and returned the entity. A body with an
 * {@code id} set would overwrite an existing row through {@code save()}.
 */
class UnvalidatedInputTest extends BaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;
    @Autowired org.example.commonmanager.repository.UserRepository users;

    // ── the sort whitelist ───────────────────────────────────────────────────

    @Test
    @DisplayName("an allowed column sorts")
    void anAllowedColumnSorts() {
        var sort = SortWhitelist.of("name", "asc", "sortBy", Set.of("id", "name", "rating"));
        assertEquals("name", sort.getOrderFor("name").getProperty());
        assertTrue(sort.getOrderFor("name").isAscending());
    }

    @Test
    @DisplayName("an unknown column is refused, and the message names what is allowed")
    void anUnknownColumnIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> SortWhitelist.of("salary", "asc", "sortBy", Set.of("id", "name", "rating")));
        assertTrue(refused.getMessage().contains("salary"),
                "the caller should learn which column they asked for: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("name"),
                "the refusal should say what is allowed instead: " + refused.getMessage());
    }

    @Test
    @DisplayName("a column that is not a property never reaches the database")
    void aColumnThatIsNotAPropertyIsRefused() {
        // The injection-shaped case: not a valid identifier at all, so it could never have been a column.
        assertThrows(IllegalArgumentException.class,
                () -> SortWhitelist.of("1=1; drop table player", "asc", "sortBy", Set.of("id", "name")));
    }

    @Test
    @DisplayName("a real endpoint refuses an unknown sort column with a 400")
    void anEndpointRefusesAnUnknownSort() throws Exception {
        mockMvc.perform(get("/players/paged").param("sortBy", "salary")
                        .header("Authorization", bearerFor(UserRole.OWNER)))
                .andExpect(status().isBadRequest());
    }

    // ── create endpoints ─────────────────────────────────────────────────────

    @Test
    @DisplayName("a regular manager cannot create a club")
    void aRegularManagerCannotCreateAClub() throws Exception {
        mockMvc.perform(post("/teams/create")
                        .header("Authorization", bearerFor(UserRole.REGULAR))
                        .contentType("application/json")
                        .content("{\"name\":\"Injected club\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a regular manager cannot create a player")
    void aRegularManagerCannotCreateAPlayer() throws Exception {
        mockMvc.perform(post("/players/create")
                        .header("Authorization", bearerFor(UserRole.REGULAR))
                        .param("teamId", "1")
                        .contentType("application/json")
                        .content("{\"name\":\"Injected player\",\"age\":22,\"position\":\"ATT\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an administrator creating a club with no name is refused with a 400")
    void aNamelessClubIsRefused() throws Exception {
        mockMvc.perform(post("/teams/create")
                        .header("Authorization", bearerFor(UserRole.OWNER))
                        .contentType("application/json")
                        .content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    /** Created here rather than seeded: a test about a role should build the role, not find it. */
    private String bearerFor(UserRole role) {
        User user = new User();
        user.setUsername("c6-" + UUID.randomUUID() + "@test.local");
        user.setEmail(user.getUsername());
        user.setPassword("not-a-real-hash");
        user.setDisplayName("C6 tester");
        user.setRole(role);
        user.setPlusSubscription(false);
        return "Bearer " + jwtUtil.generateToken(users.save(user));
    }
}
