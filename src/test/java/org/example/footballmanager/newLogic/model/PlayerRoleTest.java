package org.example.footballmanager.newLogic.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Position and role are two different questions (owner, 2026-09-26).
 *
 * <p>Position is the broad unit — five of them — and answers "which part of the pitch". Role is the
 * specific job and answers "what is he for". Mixing them is not a style problem: a club that counts
 * its defenders as one group can have three centre backs and no left back and think it is fine, and
 * a training plan written against positions cannot tell a full back from a centre back.
 */
class PlayerRoleTest {

    @Test
    @DisplayName("there are five positions and more roles than that")
    void twoDifferentVocabularies() {
        assertEquals(5, Position.values().length, "positions stay the broad five");
        assertTrue(PlayerRole.values().length > Position.values().length,
                "roles are the detailed set, so there must be more of them");
        assertNotEquals(Position.values().length, PlayerRole.values().length);
    }

    @Test
    @DisplayName("every role belongs to exactly one position, and no position is orphaned")
    void rolesMapOntoPositions() {
        Set<Position> covered = new HashSet<>();
        for (PlayerRole role : PlayerRole.values()) {
            assertNotEquals(null, role.position(), role + " has no position");
            assertTrue(role.position() == role.position(), "positions are compared by identity");
            covered.add(role.position());
        }
        for (Position position : Position.values()) {
            assertTrue(covered.contains(position),
                    "no role maps to " + position + ", so nobody could ever play there");
        }
    }

    @Test
    @DisplayName("a role knows whether it fits a position")
    void fitsIsExplicit() {
        assertTrue(PlayerRole.CENTRE_BACK.fits(Position.DEF));
        assertTrue(PlayerRole.STRIKER.fits(Position.ATT));
        assertFalse(PlayerRole.STRIKER.fits(Position.DEF),
                "a striker is not a centre back, and a squad balance check must know that");
        assertFalse(PlayerRole.LEFT_BACK.fits(null));
    }

    @Test
    @DisplayName("every position has a sensible default role")
    void defaultsExistForEveryPosition() {
        for (Position position : Position.values()) {
            PlayerRole role = PlayerRole.defaultFor(position);
            assertTrue(role.fits(position),
                    position + " defaults to " + role + ", which is not even in that position");
        }
        assertSame(PlayerRole.GOALKEEPER, PlayerRole.defaultFor(Position.GK));
        assertEquals(PlayerRole.CENTRE_MIDFIELDER, PlayerRole.defaultFor(null),
                "an unknown position still yields a usable role rather than null");
    }

    @Test
    @DisplayName("roles are looked up by name, and a bad name is not a crash")
    void lookupByName() {
        assertEquals(PlayerRole.LEFT_BACK, PlayerRole.byName("LEFT_BACK").orElseThrow());
        assertEquals(PlayerRole.CENTRE_BACK, PlayerRole.byName("centre back").orElseThrow(),
                "a name with spaces is the same role");
        assertTrue(PlayerRole.byName("SHOOTER").isEmpty(), "an invented role is not a role");
        assertTrue(PlayerRole.byName(null).isEmpty());
        assertTrue(PlayerRole.byName("  ").isEmpty());
    }

    @Test
    @DisplayName("a player with no role set still has one, derived from his position")
    void aPlayerNeverHasNoRole() {
        Player p = new Player();
        p.setPosition(Position.DEF);
        assertEquals(PlayerRole.CENTRE_BACK, p.effectiveRole(),
                "a player created before the role column existed must still have a role, or every "
                        + "squad screen and training plan reads null");
    }

    @Test
    @DisplayName("setting a role sets the position with it, so the two cannot drift apart")
    void settingARoleFixesThePosition() {
        Player p = new Player();
        p.setPosition(Position.MID);
        p.setRole(PlayerRole.WINGER);

        assertEquals(Position.WNG, p.getPosition(),
                "a winger is a wide player; leaving the position behind is how a striker ends up "
                        + "training as a full back");
        assertEquals(PlayerRole.WINGER, p.effectiveRole());
    }

    @Test
    @DisplayName("roles can be listed per position")
    void rolesGroupByPosition() {
        assertTrue(PlayerRole.of(Position.DEF).contains(PlayerRole.LEFT_BACK));
        assertFalse(PlayerRole.of(Position.DEF).contains(PlayerRole.STRIKER));
        assertTrue(PlayerRole.of(Position.GK).size() == 1, "there is only one way to be a keeper");
    }

    @Test
    @DisplayName("a centre back and a full back are different jobs inside the same position")
    void theReasonThisExists() {
        assertEquals(Position.DEF, PlayerRole.CENTRE_BACK.position());
        assertEquals(Position.DEF, PlayerRole.LEFT_BACK.position());

        assertNotEquals(PlayerRole.CENTRE_BACK, PlayerRole.LEFT_BACK);
        assertEquals("Left back", PlayerRole.LEFT_BACK.label());
        assertEquals("Centre back", PlayerRole.CENTRE_BACK.label());
    }

    @Test
    @DisplayName("every role has a label a manager can read")
    void labelsArePresent() {
        for (PlayerRole role : PlayerRole.values()) {
            assertTrue(role.label() != null && !role.label().isBlank(),
                    role + " has no label");
        }
        assertEquals(List.of(PlayerRole.GOALKEEPER), List.copyOf(PlayerRole.of(Position.GK)));
    }
}
