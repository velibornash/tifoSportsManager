package org.example.footballmanager.newLogic.engine;

import org.example.footballmanager.newLogic.model.*;
import org.springframework.stereotype.Component;

import java.util.Random;

@Component
public final class DuelResolver {

    private static final Random RNG = new Random();

    public DuelResolver() {}

    public DuelResult resolveShotDuel(PlayerSnapshot shooter, PlayerSnapshot goalkeeper, double distance, double xG) {
        double shootSkill = shooter.shooting() + shooter.technique();
        double gkSkill = goalkeeper != null ? goalkeeper.defending() + (goalkeeper instanceof PlayerSnapshot ? 10 : 0) : 5;

        double saveChance = 0.30 + (gkSkill / 40.0) * 0.30;
        saveChance *= (1.0 + distance / 100.0);

        double adjustedXG = xG * (0.7 + (shootSkill / 40.0) * 0.6);

        boolean saved = RNG.nextDouble() < saveChance;
        boolean isGoal = !saved && RNG.nextDouble() < adjustedXG;

        return new DuelResult(isGoal, isGoal ? "GOAL" : saved ? "SAVED" : "MISSED", adjustedXG, isGoal, saved);
    }

    public DuelResult resolveOpenGoalShot(PlayerSnapshot shooter, double distance, double xG) {
        double shootSkill = shooter.shooting() + shooter.technique();
        double adjustedXG = Math.min(0.85, xG * 1.5 * (0.8 + (shootSkill / 40.0) * 0.4));
        boolean isGoal = RNG.nextDouble() < adjustedXG;
        return new DuelResult(isGoal, isGoal ? "GOAL" : "MISSED", adjustedXG, isGoal, false);
    }

    public DuelResult resolveTackleDuel(PlayerSnapshot attacker, PlayerSnapshot defender) {
        return resolveTackle(attacker, defender);
    }

    public static DuelResult resolveTackle(PlayerSnapshot attacker, PlayerSnapshot defender) {
        double attackPower = attacker.technique() + attacker.pace() + attacker.dribbling();
        double defendPower = defender.defending() + defender.pace();

        double attackChance = attackPower / (attackPower + defendPower);
        attackChance = Math.max(0.25, Math.min(0.75, attackChance));

        boolean attackerWins = RNG.nextDouble() < attackChance;
        return new DuelResult(attackerWins, attackerWins ? "TACKLE_WON" : "TACKLE_LOST", attackChance);
    }

    public static DuelResult resolvePenalty(PlayerSnapshot shooter, PlayerSnapshot goalkeeper) {
        double shootSkill = shooter.shooting() + shooter.technique();
        double gkSkill = goalkeeper != null ? goalkeeper.defending() : 10;

        double goalChance = 0.76 * (0.8 + (shootSkill / 40.0) * 0.4);
        double saveChance = 0.35 * (0.6 + (gkSkill / 20.0) * 0.8);
        goalChance *= (1.0 - saveChance);

        boolean isGoal = RNG.nextDouble() < goalChance;
        boolean saved = !isGoal && RNG.nextDouble() < saveChance;
        return new DuelResult(isGoal, isGoal ? "PENALTY_GOAL" : saved ? "PENALTY_SAVED" : "PENALTY_MISSED", goalChance, isGoal, saved);
    }

    public DuelResult resolveHeaderDuel(PlayerSnapshot attacker, PlayerSnapshot defender) {
        double attackPower = attacker.technique() + attacker.shooting();
        double defendPower = defender.defending() + defender.technique();

        double attackChance = attackPower / (attackPower + defendPower);
        attackChance = Math.max(0.25, Math.min(0.75, attackChance));

        boolean attackerWins = RNG.nextDouble() < attackChance;
        return new DuelResult(attackerWins, attackerWins ? "HEADER_WON" : "HEADER_LOST", attackChance);
    }

    public DuelResult resolveLooseBallDuel(PlayerSnapshot player1, PlayerSnapshot player2) {
        double power1 = player1.pace() + player1.technique();
        double power2 = player2.pace() + player2.technique();

        double chance1 = power1 / (power1 + power2);
        chance1 = Math.max(0.25, Math.min(0.75, chance1));

        boolean player1Wins = RNG.nextDouble() < chance1;
        return new DuelResult(player1Wins, player1Wins ? "LOOSE_BALL_WON" : "LOOSE_BALL_LOST", chance1);
    }

    public DuelResult resolveNumericDuel(PlayerSnapshot carrier, java.util.List<PlayerSnapshot> attackers,
                                          java.util.List<PlayerSnapshot> defenders) {
        if (defenders.isEmpty()) {
            return new DuelResult(true, "CARRIER_WINS", 0.9);
        }
        if (attackers.isEmpty()) {
            return new DuelResult(false, "TACKLE_WON", 0.9);
        }

        double attackPower = attackers.stream()
            .mapToDouble(a -> a.technique() + a.pace() + a.dribbling())
            .sum();
        double defendPower = defenders.stream()
            .mapToDouble(d -> d.defending() + d.pace())
            .sum();

        int attackCount = attackers.size();
        int defendCount = defenders.size();

        if (attackCount > defendCount) {
            attackPower *= 1.0 + (attackCount - defendCount) * 0.25;
        } else if (defendCount > attackCount) {
            defendPower *= 1.0 + (defendCount - attackCount) * 0.25;
        }

        double carrierChance = attackPower / (attackPower + defendPower);
        carrierChance = Math.max(0.15, Math.min(0.85, carrierChance));

        boolean carrierWins = RNG.nextDouble() < carrierChance;
        return new DuelResult(carrierWins,
            carrierWins ? "CARRIER_WINS" : "TACKLE_WON", carrierChance);
    }

    public record DuelResult(boolean attackerWins, String resultType, double xG, boolean goal, boolean saved) {
        public DuelResult(boolean attackerWins, String resultType, double probability) {
            this(attackerWins, resultType, probability, false, false);
        }
    }
}
