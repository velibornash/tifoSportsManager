package org.example.footballmanager.newLogic.engine_v1;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.util.match.MatchContext;

import java.util.Random;

@Component
@RequiredArgsConstructor
public class DuelCalculator {

    private static final Random random = new Random();

    public enum DuelType {
        SHOOTING, TACKLE, DRIBBLING
    }

    public enum DuelOutcomeQuality {
        CLEAN, PARTIAL, FAIL
    }

    public enum CardHint {
        NONE, YELLOW, RED
    }

    @Getter
    @RequiredArgsConstructor
    public static class DuelResult {
        private final DuelOutcomeQuality outcome;
        private final boolean foulOccurred;
        private final CardHint cardHint;

        public DuelResult(DuelOutcomeQuality outcome) {
            this(outcome, false, CardHint.NONE);
        }
    }

    public static DuelResult resolveDuel(Player attacker, Player defender, MatchContext context, DuelType type) {
        if (attacker == null || defender == null) {
            return new DuelResult(DuelOutcomeQuality.PARTIAL);
        }

        double attackerSkill = getSkillForType(attacker, type);
        double defenderSkill = getDefenderSkill(defender, type);
        double fatigueFactor = context != null ? context.getFatigueFactor() : 1.0;
        int minute = context != null ? context.getCurrentMinute() : 45;

        double fatigueMod = 1.0 - (fatigueFactor - 1.0) * 0.3;
        double minuteFatigue = 1.0 - Math.max(0, (minute - 60)) * 0.005;

        double attackerPower = attackerSkill * fatigueMod * minuteFatigue;
        double defenderPower = defenderSkill * fatigueMod * minuteFatigue;

        double diff = attackerPower - defenderPower;
        double roll = random.nextDouble();

        boolean foul = false;
        CardHint card = CardHint.NONE;

        if (type == DuelType.TACKLE) {
            double foulChance = 0.15 + Math.max(0, -diff) * 0.08;
            if (roll < foulChance) {
                foul = true;
                double cardRoll = random.nextDouble();
                if (cardRoll < 0.03) {
                    card = CardHint.RED;
                } else if (cardRoll < 0.18) {
                    card = CardHint.YELLOW;
                }
            }
        }

        double cleanThreshold = 0.25 + diff * 0.15;
        double partialThreshold = cleanThreshold + 0.35 + Math.abs(diff) * 0.05;

        if (roll < cleanThreshold) {
            return new DuelResult(DuelOutcomeQuality.CLEAN, false, card);
        } else if (roll < partialThreshold) {
            return new DuelResult(DuelOutcomeQuality.PARTIAL, foul, card);
        } else {
            return new DuelResult(DuelOutcomeQuality.FAIL, foul, card);
        }
    }

    private static double getSkillForType(Player player, DuelType type) {
        if (player == null || player.getSkills() == null) return 0.5;
        return switch (type) {
            case SHOOTING -> {
                double shooting = player.getSkills().getStriker() / 100.0;
                double technique = player.getSkills().getTechnique() / 100.0;
                yield (shooting * 0.6 + technique * 0.4);
            }
            case TACKLE -> {
                double defending = player.getSkills().getDefender() / 100.0;
                double pace = player.getSkills().getPace() / 100.0;
                yield (defending * 0.7 + pace * 0.3);
            }
            case DRIBBLING -> {
                double technique = player.getSkills().getTechnique() / 100.0;
                double pace = player.getSkills().getPace() / 100.0;
                double playmaker = player.getSkills().getPlaymaker() / 100.0;
                yield (technique * 0.4 + pace * 0.3 + playmaker * 0.3);
            }
        };
    }

    private static double getDefenderSkill(Player defender, DuelType type) {
        if (defender == null || defender.getSkills() == null) return 0.5;
        return switch (type) {
            case SHOOTING -> {
                double gk = defender.getSkills().getGoalkeeper() / 100.0;
                double reflexes = defender.getSkills().getPace() / 100.0;
                yield (gk * 0.7 + reflexes * 0.3);
            }
            case TACKLE -> {
                double defending = defender.getSkills().getDefender() / 100.0;
                double pace = defender.getSkills().getPace() / 100.0;
                yield (defending * 0.7 + pace * 0.3);
            }
            case DRIBBLING -> {
                double defending = defender.getSkills().getDefender() / 100.0;
                double pace = defender.getSkills().getPace() / 100.0;
                yield (defending * 0.6 + pace * 0.4);
            }
        };
    }
}
