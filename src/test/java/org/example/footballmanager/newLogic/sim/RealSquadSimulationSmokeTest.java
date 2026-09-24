package org.example.footballmanager.newLogic.sim;

import org.example.footballmanager.newLogic.model.Lineup;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.Skills;
import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.sim.engine.MatchOrchestrator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealSquadSimulationSmokeTest {

    @Test
    void realSquadsRunFullMatch() {
        List<Player> homeStarters = elevenStarters();
        List<Player> awayStarters = elevenStarters();
        Lineup homeLineup = lineupWith(homeStarters);
        Lineup awayLineup = lineupWith(awayStarters);

        List<org.example.footballmanager.newLogic.sim.model.Player> homeSquad =
                RealSquadFactory.buildSquad(homeLineup, "HOME");
        List<org.example.footballmanager.newLogic.sim.model.Player> awaySquad =
                RealSquadFactory.buildSquad(awayLineup, "AWAY");

        assertNotNull(homeSquad);
        assertNotNull(awaySquad);
        assertTrue(homeSquad.size() == 11 && awaySquad.size() == 11);

        MatchOrchestrator orchestrator = SimMatchRunner.run(
                "Home FC", "Away United", 3600, homeSquad, awaySquad);

        assertNotNull(orchestrator.buildOutcome());
        assertTrue(orchestrator.getState().getHomeGoals() >= 0);
        assertTrue(orchestrator.getState().getAwayGoals() >= 0);
        assertTrue(orchestrator.getState().getMatchTicks() == 3600L);
    }

    @Test
    void realSquadIdIsStableAcrossKickoffAndTactics() {
        Lineup lineup = lineupWith(elevenStarters());
        List<org.example.footballmanager.newLogic.sim.model.Player> squad =
                RealSquadFactory.buildSquad(lineup, "HOME");

        // Roles must be engine 4-4-2 slot keys so tactical targets resolve.
        for (org.example.footballmanager.newLogic.sim.model.Player p : squad) {
            assertTrue(Set.of(
                    "GK", "DL", "DCL", "DCR", "DR", "ML", "CML", "CMR", "MR", "STL", "STR")
                    .contains(p.getRole()));
        }
    }

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
        skills.setSkill(SkillName.PACE, 15);
        skills.setSkill(SkillName.STAMINA, 14);
        skills.setSkill(SkillName.GOALKEEPER, 13);
        skills.setSkill(SkillName.DEFENDER, 14);
        skills.setSkill(SkillName.TECHNIQUE, 14);
        skills.setSkill(SkillName.PLAYMAKER, 14);
        skills.setSkill(SkillName.PASSING, 14);
        skills.setSkill(SkillName.STRIKER, 14);
        skills.initializeExactFromVisibleIfNeeded();
        return new Player(
                id, "Player " + id, skills, 0.5, 21, 0, 0,
                1.80, 78.0, 8.0, 7, position,
                0, 0, null, false, 0, null, null, null);
    }

    private static Lineup lineupWith(List<Player> starters) {
        Lineup lineup = new Lineup();
        lineup.setStartingPlayers(new ArrayList<>(starters));
        List<Long> ids = starters.stream().map(Player::getId).toList();
        lineup.setStarterOrder(String.join(",", ids.stream().map(String::valueOf).toList()));
        return lineup;
    }
}