package org.example.footballmanager.newLogic.sim.util;

import org.example.footballmanager.newLogic.sim.model.PlayerSkills;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The two synthetic squads must be EXACT mirror images.
 *
 * They were not: the base skill was derived from {@code (team + role).hashCode()},
 * so HOME's wide midfielders came out at base 12 and AWAY's at 15. Because
 * {@code DuelEngine} resolves a duel by comparing one power number, mirrored
 * midfield matchups became "AWAY wins 100% / HOME wins 0%", producing a 35/65
 * possession split and the whole downstream goals cascade.
 */
class SimTeamFactoryMirrorTest {

    private static final List<String> ROLES =
            List.of("GK", "DL", "DR", "DCL", "DCR", "ML", "MR", "CML", "CMR", "STL", "STR");

    @Test
    void everyRoleHasIdenticalSkillsOnBothSides() {
        for (String role : ROLES) {
            PlayerSkills home = SimTeamFactory.randomSkills(role, "HOME");
            PlayerSkills away = SimTeamFactory.randomSkills(role, "AWAY");
            assertEquals(home, away,
                    "role " + role + " must have identical skills for both teams, otherwise "
                            + "duel power comparisons are decided by the squad generator");
        }
    }

    @Test
    void profileIsAFootballSquadNotAFlatWall() {
        // A keeper must be a keeper, strikers must finish, defenders must defend.
        PlayerSkills gk = SimTeamFactory.randomSkills("GK", "HOME");
        PlayerSkills dcl = SimTeamFactory.randomSkills("DCL", "HOME");
        PlayerSkills cml = SimTeamFactory.randomSkills("CML", "HOME");
        PlayerSkills str = SimTeamFactory.randomSkills("STR", "HOME");

        assertEquals(17.0, gk.keeper(), 0.001, "GK must be the best keeper on the pitch");
        assertEquals(16.0, str.striker(), 0.001, "STR must be the best finisher");
        assertEquals(17.0, dcl.defender(), 0.001, "DCL must be the best defender");
        assertEquals(16.0, cml.passing(), 0.001, "CML must be the best passer");
    }

    @Test
    void unknownRoleFallsBackToNeutral() {
        assertEquals(PlayerSkills.neutral(), SimTeamFactory.randomSkills("XYZ", "HOME"));
    }
}
