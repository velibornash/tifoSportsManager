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
        double onTargetRate = goalRate + saveRate;

        assertTrue(goalRate > 0.08, "Goal rate should not collapse to near-zero");
        assertTrue(onTargetRate > 0.38,
                () -> "Medium-quality shots should now reach the frame often enough, got on-target rate " + onTargetRate);
        assertTrue(saveRate > 0.16 && saveRate < 0.65,
                () -> "Saved shots should stay present without dominating medium-quality attempts, got " + saveRate);
        assertTrue(missRate > 0.18 && missRate < 0.60,
                () -> "Misses should still exist but no longer dominate ordinary shots, got " + missRate);
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

    @Test
    void openGoalChancesConvertOverwhelminglyOftenFromCentralRange() {
        DuelResolver resolver = new DuelResolver(new Random(20260310L));
        Player shooter = player("Finisher", Position.ATT, 17, 15, 16, 3, 5);

        int goals = 0;
        int misses = 0;
        for (int i = 0; i < 1000; i++) {
            DuelResolver.DuelResult result = resolver.resolveOpenGoalShot(shooter, 88.0, 50.0);
            if (result.isGoal()) {
                goals++;
            } else {
                misses++;
            }
        }

        double goalRate = goals / 1000.0;
        double missRate = misses / 1000.0;
        assertTrue(goalRate > 0.80, () -> "Open-goal central shots should score most of the time, got " + goalRate);
        assertTrue(missRate < 0.20, () -> "Open-goal misses should stay rare, got " + missRate);
    }

    @Test
    void penaltiesConvertAroundSeventyFivePercent() {
        DuelResolver resolver = new DuelResolver(new Random(20260310L));
        Player shooter = player("PenaltyTaker", Position.ATT, 17, 15, 15, 3, 5);
        Player goalkeeper = player("Keeper", Position.GK, 4, 7, 10, 16, 12);

        int goals = 0;
        int saves = 0;
        int misses = 0;
        for (int i = 0; i < 2500; i++) {
            DuelResolver.DuelResult result = resolver.resolvePenalty(shooter, goalkeeper);
            if (result.isGoal()) {
                goals++;
            } else if (result.isSaved()) {
                saves++;
            } else if (result.isMissed()) {
                misses++;
            }
        }

        double goalRate = goals / 2500.0;
        double saveRate = saves / 2500.0;
        double missRate = misses / 2500.0;

        assertTrue(goalRate > 0.70 && goalRate < 0.83,
                () -> "Penalty conversion should stay around 75%, got " + goalRate);
        assertTrue(saveRate > 0.10 && saveRate < 0.24,
                () -> "Saved penalties should remain a visible share, got " + saveRate);
        assertTrue(missRate > 0.03 && missRate < 0.12,
                () -> "Penalty misses should stay rarer than saves, got " + missRate);
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

