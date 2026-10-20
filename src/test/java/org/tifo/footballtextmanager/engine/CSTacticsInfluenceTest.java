package org.tifo.footballtextmanager.engine;

import org.tifo.footballtextmanager.model.CSMatchResult;
import org.tifo.footballtextmanager.model.CSPlayStyle;
import org.tifo.footballtextmanager.model.CSTactics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tactics must be a real decision, not a costume.
 *
 * <p>Measured over 3,000 simulations per cell, the old model produced this for the home side
 * against an identical opponent: ATTACKING 1.760 goals, COUNTER 1.735, BALANCED 1.712,
 * DEFENSIVE 1.709. The spread was 3%, DEFENSIVE was indistinguishable from BALANCED, and the
 * cause was structural rather than a tuning miss: every style fed one scalar "strength", so a
 * defensive instruction could only subtract a little from it. It could never make a side harder
 * to score against. The choice therefore had no cost, ATTACKING was strictly dominant, and the
 * other three options were traps dressed as tactics.
 *
 * <p>These assertions are statistical by necessity — the simulator is stochastic — so they run
 * enough matches for the margins to clear noise comfortably, and they assert on the sign and the
 * ordering rather than on exact numbers that would break on any tuning change.
 */
class CSTacticsInfluenceTest {

    /** Enough for a 0.25-goal margin to clear noise by a wide factor. */
    private static final int MATCHES = 1500;

    private static final double MIN_MEANINGFUL_MARGIN = 0.05;

    @Test
    @DisplayName("Attacking scores more than defending")
    void attackingScoresMoreThanDefending() {
        double attacking = meanFor("4-4-2", CSPlayStyle.ATTACKING).scored();
        double defending = meanFor("4-4-2", CSPlayStyle.DEFENSIVE).scored();

        assertTrue(attacking - defending > MIN_MEANINGFUL_MARGIN,
                "Attacking scored " + fmt(attacking) + " and defending " + fmt(defending)
                        + ". A higher line that does not produce more goals is not a choice.");
    }

    @Test
    @DisplayName("Defending concedes fewer than attacking — the change that was missing")
    void defendingConcedesFewerThanAttacking() {
        double attackingConceded = meanFor("4-4-2", CSPlayStyle.ATTACKING).conceded();
        double defendingConceded = meanFor("4-4-2", CSPlayStyle.DEFENSIVE).conceded();

        assertTrue(attackingConceded - defendingConceded > MIN_MEANINGFUL_MARGIN,
                "Attacking conceded " + fmt(attackingConceded) + " and defending "
                        + fmt(defendingConceded)
                        + ". If defending does not defend, the defensive option is strictly worse "
                        + "rather than a trade-off.");
    }

    @Test
    @DisplayName("Attacking games are open and defensive games are cagey")
    void styleMovesTotalGoalsAsWellAsTheBalance() {
        double open = meanFor("4-4-2", CSPlayStyle.ATTACKING).total();
        double cagey = meanFor("4-4-2", CSPlayStyle.DEFENSIVE).total();

        assertTrue(open > cagey,
                "Attacking produced " + fmt(open) + " total goals and defending " + fmt(cagey)
                        + ". Style must change how many goals the game contains, not only who won them.");
    }

    @Test
    @DisplayName("A formation that does not fit the squad costs real goals")
    void formationFitIsWorthGoals() {
        double fits = meanFor("4-4-2", CSPlayStyle.BALANCED).scored();
        double doesNotFit = meanFor("4-1-5", CSPlayStyle.BALANCED).scored();

        assertTrue(fits - doesNotFit > 0.10,
                "A 4-4-2 squad scored " + fmt(fits) + " and the same squad in a 4-1-5 scored "
                        + fmt(doesNotFit) + ". Formation choice must be worth more than noise.");
    }

    private static String fmt(double value) {
        return String.format("%.3f", value);
    }

    private record Goals(double scored, double conceded) {
        double total() {
            return scored + conceded;
        }
    }

    /**
     * Averages the home side's scored/conceded goals across {@link #MATCHES} simulations against
     * an identical opponent.
     *
     * <p>Fresh player lists are built per match on purpose: the simulator mutates fatigue and
     * form on the objects it is given, so reusing one squad would let round 1's fatigue depress
     * round 1,500's attack and quietly bias whichever style happened to be measured first.
     */
    private static Goals meanFor(String formation, CSPlayStyle style) {
        CSMatchSimulator simulator = new CSMatchSimulator();
        var home = SquadFixture.team(1L, "Home");
        var away = SquadFixture.team(2L, "Away");

        CSTactics homeTactics = CSTactics.builder().formation(formation).style(style).build();
        CSTactics awayTactics = CSTactics.builder()
                .formation("4-4-2")
                .style(CSPlayStyle.BALANCED)
                .build();

        long scored = 0;
        long conceded = 0;

        for (int round = 1; round <= MATCHES; round++) {
            CSMatchResult result = simulator.simulate(
                    home,
                    SquadFixture.startingEleven(1L, "H", 12), SquadFixture.bench(1L, "HB", 12, 7),
                    away,
                    SquadFixture.startingEleven(2L, "A", 12), SquadFixture.bench(2L, "AB", 12, 7),
                    homeTactics, awayTactics, round);

            scored += result.getHomeGoals();
            conceded += result.getAwayGoals();
        }

        return new Goals(scored / (double) MATCHES, conceded / (double) MATCHES);
    }
}