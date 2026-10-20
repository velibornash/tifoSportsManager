package org.example.footballmanager.newLogic.sim.engine;

import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.Player;

public final class FatigueSystem {

    public static final double DRAIN_PER_CELL = 0.00035;

    public FatigueSystem() {
    }

    public static double speedFactor(Player player) {
        return 1.0 - player.getFatigue() * MovementEngine.MAX_FATIGUE_SPEED_LOSS;
    }

    public void update(MatchState state) {
        for (Player player : state.getPlayers()) {
            if (player.isUnavailable()) continue;
            double distance = Math.hypot(player.getVelX(), player.getVelY());
            if (distance <= 1e-9) continue;
            double stamina = Math.max(1.0, Math.min(20.0, player.getSkills().stamina()));
            double staminaFactor = (21.0 - stamina) / 20.0;
            player.setFatigue(player.getFatigue() + distance * DRAIN_PER_CELL * staminaFactor);
        }
    }
}
