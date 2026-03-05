package org.example.footballmanager.engines;

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

    // Default thresholds for duel quality
    private static final double CLEAN_THRESHOLD = 2.8;
    private static final double PARTIAL_THRESHOLD = 0.6;

    // Duel result
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

    // Duel outcome quality
    public enum DuelOutcomeQuality {CLEAN, PARTIAL, FAIL}

    // Card hints
    public enum CardHint {NONE, YELLOW, RED}

    // Injury hints
    public enum InjuryHint {NONE, MINOR, SERIOUS}

    // Next state after duel
    public enum DuelNextState {CONTINUE, STOPPAGE}

    // Duel type with skill weight maps
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

    /** Main method for resolving a duel */
    public static DuelResult resolveDuel(Player attacker, Player defender, MatchContext context, DuelType type) {
        // 1. Base power
        double attackBase = calculateBasePower(attacker, type.getSkillWeights());
        double defenseBase = calculateBasePower(defender, type.getDefensiveWeights());

        // 2. Modifiers (stamina, form, fatigue)
        double attackMod = calculateModifiers(attacker, context);
        double defenseMod = calculateModifiers(defender, context);

        double attackFinal = attackBase * attackMod;
        double defenseFinal = defenseBase * defenseMod;

        if (type == DuelType.SHOOTING) {
            double finishing = normalize(attacker.getSkills().getStriker()) * 0.6 + normalize(attacker.getSkills().getTechnique()) * 0.4;
            double goalkeeping = normalize(defender.getSkills().getGoalkeeper());

            attackFinal *= 1.00 + (finishing - 0.5) * 0.30;
            defenseFinal *= 1.00 + (goalkeeping - 0.5) * 0.28;
        }

        // 3. Random factor
        attackFinal *= randomFactor(attacker);
        defenseFinal *= randomFactor(defender);

        // 4. Delta and outcome
        double delta = attackFinal - defenseFinal;
        double cleanThreshold = getCleanThreshold(type);
        double partialThreshold = getPartialThreshold(type);
        DuelOutcomeQuality quality = delta > cleanThreshold ? DuelOutcomeQuality.CLEAN
                : delta > partialThreshold ? DuelOutcomeQuality.PARTIAL
                : DuelOutcomeQuality.FAIL;

        // 5. Foul and injury
        boolean foul = calculateFoul(attacker, defender, delta, type, context);
        CardHint card = foul ? determineCard(defender, delta) : CardHint.NONE;
        InjuryHint injury = calculateInjury(attacker, defender, delta, context);

        // 6. Next state
        DuelNextState nextState = foul ? DuelNextState.STOPPAGE : DuelNextState.CONTINUE;

        return new DuelResult(attacker, defender, quality, foul, card, injury, nextState);
    }

    private static double getCleanThreshold(DuelType type) {
        if (type == DuelType.SHOOTING) {
            return 0.55;
        }
        return CLEAN_THRESHOLD;
    }

    private static double getPartialThreshold(DuelType type) {
        if (type == DuelType.SHOOTING) {
            return -0.20;
        }
        return PARTIAL_THRESHOLD;
    }

    /** Generic method for base power */
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

    /** Modifiers: stamina, form, fatigue */
    private static double calculateModifiers(Player player, MatchContext context) {
        double mod = 1.0;
        mod *= 0.78 + 0.22 * normalize(player.getSkills().getStamina());
        mod *= context.getFatigueFactor();
        mod *= 0.85 + 0.25 * (player.getForm() / 10.0);
        return mod;
    }

    /** Random factor - less stable players have more variance */
    private static double randomFactor(Player player) {
        double instability = 0.1 + 0.2 * (1.0 - player.getForm() / 10.0);
        return 1.0 + (random.nextDouble() * 2 - 1) * instability;
    }

    private static double normalize(int raw) {
        if (raw <= 20) {
            return Math.max(0.0, Math.min(1.0, raw / 20.0));
        }
        return Math.max(0.0, Math.min(1.0, raw / 100.0));
    }

    /** Calculate foul probability */
    private static boolean calculateFoul(Player attacker, Player defender, double delta, DuelType type, MatchContext context) {
        double chance = 0.0;
        if (Math.abs(delta) < PARTIAL_THRESHOLD) chance += 0.16;
        if (normalize(defender.getSkills().getDefender()) < 0.42) chance += 0.08;
        if (type == DuelType.TACKLE) chance += 0.08;
        return random.nextDouble() < chance;
    }

    /** Determine card severity based on delta and defender skill */
    private static CardHint determineCard(Player defender, double delta) {
        if (delta < -CLEAN_THRESHOLD && random.nextDouble() < 0.15) {
            return CardHint.RED;
        }
        return CardHint.YELLOW;
    }

    /** Calculate injury probability */
    private static InjuryHint calculateInjury(Player attacker, Player defender, double delta, MatchContext context) {
        double chance = 0.0;
        if (delta < -CLEAN_THRESHOLD) chance += 0.1;
        if (attacker.getSkills().getFatigue() > 70) chance += 0.2;
        if (random.nextDouble() < chance * 0.3) return InjuryHint.SERIOUS;
        return random.nextDouble() < chance ? InjuryHint.MINOR : InjuryHint.NONE;
    }
}
