package org.example.footballmanager.demo.service.engine;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.model.Player;

/**
 * Fatigue System — corePrinciples Section 24.
 *
 * "Fatigue may affect speed, acceleration, reaction, decision quality,
 * technical execution, pressing, recovery, concentration."
 *
 * "Fatigue should modify probabilities and capabilities gradually
 * rather than suddenly disabling abilities."
 */
public class FatigueService {

    private static final double MAX_FATIGUE = 1.0;
    private static final double FATIGUE_RATE_RUNNING = 0.0003; // per tick while running
    private static final double FATIGUE_RATE_STANDING = 0.00005; // per tick while standing
    private static final double RECOVERY_RATE = 0.0001; // per tick when standing still
    private static final double SPRINT_FATIGUE_MULTIPLIER = 3.0;
    private static final double INJURY_THRESHOLD = 0.92;
    private static final double INJURY_BASE_CHANCE = 0.001;

    private final MatchState state;

    public FatigueService(MatchState state) {
        this.state = state;
    }

    /** Update fatigue for all players on each tick. */
    public void updateAll() {
        for (Player p : state.getPlayers()) {
            updatePlayer(p);
        }
    }

    /** Update fatigue for a single player. */
    public void updatePlayer(Player player) {
        double currentFatigue = player.getFatigue();
        boolean isMoving = player.getTarget() != null;
        boolean isCarrier = state.getCarrier() == player;

        double fatigueDelta;
        if (isMoving) {
            fatigueDelta = FATIGUE_RATE_RUNNING;
            if (isCarrier) fatigueDelta *= SPRINT_FATIGUE_MULTIPLIER;
        } else {
            fatigueDelta = -RECOVERY_RATE;
        }

        double newFatigue = Math.max(0, Math.min(MAX_FATIGUE, currentFatigue + fatigueDelta));
        player.setFatigue(newFatigue);
    }

    /** Get speed multiplier based on fatigue (1.0 = no fatigue, 0.7 = very tired). */
    public double speedMultiplier(Player player) {
        double fatigue = player.getFatigue();
        return 1.0 - fatigue * 0.3; // max 30% speed reduction
    }

    /** Get decision quality multiplier based on fatigue. */
    public double decisionMultiplier(Player player) {
        double fatigue = player.getFatigue();
        return 1.0 - fatigue * 0.2; // max 20% decision quality reduction
    }

    /** Get technical execution multiplier based on fatigue. */
    public double executionMultiplier(Player player) {
        double fatigue = player.getFatigue();
        return 1.0 - fatigue * 0.25; // max 25% execution quality reduction
    }

    /** Check if a player is at risk of injury due to fatigue. */
    public boolean checkInjuryRisk(Player player) {
        if (player.getFatigue() < INJURY_THRESHOLD) return false;
        double excess = player.getFatigue() - INJURY_THRESHOLD;
        double injuryChance = excess * INJURY_BASE_CHANCE * 100;
        return state.getRandom().nextDouble() < injuryChance;
    }

    /** Get fatigue percentage for display (0-100). */
    public int fatiguePercent(Player player) {
        return (int) Math.round(player.getFatigue() * 100);
    }
}
