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
        // xG model - simple implementation
        double distToGoal = Math.sqrt(Math.pow(100.0 - x, 2) + Math.pow(50.0 - y, 2));
        double angleToGoal = Math.abs(50.0 - y) / (100.01 - x); // Higher is worse angle
        
        // Base xG from position (range 0.02 - 0.70)
        double base_xG = 0.92 * Math.exp(-0.105 * distToGoal) / (1.0 + angleToGoal * 1.85);
        base_xG = Math.max(0.02, Math.min(0.88, base_xG));

        // Player skill impact (+/- 40% on xG)
        double skillFactor = (shooter.getSkills().getStriker() * 1.5 + shooter.getSkills().getTechnique() * 0.5) / 140.0;
        double xG = base_xG * (0.84 + 0.42 * skillFactor);
        xG = Math.max(0.02, Math.min(0.90, xG));

        // Shooter strength
        double shooterPower = shooter.getSkills().getStriker() * 1.2 +
                             shooter.getSkills().getTechnique() * 0.8 +
                             (shooter.getSkills().getPace() / 4.0);
        
        // Goalkeeper defense
        double goalkeeperDefense = goalkeeper.getSkills().getGoalkeeper() * 3.1 +
                goalkeeper.getSkills().getDefender() * 0.45;
        
        // Osnovni faktor šanse (uzimamo xG kao primarni ulaz)
        double baseChance = xG * (0.78 + (shooterPower / (shooterPower + (goalkeeperDefense / 4.8))));
        
        // Random varijacija
        double variation = (random.nextDouble() - 0.5) * 0.12;
        double finalChance = Math.max(0.02, Math.min(0.96, baseChance + variation));
        
        double shotRoll = random.nextDouble();
        boolean isGoal = shotRoll < finalChance;
        double saveChance = 0.10 + (goalkeeperDefense / 1450.0) + (xG * 0.28);
        saveChance = Math.max(0.14, Math.min(0.48, saveChance));
        boolean isSaved = !isGoal && random.nextDouble() < saveChance;
        
        log.debug("Shot duel: {} (xG: {:.2f}), chance={:.2f}, result={}", 
                shooter.getName(), xG, finalChance, 
                isGoal ? "GOAL" : isSaved ? "SAVED" : "MISSED");
        
        return new DuelResult(isGoal, isSaved, !isGoal && !isSaved, xG);
    }

    public DuelResult resolveTackleDuel(Player attacker, Player defender) {
        // Attacker snaga (tehnike + driblinga)
        double attackerStrength = attacker.getSkills().getTechnique() * 1.1 +
                                 attacker.getSkills().getStriker() * 0.25 +
                                 (attacker.getSkills().getPace() / 1.8);
        
        // Defender snaga (odbrane) - Defenders have a natural advantage in tackles
        double defenderStrength = defender.getSkills().getDefender() * 1.45 +
                                 (defender.getSkills().getPace() / 2.2);
        
        // Osnovna šansa za pobenu
        double attackerWinChance = attackerStrength / (attackerStrength + defenderStrength);
        
        // Random varijacija
        double variation = (random.nextDouble() - 0.5) * 0.15;
        double finalChance = Math.max(0.20, Math.min(0.80, attackerWinChance + variation));
        
        boolean attackerWins = random.nextDouble() < finalChance;
        
        log.debug("Tackle duel: {} vs {}, chance={:.2f}, winner={}", 
                attacker.getName(), defender.getName(), finalChance,
                attackerWins ? attacker.getName() : defender.getName());
        
        return new DuelResult(attackerWins, false, !attackerWins);
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
