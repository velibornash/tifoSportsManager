package org.example.footballmanager.newLogic.service;

import org.example.commonmanager.model.User;
import org.example.commonmanager.model.UserRole;
import org.example.footballmanager.newLogic.model.Junior;
import org.example.footballmanager.newLogic.model.JuniorStatus;
import org.example.footballmanager.newLogic.model.Team;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The academy half of the PLUS rule (Sprint 5.2).
 *
 * <p>This exists because the gate was <b>written and never wired</b>. `PlusFeatureService` shipped with
 * five public methods and zero callers, so the owner's rule that talent is paid information was
 * implemented, documented and unit-tested as a service and then never applied to a single DTO the UI
 * read. `JuniorAcademyItemDTO.talent` carried the exact value to everyone.
 *
 * <p>So the test that matters here is the negative one: a regular manager, looking at his own academy,
 * must be told nothing. A test that only checks the happy path would pass against the broken version.
 */
class PlusJuniorVisibilityTest {

    private static User user(UserRole role) {
        User u = new User();
        u.setRole(role);
        return u;
    }

    private static Team team(Long id) {
        Team t = new Team();
        t.setId(id);
        return t;
    }

    private static Junior juniorAt(Long teamId) {
        Junior j = new Junior();
        j.setTeam(team(teamId));
        j.setTalent(8.4);
        j.setStatus(JuniorStatus.ACTIVE);
        return j;
    }

    // Both collaborators are only needed to resolve a *club* for a real user. Every check here is
    // team-id based, so mocks rather than null: null would have thrown the moment the service grew a
    // second collaborator, and a test that cannot compile is a cheaper failure than one that hides a
    // dependency behind a null that happens not to be dereferenced.
    private final PlusFeatureService plus = new PlusFeatureService(
            org.mockito.Mockito.mock(org.example.footballmanager.newLogic.repository.TeamRepository.class),
            new org.example.footballmanager.newLogic.service.ClubOwnershipLinker(
                    org.mockito.Mockito.mock(org.example.footballmanager.newLogic.repository.TeamRepository.class),
                    org.mockito.Mockito.mock(org.example.commonmanager.repository.UserRepository.class)));

    @Test
    @DisplayName("a regular manager sees nothing about his own academy's talent")
    void regularUserIsRefused() {
        // The regression this whole class exists for. Before Sprint 5.2 this manager saw 8.4, in his
        // own academy, because nothing asked who was asking.
        assertFalse(plus.canSeeJunior(juniorAt(1L), user(UserRole.REGULAR), 1L));
    }

    @Test
    @DisplayName("a PLUS subscriber sees his own academy's talent")
    void plusUserIsAllowed() {
        assertTrue(plus.canSeeJunior(juniorAt(1L), user(UserRole.PLUS), 1L));
    }

    @Test
    @DisplayName("a PLUS subscriber does not see a rival academy, and neither does an owner")
    void ownClubStillApplies() {
        // The subscription is the outer gate; the club is the inner one. Passing both is required.
        assertFalse(plus.canSeeJunior(juniorAt(2L), user(UserRole.PLUS), 1L),
                "another club's academy is not yours, whatever you pay");
        assertFalse(plus.canSeeJunior(juniorAt(2L), user(UserRole.OWNER), 1L),
                "OWNER bypasses the subscription, not the own-club rule");
        assertFalse(plus.canSeeJunior(juniorAt(2L), user(UserRole.DEV), 1L));
        assertFalse(plus.canSeeJunior(juniorAt(2L), user(UserRole.ADMIN), 1L));
    }

    @Test
    @DisplayName("owner, dev and admin bypass the subscription but only for their own club")
    void privilegedRolesBypassTheSubscription() {
        for (UserRole role : new UserRole[] { UserRole.OWNER, UserRole.DEV, UserRole.ADMIN }) {
            assertTrue(plus.canSeeJunior(juniorAt(1L), user(role), 1L),
                    role + " should see his own academy's talent without a subscription");
        }
    }

    @Test
    @DisplayName("an unresolvable viewer is refused rather than defaulted to yes")
    void failsClosed() {
        // Defaulting to yes would leak every junior in the database to anyone whose user or club
        // lookup failed, which is the wrong way round for a paid feature.
        assertFalse(plus.canSeeJunior(juniorAt(1L), null, 1L), "a null user is not a subscriber");
        assertFalse(plus.canSeeJunior(juniorAt(1L), user(UserRole.REGULAR), null), "a null club is not your club");
        assertFalse(plus.canSeeJunior(null, user(UserRole.PLUS), 1L), "there is nothing to see");
        assertFalse(plus.hasPlus(null));
        assertFalse(plus.hasPlus(user(null)), "a user with no role is not a subscriber");
    }

    @Test
    @DisplayName("a junior with no club is never visible")
    void clublessJuniorIsHidden() {
        Junior orphan = new Junior();
        orphan.setTalent(9.9);
        assertFalse(plus.canSeeJunior(orphan, user(UserRole.OWNER), 1L),
                "an academy junior with no club cannot be your academy junior");
    }

    @Test
    @DisplayName("hasPlus covers exactly the paid and privileged roles")
    void hasPlusIsExact() {
        assertTrue(plus.hasPlus(user(UserRole.PLUS)));
        assertTrue(plus.hasPlus(user(UserRole.OWNER)));
        assertFalse(plus.hasPlus(user(UserRole.REGULAR)));
        assertFalse(plus.hasPlus(user(UserRole.STAFF)));
        assertFalse(plus.hasPlus(user(UserRole.MOD)));
        assertEquals(4, java.util.Set.of(UserRole.PLUS, UserRole.OWNER, UserRole.DEV, UserRole.ADMIN)
                .stream().filter(r -> plus.hasPlus(user(r))).count(),
                "PLUS plus OWNER, DEV and ADMIN — four roles, and nothing else");
    }
}
