package org.example.footballmanager.engines;

import lombok.Data;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class DuelResolver {

    private final Random random = new Random();

    /**
     * Rezolvuje duel između šutača i golmana
     * 
     * Šansa za gol zavisi od:
     * - Striker skill šutača
     * - Goalkeeper skill golmana
     * - Distancu (procena)
     */
    public DuelResult resolveShotDuel(Player shooter, Player goalkeeper) {
        // Iznos za šut
        double shooterPower = shooter.getSkills().getStriker() +
                             shooter.getSkills().getTechnique() +
                             (shooter.getSkills().getPace() / 3.0);
        
        // Defensa golmana
        double goalkeeperDefense = goalkeeper.getSkills().getGoalkeeper() * 2.9 +
                goalkeeper.getSkills().getDefender() * 0.55;
        
        // Osnovni faktor šanse
        double baseChance = shooterPower / (shooterPower + goalkeeperDefense);
        
        // Random varijacija (+/- 10%)
        double variation = (random.nextDouble() - 0.5) * 0.14;
        double finalChance = Math.max(0.06, Math.min(0.58, baseChance + variation));
        
        // Normalno: 50% šansa je 50% golova (ili 60% ako je igrač dobar)
        double shotRoll = random.nextDouble();
        
        boolean isGoal = shotRoll < finalChance;
        boolean isSaved = !isGoal && random.nextDouble() < 0.56; // vecina ne-gol suteva ide u odbranu golmana
        
        log.debug("Shot duel: {} vs {}, chance={:.2f}, result={}", 
                shooter.getName(), goalkeeper.getName(), finalChance, 
                isGoal ? "GOAL" : isSaved ? "SAVED" : "MISSED");
        
        return new DuelResult(isGoal, isSaved, !isGoal && !isSaved);
    }

    /**
     * Rezolvuje duel (tackle) između dva igrača
     * 
     * Pobednik zavisi od:
     * - Defender skill (odbrane)
     * - Attacker skill (tehnike, driblinga)
     * - Pace (brzina je važna)
     */
    public DuelResult resolveTackleDuel(Player attacker, Player defender) {
        // Attacker snaga (tehnike + driblinga)
        double attackerStrength = attacker.getSkills().getTechnique() +
                                 attacker.getSkills().getStriker() * 0.35 +
                                 attacker.getSkills().getPassing() * 0.2 +
                                 (attacker.getSkills().getPace() / 2.0);
        
        // Defender snaga (odbrane)
        double defenderStrength = defender.getSkills().getDefender() * 1.35 +
                                 (defender.getSkills().getPace() / 2.4);
        
        // Osnovna šansa za pobenu
        double attackerWinChance = attackerStrength / (attackerStrength + defenderStrength);
        
        // Random varijacija
        double variation = (random.nextDouble() - 0.5) * 0.12;
        double finalChance = Math.max(0.24, Math.min(0.76, attackerWinChance + variation));
        
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
