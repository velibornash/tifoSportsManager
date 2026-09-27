package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.PlayerTrainingIntensity;
import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.model.Skills;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.TrainingIntensity;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.PlayerTrainingIntensityRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;

import java.util.Random;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.example.footballmanager.newLogic.service.TrainingIntensityService.INJURY_FATIGUE_KNOCK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sprint 4.2 — the cost of training.
 *
 * <p>These are about the <b>trade</b>, not the numbers. A setting where every tier is strictly
 * better than every other one is not a decision, and a game with no decision has one strategy. So
 * the tests that matter are the ones that would fail if a tier became a strict upgrade.
 */
class TrainingIntensityServiceTest {

    private final PlayerTrainingIntensityRepository overrides = mock(PlayerTrainingIntensityRepository.class);
    private final PlayerRepository players = mock(PlayerRepository.class);
    private final TeamRepository teams = mock(TeamRepository.class);
    private final TrainingIntensityService service =
            new TrainingIntensityService(overrides, players, teams);

    private static Player player(String name, int fatigue) {
        Player p = new Player();
        p.setId(1L);
        p.setName(name);
        Team t = new Team();
        t.setId(7L);
        p.setTeam(t);
        Skills s = new Skills();
        for (SkillName skill : SkillName.values()) {
            // SkillName carries FATIGUE, and setSkill writes it - so set the skill first and the
            // condition we are actually testing with second, not the other way round.
            s.setSkill(skill, 10);
        }
        s.setFatigue(fatigue);
        p.setSkills(s);
        return p;
    }

    @Test
    @DisplayName("Every tier costs fatigue — otherwise there is no decision to make")
    void everyTierHasACost() {
        for (TrainingIntensity intensity : TrainingIntensity.values()) {
            assertTrue(intensity.weeklyFatigue() > 0,
                    intensity + " is free, which means a manager never has to weigh it");
        }
    }

    @Test
    @DisplayName("Intensity is a genuine trade: more growth buys more fatigue")
    void growthAndCostMoveTogether() {
        TrainingIntensity previous = null;
        for (TrainingIntensity intensity : TrainingIntensity.values()) {
            if (previous != null) {
                assertTrue(intensity.growthMultiplier() > previous.growthMultiplier(),
                        intensity + " does not grow more than " + previous);
                assertTrue(intensity.weeklyFatigue() > previous.weeklyFatigue(),
                        intensity + " costs no more fatigue than " + previous + ", so it dominates it");
            }
            previous = intensity;
        }
    }

    @Test
    @DisplayName("Very hard is the best week a rested player can have, and the worst week a tired one can")
    void veryHardWinsWhenRestedAndLosesWhenTired() {
        double restedChance = TrainingIntensity.VERY_HARD.injuryChance(0);
        double tiredChance = TrainingIntensity.VERY_HARD.injuryChance(100);
        double normalRested = TrainingIntensity.NORMAL.injuryChance(0);

        assertTrue(restedChance > normalRested,
                "if very hard is no riskier when rested, resting is pointless");
        assertTrue(tiredChance > restedChance * 5,
                "pushing a tired player must be far riskier, got " + tiredChance + " vs " + restedChance);
        assertTrue(tiredChance > 0.20,
                "a 100-fatigue player on very hard should be in real danger, got " + tiredChance);
    }

    @Test
    @DisplayName("A week of work adds fatigue and reports both sides")
    void applyAddsFatigueAndReportsIt() {
        // The dice are pinned because this method is random. VERY_HARD hurts a rested player 4.5% of
        // the time and the injury branch then adds a further 8 fatigue, so the exact figure asserted
        // here was previously right only 95.5% of the time - a 1-in-22 flake, which is how a red
        // build becomes something people re-run instead of read. 0.99 is above every chance in the
        // table, so this exercises the working hard, nobody hurt, path on purpose.
        service.useRandom(alwaysRolls(0.99));
        Player p = player("Vasa", 20);
        var outcome = service.apply(p, TrainingIntensity.VERY_HARD, 1, 5);

        assertEquals(20, outcome.fatigueBefore());
        assertEquals(20 + TrainingIntensity.VERY_HARD.weeklyFatigue(), outcome.fatigueAfter());
        assertTrue(outcome.costFatigue());
        assertFalse(outcome.injured());
        assertEquals(20 + TrainingIntensity.VERY_HARD.weeklyFatigue(), p.getSkills().getFatigue());
    }

