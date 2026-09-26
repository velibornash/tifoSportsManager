package org.example.footballmanager.newLogic.service;

import org.example.commonmanager.model.User;
import org.example.commonmanager.model.UserRole;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Team;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Talent and the training percentage are paid, and own-squad only (owner, 2026-09-27).
 *
 * <p>The owner's exact rule: talent is visible <b>only for players in your own squad</b>, and the
 * training percentage likewise — not for a player you can see but do not manage.
 *
 * <p>Two separate gates, and both have to pass. That is the point of the test: a plus user still must
 * not see a rival's talent, and an owner still must not see the whole league.
 */
class PlusFeatureServiceTest {

    // Mocked: these tests are about the two rules, not about resolving a club name. The name
    // resolution is exercised where a real database exists.
    private final PlusFeatureService service = new PlusFeatureService(
            org.mockito.Mockito.mock(org.example.footballmanager.newLogic.repository.TeamRepository.class));

    private User user(UserRole role) {
        User u = new User();
        u.setRole(role);
        return u;
    }

    private Team team(Long id) {
        Team t = new Team();
        t.setId(id);
        return t;
    }

    private Player playerAt(Long teamId, double talent) {
        Player p = new Player();
        p.setName("Player " + teamId);
        p.setTalent(talent);
        p.setTeam(team(teamId));
        return p;
    }

    @Test
    @DisplayName("a plus user sees talent for his own squad")
    void plusSeesHisOwnSquad() {
        assertTrue(service.canSee(playerAt(1L, 8.0), user(UserRole.PLUS), 1L));
        assertEquals(8.0, service.talentOrNull(playerAt(1L, 8.0), user(UserRole.PLUS), 1L), 0.01);
    }

    @Test
    @DisplayName("a plus user does not see a rival's talent, and that is the point")
    void plusDoesNotSeeRivals() {
        // Knowing a rival's 19-year-old is special is a scouting secret, and it would let a manager
        // bid a price that only makes sense if you can see what he is.
        assertFalse(service.canSee(playerAt(2L, 9.5), user(UserRole.PLUS), 1L),
                "a plus subscription buys your own squad's talent, not the league's");
        assertNull(service.talentOrNull(playerAt(2L, 9.5), user(UserRole.PLUS), 1L));
    }

    @Test
    @DisplayName("a regular user sees nothing, even about his own players")
    void regularSeesNothing() {
        assertFalse(service.hasPlus(user(UserRole.REGULAR)));
        assertFalse(service.canSee(playerAt(1L, 8.0), user(UserRole.REGULAR), 1L),
                "the own-team half of the rule does not replace the plus half");
    }

    @Test
    @DisplayName("talent is hidden as null, never as zero")
    void hiddenTalentIsNullNotZero() {
        Double hidden = service.talentOrNull(playerAt(2L, 7.0), user(UserRole.REGULAR), 2L);
        assertNull(hidden, "zero is a real talent value; showing it would make an invisible "
                + "10-year-old look like a hopeless one rather than a hidden one");
    }

    @Test
    @DisplayName("the training percentage follows the same rule exactly")
    void percentageFollowsTheSameRule() {
        Player mine = playerAt(1L, 7.0);
        Player theirs = playerAt(2L, 7.0);

        assertEquals(88.4,
                service.trainingPercentOrNull(88.4, mine, user(UserRole.PLUS), 1L), 0.01);
        assertNull(service.trainingPercentOrNull(88.4, mine, user(UserRole.REGULAR), 1L),
                "a regular user does not get the percentage for his own player");
        assertNull(service.trainingPercentOrNull(88.4, theirs, user(UserRole.PLUS), 1L),
                "nor for one he can see but does not manage");
    }

    @Test
    @DisplayName("an unknown viewer sees nothing, rather than everything")
    void unknownViewerSeesNothing() {
        Player p = playerAt(1L, 8.0);
        assertFalse(service.canSee(p, user(UserRole.PLUS), null),
                "a failed team lookup must fail closed; defaulting to yes would leak every player's "
                        + "talent to anyone");
        assertFalse(service.canSee(p, null, 1L));
        assertFalse(service.canSee(null, user(UserRole.PLUS), 1L));
    }

    @Test
    @DisplayName("a player with no club is never your own")
    void aFreeAgentIsNotYours() {
        Player free = playerAt(1L, 8.0);
        free.setTeam(null);
        assertFalse(service.canSee(free, user(UserRole.PLUS), 1L),
                "a player with no club is a free agent, and a free agent's talent is exactly the "
                        + "thing you would be scouting for");
    }

    @Test
    @DisplayName("staff and owners skip the plus check but not the own-team check")
    void staffSkipPlusButNotOwnership() {
        for (UserRole role : List.of(UserRole.OWNER, UserRole.DEV, UserRole.ADMIN)) {
            assertTrue(service.hasPlus(user(role)), role + " should see paid information");
            assertTrue(service.canSee(playerAt(1L, 8.0), user(role), 1L));
            assertFalse(service.canSee(playerAt(2L, 8.0), user(role), 1L),
                    role + " still only sees their own club's players");
        }
    }

    @Test
    @DisplayName("a null or role-less user is not plus")
    void nullsAreNotPlus() {
        assertFalse(service.hasPlus(null));
        assertFalse(service.hasPlus(new User()));
    }

}
