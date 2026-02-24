package org.example.footballmanager.util.teams;

import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Position;
import org.example.footballmanager.model.tactics.Formation;
import org.example.footballmanager.model.tactics.Tactics;

import java.util.List;

public class TeamStrengthCalculator {

    public static double calculateTeamStrength(List<Player> players, Formation formation, Tactics tactics, boolean isHome) {
        double skillSum = players.stream()
                .mapToDouble(p -> {
                    if (p.getPositionEnum() == null) {
                        System.out.println("Player with null position: " + p.getName() + " id=" + p.getId());
                    }
                    return p.getSkills().getRatingScore(
                            p.getPositionEnum() != null ? p.getPositionEnum() : Position.MID
                    );
                })
                .sum();

        double formAvg = players.stream().mapToDouble(Player::getForm).average().orElse(5.0);
        double talentAvg = players.stream().mapToDouble(Player::getTalent).average().orElse(5.0);

        double baseStrength = skillSum + (formAvg + talentAvg) * 5;

        double tacticModifier = (tactics.getAggression() + tactics.getPressing() +
                tactics.getCounterAttack() + tactics.getBallControl()) / 4.0;

        double formationModifier = (formation.getOffenseModifier() +
                formation.getDefenseModifier() +
                formation.getPossessionModifier()) / 3.0;

        double homeAdvantage = isHome ? 1.05 : 1.0;

        return baseStrength * (1 + tacticModifier / 10.0) * formationModifier * homeAdvantage;
    }
}
