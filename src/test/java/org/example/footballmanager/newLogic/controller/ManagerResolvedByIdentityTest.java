package org.example.footballmanager.newLogic.controller;

import org.example.commonmanager.model.User;
import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.commonmanager.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A manager's own club is resolved by identity, not by name ({@code P0-PREV-6}).
 *
 * <p>{@code SimulationController} resolved the manager's club as a <b>name</b> and looked it back up with
 * {@code findByName}, at four call sites — including {@code isUserMatch}, which decides what "play my
 * match" and the reveal button may act on. {@code User.footballTeam} is a real foreign key to
 * {@code Team}, and its own javadoc records that this name-join "produced four separate defects, all the
 * same mistake in a different costume". This controller was one of the readers still doing it.
 *
 * <p><b>This calls the resolver.</b> An earlier version of this test asserted only on its own fixtures —
 * that two clubs can share a name — which passes whether or not the code works, and is exactly the kind
 * of test this repository keeps finding. The resolver is package-private so it can be called here.
 */
class ManagerResolvedByIdentityTest extends BaseTest {

    @Autowired
    SimulationController controller;

    @Autowired
    TeamRepository teams;

    @Autowired
    UserRepository users;

    @Test
    @Transactional
    @DisplayName("a manager holding one of two identically named clubs resolves to the one they hold")
    void aManagerResolvesToTheirOwnClubWhenTwoShareAName() {
        Team mine = aTeam("Red Star");
        Team theirs = aTeam("Red Star");
        assertEquals(mine.getName(), theirs.getName(),
                "precondition: two distinct clubs deliberately sharing a name");

        User manager = new User();
        manager.setEmail("identity-" + java.util.UUID.randomUUID() + "@example.com");
        manager.setPassword("x");
        manager.setFootballTeam(mine);
        manager = users.save(manager);

        Team resolved = controller.resolveUserTeam(manager);

        assertNotNull(resolved, "the manager holds a club, so one must come back");
        assertEquals(mine.getId(), resolved.getId(),
                "and it must be the one they actually hold. With a name lookup this returns whichever of "
                        + "the two the query found first - a successful lookup returning the wrong club, "
                        + "with no error anywhere");
        assertTrue(mine.getId() != theirs.getId(), "and the two really are different rows");
    }

    @Test
    @Transactional
    @DisplayName("a manager with no club resolves to nothing rather than to a same-named club")
    void aManagerWithNoClubResolvesToNothing() {
        Team someoneElses = aTeam("Red Star");

        User manager = new User();
        manager.setEmail("identity-none-" + java.util.UUID.randomUUID() + "@example.com");
        manager.setPassword("x");
        manager = users.save(manager);

        assertEquals(null, controller.resolveUserTeam(manager),
                "an account with no club must not be handed somebody else's club that happens to share a "
                        + "name with one that does not exist for them");
    }

    private Team aTeam(String name) {
        Team team = new Team();
        team.setName(name);
        team.setFormation("4-4-2");
        return teams.save(team);
    }
}
