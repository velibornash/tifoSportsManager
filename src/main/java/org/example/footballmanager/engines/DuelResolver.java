package org.example.footballmanager.engines;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Position;
import org.springframework.stereotype.Component;

import java.util.Random;

/**
 * Duel Resolver - Rezolvuje duele između igrača
 * 
 * Dva tipa duella:
 * 1. TACKLE - Odbranac vs Napadač (борба за loptu)
 * 2. SHOT - Šutač vs Golman (gol ili ne)
 * 
 * Koristi player skills za izračunavanje rezultata
 */
@Component
@Slf4j
public class DuelResolver {

    private final Random random;

    public DuelResolver() {
        this(new Random());
    }

    DuelResolver(Random random) {
        this.random = random;
    }

    /**
     * Rezolvuje duel između šutača i golmana
     * 
     * Šansa za gol zavisi od:
     * - Striker skill šutača
     * - Goalkeeper skill golmana
     * - Distancu (procena)
     */
    public DuelResult resolveShotDuel(Player shooter, Player goalkeeper, double x, double y) {
        double xG = estimateShotXg(shooter, x, y, false);

        double shooterAccuracy = shooter.getSkills().getStriker() * 1.05
                + shooter.getSkills().getTechnique() * 0.95
                + shooter.getSkills().getPace() * 0.18;
        double goalkeeperDefense = goalkeeper.getSkills().getGoalkeeper() * 3.0
                + goalkeeper.getSkills().getDefender() * 0.40;
        double finisherEdge = shooterAccuracy / Math.max(1.0, shooterAccuracy + goalkeeperDefense);

        double onTargetChance = 0.18
                + xG * 0.78
                + (shooter.getSkills().getTechnique() / 210.0)
                + (shooter.getSkills().getStriker() / 260.0)
                - (goalkeeper.getSkills().getGoalkeeper() / 420.0);
        onTargetChance = clampChance(onTargetChance + (random.nextDouble() - 0.5) * 0.035, 0.28, 0.78);

        boolean isOnTarget = random.nextDouble() < onTargetChance;

        double desiredGoalChance = xG * (0.78 + finisherEdge * 0.14);
        desiredGoalChance = clampChance(desiredGoalChance + (random.nextDouble() - 0.5) * 0.018, 0.02, 0.42);
        double goalChanceWhenOnTarget = clampChance(
                desiredGoalChance / Math.max(onTargetChance, 0.16),
                0.05,
                0.45
        );

        boolean isGoal = isOnTarget && random.nextDouble() < goalChanceWhenOnTarget;
        boolean isSaved = isOnTarget && !isGoal;
        
        log.debug("Shot duel: {} (xG: {:.2f}), onTarget={:.2f}, goal|onTarget={:.2f}, result={}",
                shooter.getName(), xG, onTargetChance, goalChanceWhenOnTarget,
                isGoal ? "GOAL" : isSaved ? "SAVED" : "MISSED");
        
        return new DuelResult(isGoal, isSaved, !isGoal && !isSaved, xG);
    }

    public DuelResult resolvePenalty(Player shooter, Player goalkeeper) {
        double xG = 0.76
                + ((shooter.getSkills().getStriker() - 10.0) * 0.006)
                + ((shooter.getSkills().getTechnique() - 10.0) * 0.004);
        xG = clampChance(xG, 0.72, 0.84);

        double takerQuality = shooter.getSkills().getStriker() * 1.10
                + shooter.getSkills().getTechnique() * 0.90;
        double goalkeeperQuality = goalkeeper != null
                ? goalkeeper.getSkills().getGoalkeeper() * 1.22 + goalkeeper.getSkills().getDefender() * 0.18
                : 0.0;

        double scoreChance = 0.74 + (takerQuality - goalkeeperQuality) / 260.0;
        scoreChance = clampChance(
                scoreChance + (random.nextDouble() - 0.5) * 0.08,
                goalkeeper == null ? 0.82 : 0.66,
                goalkeeper == null ? 0.97 : 0.84
        );

        boolean isGoal = random.nextDouble() < scoreChance;

        double missShare = 0.24
                - Math.min(0.06, shooter.getSkills().getTechnique() / 400.0)
                + Math.max(0.0, 10.0 - shooter.getSkills().getStriker()) * 0.01;
        missShare = clampChance(
                missShare + (random.nextDouble() - 0.5) * 0.05,
                0.16,
                goalkeeper == null ? 1.0 : 0.34
        );

        boolean isSaved = !isGoal && goalkeeper != null && random.nextDouble() >= missShare;

        log.debug("Penalty duel: {} (xG: {:.2f}), score={:.2f}, missShare={:.2f}, result={}",
                shooter.getName(), xG, scoreChance, missShare,
                isGoal ? "GOAL" : isSaved ? "SAVED" : "MISSED");

        return new DuelResult(isGoal, isSaved, !isGoal && !isSaved, xG);
    }

