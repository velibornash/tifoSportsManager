package org.example.footballmanager.newLogic.engine;

import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;

import java.util.Random;

public final class DuelResolver {

    private static final Random RNG = new Random();

    public record DuelResult(boolean attackerWins, boolean goal, boolean saved, boolean missed, double xG) {}

    public record ShotParams(double x, double y, double goalDistance, double angle) {}

    public static DuelResult resolveShot(Player shooter, Player goalkeeper, ShotParams params, boolean openGoal) {
        double xG = estimateXG(shooter, params, openGoal);

        if (openGoal) {
            // Open goal: moderate conversion
            double chance = clamp(0.42 + xG * 0.35 + (shooter.skills().shooting() + shooter.skills().technique()) / 330.0, 0.65, 0.90);
            boolean goal = RNG.nextDouble() < chance;
            return new DuelResult(goal, goal, false, !goal, xG);
        }

        double shooterPower = shooter.skills().shooting() * 0.50 + shooter.skills().technique() * 0.30 + shooter.skills().pace() * 0.20;
        double keeperPower = goalkeeper.skills().goalkeeping() * 0.60 + goalkeeper.skills().defending() * 0.20;

        double onTargetChance = clamp(0.30 + xG * 0.38 + (shooter.skills().technique() / 220.0) - (goalkeeper.skills().goalkeeping() / 480.0), 0.24, 0.76);
        boolean onTarget = RNG.nextDouble() < onTargetChance;

        if (!onTarget) {
            return new DuelResult(false, false, false, true, xG);
        }

        double goalChance = clamp(xG * 0.75 + (shooterPower / (shooterPower + keeperPower + 1)) * 0.16, 0.15, 0.55);
        boolean goal = RNG.nextDouble() < goalChance;

        return new DuelResult(goal, goal, !goal, false, xG);
    }

    public static DuelResult resolveTackle(Player attacker, Player defender) {
        double attStr = attacker.skills().technique() * 1.05 + attacker.skills().pace() * 0.58 + attacker.skills().shooting() * 0.22
            + attacker.skills().stamina() * 0.20;
        double defStr = defender.skills().defending() * 1.38 + defender.skills().pace() * 0.44 + defender.skills().technique() * 0.18
            + defender.skills().stamina() * 0.28;

        attStr *= attacker.skills().fatigueFactor(attacker.fatigueInt());
        defStr *= defender.skills().fatigueFactor(defender.fatigueInt());

        double winChance = attStr / (attStr + defStr + 0.1);
        winChance = clamp(winChance + (RNG.nextDouble() - 0.5) * 0.15, 0.18, 0.82);
        boolean attackerWins = RNG.nextDouble() < winChance;

        return new DuelResult(attackerWins, false, false, !attackerWins, 0);
    }

    public static DuelResult resolvePenalty(Player taker, Player goalkeeper) {
        double xG = clamp(0.76 + (taker.skills().shooting() - 10) * 0.006 + (taker.skills().technique() - 10) * 0.004, 0.72, 0.84);
        double takerQuality = taker.skills().shooting() * 1.10 + taker.skills().technique() * 0.90;
        double keeperQuality = goalkeeper.skills().goalkeeping() * 1.22 + goalkeeper.skills().defending() * 0.18;

        double scoreChance = clamp(0.78 + (takerQuality - keeperQuality) / 255.0 + (RNG.nextDouble() - 0.5) * 0.07, 0.70, 0.88);
        boolean scored = RNG.nextDouble() < scoreChance;

        if (scored) {
            return new DuelResult(true, true, false, false, xG);
        }

        double missShare = clamp(0.24 - Math.min(0.06, taker.skills().technique() / 400.0)
            + Math.max(0, 10 - taker.skills().shooting()) * 0.01 + (RNG.nextDouble() - 0.5) * 0.05, 0.16, 0.34);
        boolean saved = RNG.nextDouble() >= missShare;

        return new DuelResult(false, false, saved, !saved, xG);
    }

    public static boolean isFoul(Player attacker, Player defender, boolean inPenaltyBox) {
        double chance = inPenaltyBox ? 0.038 : 0.034;
        if (defender.position() == Position.DEF) chance += inPenaltyBox ? 0.006 : 0.012;
        if (defender.position() == Position.MID) chance += inPenaltyBox ? 0.004 : 0.008;
        if (defender.position() == Position.GK && inPenaltyBox) chance += 0.012;
        return RNG.nextDouble() < chance;
    }

    public static boolean isCardWorthy(double foulChanceModifier) {
        double chance = 0.012 + foulChanceModifier;
        return RNG.nextDouble() < chance;
    }

    private static double estimateXG(Player shooter, ShotParams params, boolean openGoal) {
        double dist = params.goalDistance();
        double angle = params.angle();
        double baseXg = (openGoal ? 1.18 : 1.04)
            * Math.exp(-(openGoal ? 0.080 : 0.092) * dist)
            / (1.0 + angle * (openGoal ? 1.20 : 1.50));
        baseXg = clamp(baseXg, openGoal ? 0.22 : 0.025, openGoal ? 0.90 : 0.68);

        double bodyFactor = (shooter.skills().shooting() + shooter.skills().technique()) / 40.0;
        return clamp(baseXg * (openGoal ? 0.98 + 0.03 * bodyFactor : 0.96 + 0.06 * bodyFactor),
            openGoal ? 0.22 : 0.025, openGoal ? 0.92 : 0.70);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
