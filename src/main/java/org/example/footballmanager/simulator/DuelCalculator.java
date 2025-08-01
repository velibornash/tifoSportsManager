package org.example.footballmanager.simulator;

import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Position;
import org.example.footballmanager.simulator.MatchContext;

import java.util.Random;

public class DuelCalculator {

    private static final Random random = new Random();

    public static boolean winDuel(Player attacker, Player defender, MatchContext context) {
        double attackScore = getAttackScore(attacker) * context.getFatigueFactor();
        double defenseScore = getDefenseScore(defender) * context.getFatigueFactor();

        // Dodaj uticaj taktika (npr. agresija i pressing)
        double tacticModifier = (context.getMatch().getHomeTactics().getAggression() +
                context.getMatch().getHomeTactics().getPressing()) / 20.0;
        attackScore *= (1.0 + tacticModifier);
        defenseScore *= (1.0 + tacticModifier);

        double probability = attackScore / (attackScore + defenseScore);
        return random.nextDouble() < probability;
    }

    private static double getAttackScore(Player player) {
        double score = 0;
        score += player.getSkills().getPace() * 1.2;
        score += player.getSkills().getTechnique() * 1.5;
        score += player.getSkills().getStriker() * 2.0;
        score += player.getSkills().getPassing() * 0.5;
        score += player.getForm();
        return score;
    }

    private static double getDefenseScore(Player player) {
        double score = 0;
        score += player.getSkills().getDefender() * 2.0;
        score += player.getSkills().getPace();
        score += player.getSkills().getPlaymaker();
        score += player.getSkills().getTechnique() * 0.5;
        score += player.getForm();
        return score;
    }
}