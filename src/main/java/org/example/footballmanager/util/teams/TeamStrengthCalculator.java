package org.example.footballmanager.util.teams;

import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Position;
import org.example.footballmanager.model.tactics.Formation;
import org.example.footballmanager.model.tactics.Tactics;

import java.util.List;

public class TeamStrengthCalculator {

    public static double calculateTeamStrength(List<Player> players, Formation formation, Tactics tactics, boolean isHome) {
        if (players == null || players.isEmpty()) {
            return isHome ? 1.05 : 1.0;
        }

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
        double fatigueAvg = players.stream().mapToDouble(Player::getCurrentFatigue).average().orElse(5.0);

        double formModifier = 0.90 + (Math.max(1.0, Math.min(10.0, formAvg)) / 50.0);
        double talentModifier = 0.88 + (Math.max(1.0, Math.min(10.0, 10.0 - talentAvg)) / 40.0);
        double fatigueModifier = 1.04 - (Math.max(0.0, Math.min(10.0, fatigueAvg)) / 50.0);

        double tacticModifier = 0.90 + (
                tactics.getAggression() +
                tactics.getDefenseLine() +
                tactics.getPressing() +
                tactics.getPossession() +
                tactics.getCounterAttack() +
                tactics.getBallControl()
        ) / 60.0;

        double formationModifier = (formation.getOffenseModifier() +
                formation.getDefenseModifier() +
                formation.getPossessionModifier()) / 3.0;

        long goalkeepers = players.stream().filter(p -> p.getPositionEnum() == Position.GK).count();
        long defenders = players.stream().filter(p -> p.getPositionEnum() == Position.DEF).count();
        long midfielders = players.stream().filter(p -> p.getPositionEnum() == Position.MID || p.getPositionEnum() == Position.WNG).count();
        long attackers = players.stream().filter(p -> p.getPositionEnum() == Position.ATT).count();

        double balanceModifier = 1.0;
        if (goalkeepers == 0) balanceModifier -= 0.18;
        if (defenders < 3) balanceModifier -= 0.08;
        if (midfielders < 3) balanceModifier -= 0.06;
        if (attackers == 0) balanceModifier -= 0.05;
        if (players.size() < 11) balanceModifier -= (11 - players.size()) * 0.03;
        balanceModifier = Math.max(0.65, balanceModifier);

        double homeAdvantage = isHome ? 1.05 : 1.0;

        return skillSum * formModifier * talentModifier * fatigueModifier * tacticModifier * formationModifier * balanceModifier * homeAdvantage;
    }
}