    @Test
    @DisplayName("a training injury adds a knock on top of the week's work, and says so")
    void trainingInjuryStacksOnTopOfTheWeek() {
        // The branch the flaky assertion above was accidentally covering, now pinned deliberately.
        // 0.0 is below every chance in the table, so the roll is a hit.
        service.useRandom(alwaysRolls(0.0));
        Player p = player("Vasa", 20);

        var outcome = service.apply(p, TrainingIntensity.VERY_HARD, 1, 5);

        assertTrue(outcome.injured(), "the roll was a hit");
        assertEquals(20 + TrainingIntensity.VERY_HARD.weeklyFatigue() + INJURY_FATIGUE_KNOCK, outcome.fatigueAfter(),
                "an injury is not just the week: he comes back tired, not fresh");
        assertTrue(p.isInjured());
        assertTrue(p.getInjuryDaysRemaining() >= 7, "a training injury costs a real spell out");
    }

    /** A dice that always returns the same value, so a test can choose its branch. */
    private Random alwaysRolls(double value) {
        return new Random() {
            @Override
            public double nextDouble() {
                return value;
            }
        };
    }

    @Test
    @DisplayName("Fatigue is capped, so a season of very hard cannot exceed 100")
    void fatigueIsCapped() {
        Player p = player("Vasa", 96);
        service.apply(p, TrainingIntensity.VERY_HARD, 1, 5);
        assertEquals(100, p.getSkills().getFatigue());
    }

    @Test
    @DisplayName("Light is rehabilitation: it never injures anyone, however tired the player is")
    void lightNeverInjures() {
        for (int fatigue = 0; fatigue <= 100; fatigue += 10) {
            Player p = player("Patched", fatigue);
            for (int week = 1; week <= 12; week++) {
                var outcome = service.apply(p, TrainingIntensity.LIGHT, 1, week);
                assertFalse(outcome.injured(),
                        "a squad in rehabilitation got hurt at fatigue " + fatigue);
            }
            assertFalse(p.isInjured());
        }
    }

    @Test
    @DisplayName("An already injured player does not pick up a second injury")
    void noSecondInjuryWhileOut() {
        Player p = player("Broken", 95);
        p.setInjured(true);
        p.setInjuryDaysRemaining(20);

        for (int week = 1; week <= 12; week++) {
            assertFalse(service.apply(p, TrainingIntensity.VERY_HARD, 1, week).injured(),
                    "an injured player was injured again in week " + week);
        }
        assertEquals(20, p.getInjuryDaysRemaining(), "the lay-off was extended by a second injury");
    }

    @Test
    @DisplayName("A training injury writes a real lay-off, not a flag")
    void trainingInjuryIsRecorded() {
        Player p = player("Vasa", 100);
        // Very hard at 100 fatigue is over 40% per week, so this cannot miss for long.
        for (int week = 1; week <= 12 && !p.isInjured(); week++) {
            service.apply(p, TrainingIntensity.VERY_HARD, 1, week);
        }
        assertTrue(p.isInjured(), "very hard at 100 fatigue never hurt anyone in twelve weeks");
        assertTrue(p.getInjuryDaysRemaining() >= 7, "a training injury cost less than a week");
    }

    @Test
    @DisplayName("A player's own override wins over the club's setting for the week")
    void overrideBeatsTeamDefault() {
        Player p = player("Vasa", 0);
        when(overrides.findByPlayerIdAndSeasonAndWeek(1L, 1, 5)).thenReturn(Optional.of(
                PlayerTrainingIntensity.of(7L, 1L, 1, 5, TrainingIntensity.LIGHT)));

        assertEquals(TrainingIntensity.LIGHT, service.intensityFor(p, TrainingIntensity.VERY_HARD, 1, 5));
    }

    @Test
    @DisplayName("No override means the club's setting for the week")
    void fallsBackToTeamDefault() {
        Player p = player("Vasa", 0);
        when(overrides.findByPlayerIdAndSeasonAndWeek(1L, 1, 5)).thenReturn(Optional.empty());

        assertEquals(TrainingIntensity.VERY_HARD, service.intensityFor(p, TrainingIntensity.VERY_HARD, 1, 5));
    }

    @Test
    @DisplayName("A missing setting trains normally rather than taking out a week")
    void noSettingMeansNormal() {
        Player p = player("Vasa", 0);
        when(overrides.findByPlayerIdAndSeasonAndWeek(1L, 1, 5)).thenReturn(Optional.empty());

        assertEquals(TrainingIntensity.NORMAL, service.intensityFor(p, null, 1, 5));
        assertEquals(TrainingIntensity.NORMAL, service.intensityFor(null, null, null, null));
    }

