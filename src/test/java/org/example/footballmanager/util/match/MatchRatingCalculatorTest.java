package org.example.footballmanager.util.match;

import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Position;
import org.example.footballmanager.model.Skills;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchRatingCalculatorTest {

    @Test
    void goalkeeperSavesAndCleanSheetLiftRating() {
        Player goalkeeper = player("Keeper", Position.GK, 14, 9, 10, 15, 12, 7.2);

        int strongPerformance = MatchRatingCalculator.calculate(goalkeeper, 0, 0, 0, 6, true, 0, 0, 1, 0, 90);
        int quietPerformance = MatchRatingCalculator.calculate(goalkeeper, 0, 0, 0, 0, false, 0, 0, 1, 2, 90);

        assertTrue(strongPerformance > quietPerformance,
                () -> "Expected saves + clean sheet to beat a quiet game, got " + strongPerformance + " vs " + quietPerformance);
    }

    @Test
    void defensiveActionsBeatCardsAndConceding() {
        Player defender = player("Stopper", Position.DEF, 6, 10, 10, 4, 15, 7.0);

        int strongPerformance = MatchRatingCalculator.calculate(defender, 0, 0, 5, 0, true, 0, 0, 2, 0, 90);
        int poorPerformance = MatchRatingCalculator.calculate(defender, 0, 0, 1, 0, false, 1, 1, 0, 3, 90);

        assertTrue(strongPerformance > poorPerformance,
                () -> "Expected interceptions/clean sheet to beat cards + concessions, got " + strongPerformance + " vs " + poorPerformance);
    }

    private Player player(String name,
                          Position position,
                          int striker,
                          int technique,
                          int pace,
                          int goalkeeper,
                          int defender,
                          double form) {
        Skills skills = new Skills();
        skills.setStriker(striker);
        skills.setTechnique(technique);
        skills.setPace(pace);
        skills.setGoalkeeper(goalkeeper);
        skills.setDefender(defender);
        skills.setPassing(11);
        skills.setPlaymaker(11);
        skills.setStamina(13);
        skills.setFatigue(2);

        Player player = new Player();
        player.setName(name);
        player.setPosition(position);
        player.setSkills(skills);
        player.setForm(form);
        player.setRating(70);
        return player;
    }
}