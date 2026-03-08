package org.example.footballmanager.util.teams;

import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Position;
import org.example.footballmanager.model.Skills;
import org.example.footballmanager.model.tactics.Formation;
import org.example.footballmanager.model.tactics.Tactics;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamStrengthCalculatorTest {

    @Test
    void balancedFullElevenBeatsShortUnbalancedSide() {
        List<Player> balanced = List.of(
                player(Position.GK), player(Position.DEF), player(Position.DEF), player(Position.DEF), player(Position.DEF),
                player(Position.MID), player(Position.MID), player(Position.MID), player(Position.WNG),
                player(Position.ATT), player(Position.ATT)
        );
        List<Player> shortSide = List.of(
                player(Position.DEF), player(Position.DEF), player(Position.MID), player(Position.MID),
                player(Position.WNG), player(Position.WNG), player(Position.ATT), player(Position.ATT), player(Position.ATT)
        );

        double balancedStrength = TeamStrengthCalculator.calculateTeamStrength(balanced, formation(), aggressiveTactics(), true);
        double shortStrength = TeamStrengthCalculator.calculateTeamStrength(shortSide, formation(), aggressiveTactics(), true);

        assertTrue(balancedStrength > shortStrength,
                () -> "Expected full balanced XI to beat short/unbalanced side, got " + balancedStrength + " vs " + shortStrength);
    }

    @Test
    void strongerTacticalIntentRaisesStrengthForSamePlayers() {
        List<Player> players = List.of(
                player(Position.GK), player(Position.DEF), player(Position.DEF), player(Position.DEF), player(Position.DEF),
                player(Position.MID), player(Position.MID), player(Position.MID), player(Position.WNG),
                player(Position.ATT), player(Position.ATT)
        );

        double assertive = TeamStrengthCalculator.calculateTeamStrength(players, formation(), aggressiveTactics(), false);
        double passive = TeamStrengthCalculator.calculateTeamStrength(players, formation(), passiveTactics(), false);

        assertTrue(assertive > passive,
                () -> "Expected assertive tactics to lift strength, got " + assertive + " vs " + passive);
    }

    private Player player(Position position) {
        Skills skills = new Skills();
        skills.setGoalkeeper(position == Position.GK ? 15 : 5);
        skills.setDefender(position == Position.DEF ? 14 : 9);
        skills.setPlaymaker(position == Position.MID || position == Position.WNG ? 13 : 8);
        skills.setPassing(11);
        skills.setTechnique(11);
        skills.setPace(position == Position.WNG || position == Position.ATT ? 13 : 10);
        skills.setStriker(position == Position.ATT ? 14 : 8);
        skills.setStamina(12);
        skills.setFatigue(2);

        Player player = new Player();
        player.setPosition(position);
        player.setSkills(skills);
        player.setForm(7.0);
        player.setTalent(4.5);
        return player;
    }

    private Formation formation() {
        Formation formation = new Formation();
        formation.setName("4-4-2");
        formation.setOffenseModifier(1.02);
        formation.setDefenseModifier(1.02);
        formation.setPossessionModifier(1.01);
        return formation;
    }

    private Tactics aggressiveTactics() {
        Tactics tactics = new Tactics();
        tactics.setAggression(7.5);
        tactics.setDefenseLine(6.4);
        tactics.setPressing(7.2);
        tactics.setPossession(5.8);
        tactics.setCounterAttack(5.4);
        tactics.setBallControl(5.9);
        return tactics;
    }

    private Tactics passiveTactics() {
        Tactics tactics = new Tactics();
        tactics.setAggression(3.2);
        tactics.setDefenseLine(3.5);
        tactics.setPressing(3.4);
        tactics.setPossession(4.0);
        tactics.setCounterAttack(3.1);
        tactics.setBallControl(3.8);
        return tactics;
    }
}