    @Test
    @DisplayName("An override can only be written by the club the player actually plays for")
    void overrideMustBelongToTheClub() {
        Player p = player("Vasa", 0);
        when(players.findById(1L)).thenReturn(Optional.of(p));

        assertTrue(service.setOverride(7L, 1L, 1, 5, TrainingIntensity.LIGHT),
                "the owning club could not set an override");
        assertFalse(service.setOverride(99L, 1L, 1, 5, TrainingIntensity.LIGHT),
                "a rival club set an override for another club's player");
    }

    @Test
    @DisplayName("Setting an override twice replaces it rather than stacking")
    void settingTwiceReplaces() {
        Player p = player("Vasa", 0);
        when(players.findById(1L)).thenReturn(Optional.of(p));
        when(overrides.findByPlayerIdAndSeasonAndWeek(1L, 1, 5)).thenReturn(Optional.of(
                PlayerTrainingIntensity.of(7L, 1L, 1, 5, TrainingIntensity.VERY_HARD)));

        assertTrue(service.setOverride(7L, 1L, 1, 5, TrainingIntensity.LIGHT));

        var saved = org.mockito.ArgumentCaptor.forClass(PlayerTrainingIntensity.class);
        org.mockito.Mockito.verify(overrides, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        var last = saved.getValue();
        assertNotNull(last);
        assertEquals(TrainingIntensity.LIGHT, last.getIntensity(),
                "the stale VERY_HARD override was kept alongside the new one");
    }

    @Test
    @DisplayName("Clearing an override sends the player back to the club's setting")
    void clearReturnsToClubDefault() {
        Player p = player("Vasa", 0);
        when(players.findById(1L)).thenReturn(Optional.of(p));
        var existing = PlayerTrainingIntensity.of(7L, 1L, 1, 5, TrainingIntensity.VERY_HARD);
        when(overrides.findByPlayerIdAndSeasonAndWeek(1L, 1, 5)).thenReturn(Optional.of(existing));

        assertTrue(service.setOverride(7L, 1L, 1, 5, null));
        org.mockito.Mockito.verify(overrides).delete(existing);
    }

    @Test
    @DisplayName("Intensity names survive a round trip through the training screen")
    void byName() {
        assertEquals(TrainingIntensity.VERY_HARD, TrainingIntensity.byName("very_hard"));
        assertEquals(TrainingIntensity.VERY_HARD, TrainingIntensity.byName(" Very Hard "));
        assertEquals(TrainingIntensity.LIGHT, TrainingIntensity.byName("light"));
        assertNull(TrainingIntensity.byName("quite hard"));
        assertNull(TrainingIntensity.byName(null));
    }

    @Test
    @DisplayName("The tiers separate over a season, which is the only place the decision matters")
    void tiersSeparateOverASeason() {
        int normal = seasonFatigueLeft(TrainingIntensity.NORMAL, 24);
        int light = seasonFatigueLeft(TrainingIntensity.LIGHT, 24);
        int hard = seasonFatigueLeft(TrainingIntensity.VERY_HARD, 24);
        int veteran = seasonFatigueLeft(TrainingIntensity.VERY_HARD, 34);

        assertTrue(light < 5, "a squad in rehabilitation should arrive fresh, got " + light);
        assertTrue(normal < light + 15, "normal should stay near rest, got " + normal);
        // A 24-year-old recovers 22 a week. Very hard has to cost more than that, or the setting is
        // free and nobody ever has to think about it.
        assertTrue(hard > 40, "very hard did not accumulate over a season for a young player: " + hard);
        // A 34-year-old recovers 11, so the same setting is far more dangerous for him - the
        // decision has to depend on who you are pointing it at.
        assertTrue(veteran > hard, "very hard should be harder on a 34 than a 24, got " + veteran + " vs " + hard);
    }

    /** Fatigue a player of a given age is left with after twelve weeks at one intensity. */
    private int seasonFatigueLeft(TrainingIntensity intensity, int age) {
        int fatigue = 0;
        for (int week = 1; week <= 12; week++) {
            fatigue = Math.min(100, fatigue + intensity.weeklyFatigue());
            double ageFactor = age <= 24 ? 1.0 : age <= 29 ? 0.85 : age <= 33 ? 0.65 : 0.5;
            fatigue = Math.max(0, fatigue - (int) Math.round(22 * ageFactor));
        }
        return fatigue;
    }
}
