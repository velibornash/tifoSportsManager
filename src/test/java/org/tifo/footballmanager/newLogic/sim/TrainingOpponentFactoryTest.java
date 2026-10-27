package org.tifo.footballmanager.newLogic.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.tifo.footballmanager.newLogic.sim.model.Player;
import org.tifo.footballmanager.newLogic.sim.model.PlayerSkills;

/**
 * The training opponent is exactly the number the manager picked, or the feature proves nothing.
 *
 * <p><b>Why these four assertions and not one.</b> The whole point of a training match (owner,
 * 2026-10-27) is that a tactic is tested against a <em>known</em> standard: the strength on the
 * slider is 8..20 and every one of the opponent's men arrives at that number. A squad that is
 * almost right — eleven at the number but seven reserves at a default, or a scale applied to the
 * visible skill while the engine reads the exact one — still produces a match that looks fine and
 * measures the wrong thing, which is the failure this class exists to catch.
 *
 * <p>Plain JUnit on purpose: {@link TrainingOpponentFactory} builds everything in memory, and a
 * test that booted Spring to check a static factory would be testing the boot.
 */
class TrainingOpponentFactoryTest {

    private static final int[] BAND = {8, 14, 20};

    // ---------- the number ----------

    @Test
    @DisplayName("every football skill of every man is the chosen number")
    void everyManCarriesTheChosenNumber() {
        for (int strength : BAND) {
            TrainingOpponentFactory.Squad squad = TrainingOpponentFactory.build(strength, null);

            for (Player man : allOf(squad)) {
                PlayerSkills skills = man.getSkills();
                double[] actual = {skills.pace(), skills.stamina(), skills.keeper(),
                        skills.technique(), skills.playmaking(), skills.passing(),
                        skills.striker(), skills.defender()};
                for (double value : actual) {
                    assertEquals(strength, value, 0.0001,
                            () -> "strength " + strength + " but " + man.getLabel()
                                    + " has a skill at " + value);
                }
            }
        }
    }

    @Test
    @DisplayName("a strength outside the band is brought inside it, not rejected and not obeyed")
    void aStrengthOutsideTheBandIsBroughtInsideIt() {
        assertEquals(8, TrainingOpponentFactory.clampStrength(1));
        assertEquals(8, TrainingOpponentFactory.clampStrength(-40));
        assertEquals(20, TrainingOpponentFactory.clampStrength(99));
        assertEquals(14, TrainingOpponentFactory.clampStrength(14));

        assertEquals(8, firstSkillOf(TrainingOpponentFactory.build(3, null)), 0.0001,
                "a request below the band must play against the floor, not against what was asked");
        assertEquals(20, firstSkillOf(TrainingOpponentFactory.build(99, null)), 0.0001,
                "a request above the band must play against the ceiling");
    }

    // ---------- the squad ----------

    @Test
    @DisplayName("eleven starters, seven reserves, and exactly one keeper in the XI")
    void elevenStarterSevenReservesAndOneKeeper() {
        TrainingOpponentFactory.Squad squad = TrainingOpponentFactory.build(12, null);

        assertEquals(11, squad.starters().size(),
                "the factory must hand the engine a full XI or the match falls back to stand-ins");
        assertEquals(7, squad.bench().size(),
                "a side that cannot substitute is not the side a manager is testing against");

        long keepers = squad.starters().stream().filter(Player::isGoalkeeper).count();
        assertEquals(1, keepers,
                "the XI needs exactly one keeper: none is an empty goal, two is an outfielder short");

        assertTrue(squad.bench().stream().anyMatch(Player::isGoalkeeper),
                "the reserves carry a second keeper, or one red card ends the test");
    }

    @Test
    @DisplayName("the opponent's men cannot be mistaken for players in the world")
    void theOpponentsIdsAreNegativeAndDistinct() {
        TrainingOpponentFactory.Squad squad = TrainingOpponentFactory.build(10, null);

        Set<String> seen = new HashSet<>();
        for (Player man : allOf(squad)) {
            assertTrue(man.getId().startsWith("-"),
                    () -> man.getLabel() + " carries id " + man.getId()
                            + ", which could collide with a real row");
            assertTrue(seen.add(man.getId()), () -> "duplicate id " + man.getId());
            assertTrue(man.getLabel().startsWith("Training "),
                    () -> man.getLabel() + " does not read as a training opponent");
        }
        assertEquals(18, seen.size(), "eighteen men, eighteen distinct ids");
    }

    // ---------- helpers ----------

    private List<Player> allOf(TrainingOpponentFactory.Squad squad) {
        List<Player> all = new java.util.ArrayList<>(squad.starters());
        all.addAll(squad.bench());
        return all;
    }

    private double firstSkillOf(TrainingOpponentFactory.Squad squad) {
        return squad.starters().get(0).getSkills().pace();
    }
}
