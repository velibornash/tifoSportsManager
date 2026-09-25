package org.example.footballmanager.newLogic.sim.engine;

import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.model.PlayerSkills;
import org.example.footballmanager.newLogic.sim.model.Position;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FatigueSystemTest {

    @Test
    void distanceDrivesFatigueAndLowerStaminaDrainsFaster() {
        MatchState state = new MatchState();
        Player high = player(20.0, 20.0);
        Player low = player(1.0, 1.0);
        state.getPlayers().add(high);
        state.getPlayers().add(low);
        high.setVelX(1.0);
        low.setVelX(1.0);

        new FatigueSystem().update(state);

        assertTrue(high.getFatigue() > 0.0);
        assertTrue(low.getFatigue() > high.getFatigue());
    }

    @Test
    void stationaryPlayersDoNotAccumulateFatigue() {
        MatchState state = new MatchState();
        Player player = player(0.0, 10.0);
        state.getPlayers().add(player);

        new FatigueSystem().update(state);

        assertEquals(0.0, player.getFatigue(), 1e-9);
    }

    @Test
    void fullFatigueAppliesTheConfiguredThirtyPercentSpeedLoss() {
        Player player = player(0.0, 10.0);
        player.setFatigue(1.0);

        assertEquals(0.70, FatigueSystem.speedFactor(player), 1e-9);
    }

    private static Player player(double stamina, double pace) {
        Position position = new Position(4.5, 4.0);
        PlayerSkills skills = new PlayerSkills(pace, stamina, 10, 10, 10, 10, 10, 10);
        return new Player("P", "P", "HOME", "STL", position, position, skills);
    }
}