    public DuelResult resolveOpenGoalShot(Player shooter, double x, double y) {
        double xG = estimateShotXg(shooter, x, y, true);
        double variation = (random.nextDouble() - 0.5) * 0.05;
        double finishingBonus = (shooter.getSkills().getStriker() + shooter.getSkills().getTechnique()) / 270.0;
        double finalChance = clampChance(0.50 + (xG * 0.45) + (finishingBonus * 0.92) + variation, 0.82, 0.97);
        boolean isGoal = random.nextDouble() < finalChance;

        log.debug("Open-goal shot: {} (xG: {:.2f}), chance={:.2f}, result={}",
                shooter.getName(), xG, finalChance, isGoal ? "GOAL" : "MISSED");

        return new DuelResult(isGoal, false, !isGoal, xG);
    }

    private double estimateShotXg(Player shooter, double x, double y, boolean openGoal) {
        double distToGoal = Math.sqrt(Math.pow(100.0 - x, 2) + Math.pow(50.0 - y, 2));
        double angleToGoal = Math.abs(50.0 - y) / Math.max(0.8, 100.01 - x);
        double baseXg = (openGoal ? 1.18 : 1.04)
                * Math.exp(-(openGoal ? 0.080 : 0.092) * distToGoal)
                / (1.0 + angleToGoal * (openGoal ? 1.20 : 1.50));
        baseXg = clampChance(baseXg, openGoal ? 0.22 : 0.025, openGoal ? 0.90 : 0.68);

        double bodyShapeFactor = (shooter.getSkills().getStriker() + shooter.getSkills().getTechnique()) / 40.0;
        double xG = openGoal
                ? (baseXg * (0.98 + 0.03 * bodyShapeFactor))
                : (baseXg * (0.96 + 0.06 * bodyShapeFactor));

        return clampChance(xG, openGoal ? 0.22 : 0.025, openGoal ? 0.92 : 0.70);
    }

    private double clampChance(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public DuelResult resolveTackleDuel(Player attacker, Player defender) {
        double attackerFatigueFactor = fatigueFactor(attacker);
        double defenderFatigueFactor = fatigueFactor(defender);

        // Attacker snaga (tehnika, burst, balans pod pritiskom)
        double attackerStrength = (
                attacker.getSkills().getTechnique() * 1.05
                        + attacker.getSkills().getStriker() * 0.22
                        + attacker.getSkills().getPace() * 0.58
                        + attacker.getSkills().getStamina() * 0.20
        ) * attackerFatigueFactor;

        // Defender snaga (timing ulaska, brzina zatvaranja i izdržljivost)
        double defenderStrength = (
                defender.getSkills().getDefender() * 1.38
                        + defender.getSkills().getTechnique() * 0.18
                        + defender.getSkills().getPace() * 0.44
                        + defender.getSkills().getStamina() * 0.28
        ) * defenderFatigueFactor;

        double attackerWinChance = attackerStrength / (attackerStrength + defenderStrength);

        double variation = (random.nextDouble() - 0.5) * 0.15;
        double finalChance = Math.max(0.20, Math.min(0.80, attackerWinChance + variation));

        boolean attackerWins = random.nextDouble() < finalChance;

        log.debug("Tackle duel: {} vs {}, chance={:.2f}, winner={}", 
                attacker.getName(), defender.getName(), finalChance,
                attackerWins ? attacker.getName() : defender.getName());

        return new DuelResult(attackerWins, false, !attackerWins);
    }

    private double fatigueFactor(Player player) {
        if (player == null) {
            return 1.0;
        }
        double fatigue = Math.max(0.0, player.getCurrentFatigue());
        return Math.max(0.74, 1.0 - Math.max(0.0, fatigue - 5.5) * 0.045);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // DATA CLASS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Data
    public static class DuelResult {
        private final boolean won;      // Šutač postiže gol? Attacker vini?
        private final boolean saved;    // Golman sprečava gol?
        private final boolean lost;     // Defender vini? Šut omašen?
        private double xG = 0.0;

        public DuelResult(boolean won, boolean saved, boolean lost) {
            this.won = won;
            this.saved = saved;
            this.lost = lost;
        }

        public DuelResult(boolean won, boolean saved, boolean lost, double xG) {
            this.won = won;
            this.saved = saved;
            this.lost = lost;
            this.xG = xG;
        }

        public boolean isGoal() {
            return won;
        }

        public boolean isSaved() {
            return saved;
        }

        public boolean isMissed() {
            return lost;
        }
    }
}
