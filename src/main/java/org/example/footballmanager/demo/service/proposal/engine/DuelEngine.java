package org.example.footballmanager.demo.service.proposal.engine;

import org.example.footballmanager.demo.service.proposal.model.*;
import org.example.footballmanager.demo.service.proposal.util.SimUtils;
import java.util.List;

/**
 * Duel engine - ONLY resolves player duels (tackles, blocks, presses).
 * 
 * Core principle: Duels are resolved independently from decision selection.
 * A player may DECIDE to tackle, but whether it SUCCEEDS is resolved here.
 * 
 * CRITICAL: Dribble duels are tight - defender must be physically on top
 * of the carrier. This prevents "magic" tackles from far away.
 */
public class DuelEngine {

    // Duel radii (1 cell = 14m x 10m)
    public static final double DRIBBLE_DUEL_RADIUS = 0.15; // ~2m - very tight
    public static final double RECEIVE_PASS_RADIUS = 0.2;
    public static final double AERIAL_DUEL_RADIUS = 0.5;
    public static final double SHOT_BLOCK_RADIUS = 0.3;
    public static final int DUEL_COOLDOWN_TICKS = 10;
    public static final int DRIBBLE_COOLDOWN_TICKS = 7;

    /**
     * Check if a duel should be triggered between attacker and defender.
     * Returns duel type if duel fires, null otherwise.
     */
    public DuelType checkDuel(Player attacker, Player defender,
                                MatchState state) {
        if (attacker == null || defender == null) return null;
        if (attacker == defender) return null;

        // Calculate distance
        double distance = SimUtils.distance(
            attacker.getPosition(), defender.getPosition());

        // Dribble duel - tightest radius
        if (distance <= DRIBBLE_DUEL_RADIUS) {
            if (!isOnCooldown(attacker, defender, DRIBBLE_COOLDOWN_TICKS)) {
                return DuelType.DRIBBLE;
            }
        }

        // Receive pass duel
        if (distance <= RECEIVE_PASS_RADIUS && attacker.getTarget() != null) {
            return DuelType.RECEIVE_PASS;
        }

        // Shot block
        if (distance <= SHOT_BLOCK_RADIUS && state.getBall().getCarrier() != null) {
            // Check if defender is between ball and goal
            return DuelType.SHOT_BLOCK;
        }

        return null; // no duel
    }

    /**
     * Resolve a duel based on skills.
     * Returns the winner (null if draw - random).
     */
    public Player resolveDuel(Player attacker, Player defender,
                                DuelType duelType, MatchState state) {
        double attackerPower = calculateDuelPower(attacker, duelType);
        double defenderPower = calculateDuelPower(defender, getDefensiveDuelType(duelType));

        // Add randomness (controlled, from seed)
        double randomFactor = (Math.random() - 0.5) * 2.0; // -1 to 1

        double attackerFinal = attackerPower + randomFactor;

        return attackerFinal > defenderPower ? attacker : defender;
    }

    /**
     * Apply duel result (winner gets ball).
     */
    public void applyDuelResult(MatchState state, Player winner, Player loser) {
        // Winner becomes carrier
        state.setCarrier(winner);

        // Loser is blocked for cooldown (unlocked by orchestrator after ticks expire)
        loser.setLocked(true);
        loser.setLockTicks(DRIBBLE_COOLDOWN_TICKS);
    }

    private double calculateDuelPower(Player player, DuelType duelType) {
        double power = 0.0;
        PlayerSkills skills = player.getSkills();

        switch (duelType) {
            case DRIBBLE -> {
                // Dribbling + technique + pace
                power = skills.technique() * 0.4 + skills.pace() * 0.3 + skills.striker() * 0.3;
            }
            case TACKLE -> {
                // Defending + technique + pace
                power = skills.defender() * 0.5 + skills.technique() * 0.3 + skills.pace() * 0.2;
            }
            case RECEIVE_PASS -> {
                // Technique + first touch
                power = skills.technique() * 0.6 + skills.pace() * 0.4;
            }
            case SHOT_BLOCK -> {
                // Defending + height
                power = skills.defender() * 0.7 + player.heightSkill() * 0.3;
            }
            case AERIAL -> {
                // Height + jumping
                power = player.heightSkill() * 0.6 + skills.striker() * 0.4;
            }
        }

        // Fatigue reduces power
        power *= (1.0 - player.getFatigue() * MovementEngine.MAX_FATIGUE_SPEED_LOSS);

        return power;
    }

    private DuelType getDefensiveDuelType(DuelType duelType) {
        return switch (duelType) {
            case DRIBBLE -> DuelType.TACKLE;
            case TACKLE -> DuelType.TACKLE;
            case RECEIVE_PASS -> DuelType.RECEIVE_PASS;
            case SHOT_BLOCK -> DuelType.TACKLE;
            case AERIAL -> DuelType.AERIAL;
            default -> DuelType.TACKLE;
        };
    }

    private boolean isOnCooldown(Player a, Player b, int cooldown) {
        // Simplified - would track last duel tick in state
        return false;
    }

    public enum DuelType {
        DRIBBLE,
        TACKLE,
        RECEIVE_PASS,
        SHOT_BLOCK,
        AERIAL,
        CLEAR,
        HEADER,
        BLOCK
    }
}