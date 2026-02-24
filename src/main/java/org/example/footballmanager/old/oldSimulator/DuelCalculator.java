package org.example.footballmanager.old.oldSimulator;

import lombok.Getter;
import lombok.Setter;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.util.match.MatchContext;

import java.util.Map;
import java.util.Random;

@Getter
@Setter
public class DuelCalculator {

    private static final Random random = new Random();

    // Thresholds za kvalitet duela
    private static final double CLEAN_THRESHOLD = 15.0;
    private static final double PARTIAL_THRESHOLD = 5.0;

    // 🏆 Rezultat jednog duela
    @Getter
    @Setter
    public static class DuelResult {
        private Player attacker;
        private Player defender;
        private DuelOutcomeQuality outcome;
        private boolean foulOccurred;
        private CardHint cardHint;
        private InjuryHint injuryHint;
        private DuelNextState nextState;

        public DuelResult(Player attacker, Player defender, DuelOutcomeQuality outcome,
                          boolean foulOccurred, CardHint cardHint, InjuryHint injuryHint,
                          DuelNextState nextState) {
            this.attacker = attacker;
            this.defender = defender;
            this.outcome = outcome;
            this.foulOccurred = foulOccurred;
            this.cardHint = cardHint;
            this.injuryHint = injuryHint;
            this.nextState = nextState;
        }
    }

    // 🎯 Ishod duela
    public enum DuelOutcomeQuality {CLEAN, PARTIAL, FAIL}

    // 🟡 Kartoni
    public enum CardHint {NONE, YELLOW, RED}

    // ⚕️ Povrede
    public enum InjuryHint {NONE, MINOR, SERIOUS}

    // ⏩ Sledeće stanje posle duela
    public enum DuelNextState {CONTINUE, STOPPAGE}

    // ⚽ Tip duela sa skill weight mapama
    public enum DuelType {
        DRIBBLING, SHOOTING, PASSING, TACKLE, JUMP;

        public enum SkillWeight {PACE, TECH, PLAY, PASSING, STR, DEF, GK, STAMINA, FATIGUE}

        public Map<SkillWeight, Double> getSkillWeights() {
            return switch (this) {
                case DRIBBLING -> Map.of(
                        SkillWeight.TECH, 0.4,
                        SkillWeight.PACE, 0.3,
                        SkillWeight.PLAY, 0.2,
                        SkillWeight.STR, 0.1
                );
                case PASSING -> Map.of(
                        SkillWeight.PLAY, 0.5,
                        SkillWeight.PASSING, 0.5
                );
                case SHOOTING -> Map.of(
                        SkillWeight.STR, 0.5,
                        SkillWeight.TECH, 0.3,
                        SkillWeight.PACE, 0.2
                );
                case TACKLE -> Map.of(
                        SkillWeight.DEF, 0.5,
                        SkillWeight.PACE, 0.3,
                        SkillWeight.PLAY, 0.2
                );
                case JUMP -> Map.of(
                        SkillWeight.PACE, 0.4,
                        SkillWeight.DEF, 0.4,
                        SkillWeight.STAMINA, 0.2
                );
            };
        }

        public Map<SkillWeight, Double> getDefensiveWeights() {
            return switch (this) {
                case DRIBBLING -> Map.of(
                        SkillWeight.DEF, 0.5,
                        SkillWeight.PACE, 0.3,
                        SkillWeight.PLAY, 0.2
                );
                case PASSING -> Map.of(
                        SkillWeight.DEF, 0.3,
                        SkillWeight.PACE, 0.3,
                        SkillWeight.PLAY, 0.4
                );
                case SHOOTING -> Map.of(
                        SkillWeight.DEF, 0.4,
                        SkillWeight.GK, 0.4,
                        SkillWeight.PACE, 0.2
                );
                case TACKLE -> Map.of(
                        SkillWeight.DEF, 0.6,
                        SkillWeight.PACE, 0.3,
                        SkillWeight.PLAY, 0.1
                );
                case JUMP -> Map.of(
                        SkillWeight.DEF, 0.5,
                        SkillWeight.PACE, 0.3,
                        SkillWeight.STAMINA, 0.2
                );
            };
        }
    }

