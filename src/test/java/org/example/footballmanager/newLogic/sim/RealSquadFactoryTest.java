package org.example.footballmanager.newLogic.sim;

import org.example.footballmanager.newLogic.model.Lineup;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.Skills;
import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.sim.model.PlayerSkills;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealSquadFactoryTest {

    private static final String[] SLOT_ORDER = {
            "GK", "DL", "DCL", "DCR", "DR", "ML", "CML", "CMR", "MR", "STL", "STR"
    };

    @Test
    void mapsRealSquadIntoFourFourTwoShape() {
        List<Player> starters = elevenStarters();
        Lineup lineup = lineupWith(starters);

        List<org.example.footballmanager.newLogic.sim.model.Player> squad =
                RealSquadFactory.buildSquad(lineup, "HOME");

        assertNotNull(squad);
        assertEquals(11, squad.size());
        assertEquals(Set.of(SLOT_ORDER), roles(squad));

        // Real names kept
        for (int i = 0; i < 11; i++) {
            assertEquals("Player " + (i + 1), squad.get(i).getLabel());
        }

        // GK is the DB goalkeeper
        org.example.footballmanager.newLogic.sim.model.Player gk = squad.stream()
                .filter(p -> p.getRole().equals("GK")).findFirst().orElseThrow();
        assertEquals("Player 1", gk.getLabel());

        // Skills mapped: DB striker (ATT) gets high striker, high pace
        org.example.footballmanager.newLogic.sim.model.Player st = squad.stream()
                .filter(p -> p.getRole().equals("STL") || p.getRole().equals("STR"))
                .findFirst().orElseThrow();
        assertTrue(st.getSkills().striker() >= 15);
        assertTrue(st.getSkills().striker() <= 20);

        // Height in meters -> cm
        assertTrue(squad.stream().allMatch(p -> p.getHeightCm() >= 170 && p.getHeightCm() <= 200));
    }

    @Test
    void mapsAwaySquadMirrored() {
        List<Player> starters = elevenStarters();
        List<org.example.footballmanager.newLogic.sim.model.Player> home =
                RealSquadFactory.buildSquad(lineupWith(starters), "HOME");
        List<org.example.footballmanager.newLogic.sim.model.Player> away =
                RealSquadFactory.buildSquad(lineupWith(starters), "AWAY");

        org.example.footballmanager.newLogic.sim.model.Player homeGk = gk(home);
        org.example.footballmanager.newLogic.sim.model.Player awayGk = gk(away);
        // HOME GK anchor CELL_0_2 -> 1.5
        assertEquals(1.5, Math.round(homeGk.getPosition().getRow() * 10.0) / 10.0);
        // AWAY GK mirrors: 9 - 1.5 = 7.5
        assertEquals(7.5, Math.round(awayGk.getPosition().getRow() * 10.0) / 10.0);
    }

    @Test
    void returnsNullWhenLineupHasFewerThanElevenStarters() {
        List<Player> starters = elevenStarters().subList(0, 5);
        assertNull(RealSquadFactory.buildSquad(lineupWith(starters), "HOME"));
    }

    @Test
    void returnsNullWhenLineupMissing() {
        assertNull(RealSquadFactory.buildSquad(null, "HOME"));
    }

    @Test
    void skillMappingUsesExactValues() {
        List<Player> starters = elevenStarters();
        starters.get(0).getSkills().setExact(SkillName.GOALKEEPER, 18.5);
        List<org.example.footballmanager.newLogic.sim.model.Player> squad =
                RealSquadFactory.buildSquad(lineupWith(starters), "HOME");
        PlayerSkills gk = gk(squad).getSkills();
        assertEquals(18.5, gk.keeper(), 0.001);
    }

    // ---------- helpers ----------

    private static List<Player> elevenStarters() {
        List<Player> out = new ArrayList<>();
        Position[] line = {Position.GK, Position.DEF, Position.DEF, Position.DEF, Position.DEF,
                Position.MID, Position.MID, Position.MID, Position.MID,
                Position.ATT, Position.ATT};
        for (int i = 0; i < 11; i++) {
            out.add(player(i + 1, line[i]));
        }
        return out;
    }

    private static Player player(long id, Position position) {
        Skills skills = new Skills();
        skills.setSkill(SkillName.PACE, 14);
        skills.setSkill(SkillName.STAMINA, 15);
        skills.setSkill(SkillName.GOALKEEPER, 12);
        skills.setSkill(SkillName.DEFENDER, 13);
        skills.setSkill(SkillName.TECHNIQUE, 13);
        skills.setSkill(SkillName.PLAYMAKER, 13);
        skills.setSkill(SkillName.PASSING, 14);
        skills.setSkill(SkillName.STRIKER, 14);
        skills.initializeExactFromVisibleIfNeeded();
        if (position == Position.ATT) {
            skills.setExact(SkillName.STRIKER, 16.0);
            skills.setExact(SkillName.PACE, 17.0);
        }
        return new Player(
                // form, morale, rating - morale was added between form and rating in Sprint 2.6,
                // so the positional all-args constructor grew by one.
                id, "Player " + id, skills, 0.5, 21, 0, 0,
                1.80, 78.0, 8.0, 60.0, 7, position,
                0, 0, null, false, 0, null, null, null);
    }

    private static Lineup lineupWith(List<Player> starters) {
        Lineup lineup = new Lineup();
        lineup.setStartingPlayers(new ArrayList<>(starters));
        List<Long> ids = starters.stream().map(Player::getId).toList();
        lineup.setStarterOrder(String.join(",", ids.stream().map(String::valueOf).toList()));
        return lineup;
    }

    private static Set<String> roles(List<org.example.footballmanager.newLogic.sim.model.Player> squad) {
        return squad.stream().map(org.example.footballmanager.newLogic.sim.model.Player::getRole)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static org.example.footballmanager.newLogic.sim.model.Player gk(
            List<org.example.footballmanager.newLogic.sim.model.Player> squad) {
        return squad.stream().filter(p -> p.getRole().equals("GK")).findFirst().orElseThrow();
    }
}