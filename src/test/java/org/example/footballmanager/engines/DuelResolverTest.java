package org.example.footballmanager.engines;

import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Position;
import org.example.footballmanager.model.Skills;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DuelResolverTest {

    @Test
    void mediumQualityShotsStillProduceMeaningfulMissRate() {
        DuelResolver resolver = new DuelResolver(new Random(12345L));
        Player shooter = player("Striker", Position.ATT, 16, 14, 15, 4, 6);
        Player goalkeeper = player("Goalkeeper", Position.GK, 4, 8, 10, 16, 12);

        int goals = 0;
        int saves = 0;
        int misses = 0;

        for (int i = 0; i < 2000; i++) {
            DuelResolver.DuelResult result = resolver.resolveShotDuel(shooter, goalkeeper, 82.0, 50.0);
            if (result.isGoal()) {
                goals++;
            } else if (result.isSaved()) {
                saves++;
            } else if (result.isMissed()) {
                misses++;
            }
        }

        double goalRate = goals / 2000.0;
        double saveRate = saves / 2000.0;
        double missRate = misses / 2000.0;

        assertTrue(goalRate > 0.08, "Goal rate should not collapse to near-zero");
        assertTrue(saveRate > 0.12 && saveRate < 0.60,
                () -> "Saved shots should stay present without dominating medium-quality attempts, got " + saveRate);
        assertTrue(missRate > 0.20,
                () -> "There should be enough missed shots so 0-0 matches do not inflate on-target stats, got " + missRate);
    }

    @Test
    void closeRangeCentralShotsConvertOftenEnoughToAvoidTooManyNilNilMatches() {
        DuelResolver resolver = new DuelResolver(new Random(20260307L));
        Player shooter = player("Poacher", Position.ATT, 17, 15, 16, 4, 6);
        Player goalkeeper = player("Keeper", Position.GK, 4, 7, 10, 16, 12);

        int goals = 0;
        for (int i = 0; i < 1200; i++) {
            DuelResolver.DuelResult result = resolver.resolveShotDuel(shooter, goalkeeper, 89.0, 50.0);
            if (result.isGoal()) {
                goals++;
            }
        }

        double goalRate = goals / 1200.0;
        assertTrue(goalRate > 0.22, () -> "High-quality central chances should convert at a healthy clip, got " + goalRate);
    }

    private Player player(String name, Position position, int striker, int technique, int pace, int goalkeeper, int defender) {
        Skills skills = new Skills();
        skills.setStriker(striker);
        skills.setTechnique(technique);
        skills.setPace(pace);
        skills.setGoalkeeper(goalkeeper);
        skills.setDefender(defender);
        skills.setPassing(10);
        skills.setPlaymaker(10);
        skills.setStamina(12);
        skills.setFatigue(2);

        Player player = new Player();
        player.setName(name);
        player.setPosition(position);
        player.setSkills(skills);
        player.setForm(7.0);
        player.setRating(70);
        return player;
    }
}