    // 🔹 Glavna metoda za rešavanje duela
    public static DuelResult resolveDuel(Player attacker, Player defender, MatchContext context, DuelType type) {
        // 1️⃣ BasePower
        double attackBase = calculateBasePower(attacker, type.getSkillWeights());
        double defenseBase = calculateBasePower(defender, type.getDefensiveWeights());

        // 2️⃣ Modifikatori (stamina, forma, fatigue)
        double attackMod = calculateModifiers(attacker, context);
        double defenseMod = calculateModifiers(defender, context);

        double attackFinal = attackBase * attackMod;
        double defenseFinal = defenseBase * defenseMod;

        // 3️⃣ Random faktor
        attackFinal *= randomFactor(attacker);
        defenseFinal *= randomFactor(defender);

        // 4️⃣ Delta i outcome
        double delta = attackFinal - defenseFinal;
        DuelOutcomeQuality quality = delta > CLEAN_THRESHOLD ? DuelOutcomeQuality.CLEAN
                : delta > PARTIAL_THRESHOLD ? DuelOutcomeQuality.PARTIAL
                : DuelOutcomeQuality.FAIL;

        // 5️⃣ Foul i povreda
        boolean foul = calculateFoul(attacker, defender, delta, type, context);
        CardHint card = foul ? CardHint.YELLOW : CardHint.NONE;
        InjuryHint injury = calculateInjury(attacker, defender, delta, context);

        // 6️⃣ Sledeće stanje
        DuelNextState nextState = DuelNextState.CONTINUE;

        return new DuelResult(attacker, defender, quality, foul, card, injury, nextState);
    }

    /** Generička metoda za BasePower */
    private static double calculateBasePower(Player player, Map<DuelType.SkillWeight, Double> weights) {
        double power = 0.0;
        for (var entry : weights.entrySet()) {
            power += getSkillValue(player, entry.getKey()) * entry.getValue();
        }
        return power;
    }

    /** Skill value getter */
    private static double getSkillValue(Player player, DuelType.SkillWeight skill) {
        return switch (skill) {
            case PACE -> player.getSkills().getPace();
            case TECH -> player.getSkills().getTechnique();
            case PLAY -> player.getSkills().getPlaymaker();
            case PASSING -> player.getSkills().getPassing();
            case STR -> player.getSkills().getStriker();
            case DEF -> player.getSkills().getDefender();
            case GK -> player.getSkills().getGoalkeeper();
            case STAMINA -> player.getSkills().getStamina();
            case FATIGUE -> 100 - player.getSkills().getFatigue();
        };
    }

    /** Modifikatori: stamina, forma, fatigue */
    private static double calculateModifiers(Player player, MatchContext context) {
        double mod = 1.0;
        mod *= 0.7 + 0.3 * (player.getSkills().getStamina() / 100.0);
        mod *= context.getFatigueFactor(); // globalni faktor zamora
        mod *= 0.85 + 0.25 * (player.getForm() / 10.0);
        return mod;
    }

    /** Random faktor */
    private static double randomFactor(Player player) {
        double instability = 0.1 + 0.2 * (1.0 - player.getForm() / 10.0);
        return 1.0 + (random.nextDouble() * 2 - 1) * instability;
    }

    /** Izračunavanje faula */
    private static boolean calculateFoul(Player attacker, Player defender, double delta, DuelType type, MatchContext context) {
        double chance = 0.0;
        if (Math.abs(delta) < PARTIAL_THRESHOLD) chance += 0.2;
        if (defender.getSkills().getDefender() < 50) chance += 0.1;
        return random.nextDouble() < chance;
    }

    /** Izračunavanje povrede */
    private static InjuryHint calculateInjury(Player attacker, Player defender, double delta, MatchContext context) {
        double chance = 0.0;
        if (delta < -CLEAN_THRESHOLD) chance += 0.1;
        if (attacker.getSkills().getFatigue() > 70) chance += 0.2;
        return random.nextDouble() < chance ? InjuryHint.MINOR : InjuryHint.NONE;
    }
}
