package org.example.footballmanager.newLogic.fantasy;

import org.example.footballmanager.newLogic.model.Position;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fantasy price scale is the owner's, and the budget on top of it does something.
 *
 * <p>Two claims, and the second is the one worth testing. The first is that the table is the twenty
 * numbers the owner specified, in the order he specified them. The second is that a budget of 135.0
 * actually constrains a squad — because the first version of this scale had a floor of 111.0 against a
 * budget of 100.0, which meant <b>no legal team could be built at all</b>, and nothing in the code would
 * have said so until a manager tried it.
 *
 * <p>So the legal range is brute-forced here: every arrangement of fifteen players across five tiers that
 * satisfies 2/5/5/3 and exactly three per tier, priced at the cheapest and at the dearest it can be.
 */
class FantasyPricingTest {

    /** The four positions fantasy knows. WNG is not one of them; it is a midfielder. */
    private static final Position[] FANTASY = {Position.GK, Position.DEF, Position.MID, Position.ATT};

    @Test
    @DisplayName("the base table is the owner's scale, to the half point")
    void theBaseTableIsTheOwnersScale() {
        // Written out as a literal, row by row, in the owner's own order. If the code and this disagree,
        // the code is wrong.
        assertEquals(13.0, FantasyPricing.basePrice(1, Position.ATT), "tier 1 attacker");
        assertEquals(11.5, FantasyPricing.basePrice(1, Position.DEF), "tier 1 defender");
        assertEquals(10.5, FantasyPricing.basePrice(1, Position.GK), "tier 1 goalkeeper");
        assertEquals(10.0, FantasyPricing.basePrice(1, Position.MID), "tier 1 midfielder");

        assertEquals(10.5, FantasyPricing.basePrice(2, Position.ATT), "tier 2 attacker");
        assertEquals(9.0, FantasyPricing.basePrice(2, Position.DEF), "tier 2 defender");
        assertEquals(8.5, FantasyPricing.basePrice(2, Position.GK), "tier 2 goalkeeper");
        assertEquals(8.0, FantasyPricing.basePrice(2, Position.MID), "tier 2 midfielder");

        assertEquals(8.5, FantasyPricing.basePrice(3, Position.ATT), "tier 3 attacker");
        assertEquals(7.5, FantasyPricing.basePrice(3, Position.DEF), "tier 3 defender");
        assertEquals(7.0, FantasyPricing.basePrice(3, Position.GK), "tier 3 goalkeeper");
        assertEquals(6.5, FantasyPricing.basePrice(3, Position.MID), "tier 3 midfielder");

        assertEquals(6.5, FantasyPricing.basePrice(4, Position.ATT), "tier 4 attacker");
        assertEquals(6.0, FantasyPricing.basePrice(4, Position.DEF), "tier 4 defender");
        assertEquals(5.5, FantasyPricing.basePrice(4, Position.GK), "tier 4 goalkeeper");
        assertEquals(5.0, FantasyPricing.basePrice(4, Position.MID), "tier 4 midfielder");

        assertEquals(5.5, FantasyPricing.basePrice(5, Position.ATT), "tier 5 attacker");
        assertEquals(5.0, FantasyPricing.basePrice(5, Position.DEF), "tier 5 defender");
        assertEquals(4.5, FantasyPricing.basePrice(5, Position.GK), "tier 5 goalkeeper");
        assertEquals(4.0, FantasyPricing.basePrice(5, Position.MID), "tier 5 midfielder");
    }

    @Test
    @DisplayName("both of the owner's ordering rules hold at the base, with no exception")
    void theBaseOrderingHolds() {
        for (Position p : FANTASY) {
            for (int tier = 1; tier < FantasyRules.TIERS_REQUIRED; tier++) {
                assertTrue(FantasyPricing.basePrice(tier, p) > FantasyPricing.basePrice(tier + 1, p),
                        "tier " + tier + " " + p + " must cost more than tier " + (tier + 1)
                                + " — the owner's rule is that a higher tier is always dearer");
            }
        }
        for (int tier = 1; tier <= FantasyRules.TIERS_REQUIRED; tier++) {
            double att = FantasyPricing.basePrice(tier, Position.ATT);
            double def = FantasyPricing.basePrice(tier, Position.DEF);
            double gk = FantasyPricing.basePrice(tier, Position.GK);
            double mid = FantasyPricing.basePrice(tier, Position.MID);
            assertTrue(att > def && def > gk && gk > mid,
                    "tier " + tier + " must run ATT > DEF > GK > MID, was "
                            + att + " / " + def + " / " + gk + " / " + mid);
        }
    }

    @Test
    @DisplayName("a winger is priced as a midfielder, because he is one")
    void wingersAreMidfielders() {
        for (int tier = 1; tier <= FantasyRules.TIERS_REQUIRED; tier++) {
            assertEquals(FantasyPricing.basePrice(tier, Position.MID),
                    FantasyPricing.basePrice(tier, Position.WNG),
                    "tier " + tier + ": WNG must be priced as MID");
        }
        assertEquals(Position.MID, FantasyRules.fantasyPositionOf(Position.WNG));
        assertEquals(5, FantasyRules.squadSlots(Position.WNG), "a winger fills a midfield squad slot");
    }

    @Test
    @DisplayName("the nudge runs from the full premium at the top of a band to nothing at the bottom")
    void theNudgeRunsFromBestToWorst() {
        assertEquals(3.0, FantasyPricing.nudge(0, 100), "the best player in a band gets the full premium");
        assertEquals(0.0, FantasyPricing.nudge(99, 100), "the worst gets none");
        assertEquals(0.0, FantasyPricing.nudge(0, 1), "a band of one has no rank to be better than");

        double previous = Double.MAX_VALUE;
        for (int rank = 0; rank < 40; rank++) {
            double nudge = FantasyPricing.nudge(rank, 40);
            assertTrue(nudge <= previous, "the nudge must never rise as a player gets worse, at rank " + rank);
            previous = nudge;
        }
        // Out-of-range ranks are clamped rather than throwing: a caller that has lost count must not be
        // able to hand out a negative price or a premium above the maximum.
        assertEquals(3.0, FantasyPricing.nudge(-5, 40));
        assertEquals(0.0, FantasyPricing.nudge(999, 40));
    }

    @Test
    @DisplayName("a tier with no fantasy price is refused, not priced at zero")
    void anUnknownTierIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> FantasyPricing.basePrice(0, Position.MID));
        assertThrows(IllegalArgumentException.class, () -> FantasyPricing.basePrice(6, Position.MID));
    }

    @Test
    @DisplayName("the budget constrains: the cheapest legal fifteen fits, the dearest does not")
    void theBudgetBinds() {
        double floor = cheapestLegalSquad();
        double ceiling = dearestLegalSquad();

        assertEquals(111.0, floor,
                "the cheapest legal fifteen is the number the budget had to be set against");
        assertEquals(FantasyPricing.CHEAPEST_LEGAL_SQUAD, floor,
                "the published floor must be the measured one, or the budget guard guards nothing");
        assertEquals(160.5, ceiling, "the dearest legal fifteen, every player at the top of his band");

        assertTrue(FantasyPricing.BUDGET > floor,
                "a budget at or below the floor makes a legal team impossible — this is the defect the "
                        + "first scale had, and the reason the budget is 135 and not 100");
        assertTrue(FantasyPricing.BUDGET < ceiling,
                "a budget at or above the ceiling is decoration: every squad is affordable and there is no "
                        + "trade to make. budget=" + FantasyPricing.BUDGET + " ceiling=" + ceiling);

        // How much of the available upgrade the budget actually buys. Asserted as a band rather than an
        // exact number so the scale can be tuned without rewriting the test, but narrow enough that a
        // budget which stopped constraining would fail here.
        // A band rather than an exact figure, so the budget can be tuned without rewriting the test. It is
        // here to catch a budget that has stopped constraining — not to pin a ratio. The band is wide
        // because the budget is a product decision: 135.0 bought 48% and the owner raised it to 145.0 for
        // 69%, and both are legitimate answers to "how much should a manager be able to afford".
        double buys = (FantasyPricing.BUDGET - floor) / (ceiling - floor);
        assertTrue(buys > 0.25 && buys < 0.85,
                "the budget should buy a substantial share of the available upgrades and not all of them; "
                        + "it buys " + Math.round(buys * 100) + "%");
    }

    @Test
    @DisplayName("the documented consequence is real: the nudge crosses a tier for the same position")
    void theNudgeCrossesATierAtTheExtremes() {
        // The javadoc claims this. A claim in a comment that nothing checks is how a comment becomes a
        // lie, so it is asserted here. Same position, adjacent tiers: the base is ordered, the nudge is
        // big enough to reverse it.
        double bestFourthDivisionAttacker = FantasyPricing.price(4, Position.ATT, 0, 100);
        double worstThirdDivisionAttacker = FantasyPricing.price(3, Position.ATT, 99, 100);
        assertTrue(bestFourthDivisionAttacker > worstThirdDivisionAttacker,
                "a good lower-league attacker must be able to cost more than a poor higher-league one — that "
                        + "mix is the point of the budget; " + bestFourthDivisionAttacker + " vs "
                        + worstThirdDivisionAttacker);

        double bestFifthDivisionAttacker = FantasyPricing.price(5, Position.ATT, 0, 100);
        double worstFourthDivisionAttacker = FantasyPricing.price(4, Position.ATT, 99, 100);
        assertTrue(bestFifthDivisionAttacker > worstFourthDivisionAttacker,
                "and again at the bottom of the pyramid: " + bestFifthDivisionAttacker + " vs "
                        + worstFourthDivisionAttacker);

        // And the base does not cross, for the same position. That is the owner's rule, and only the
        // nudge is allowed to break it.
        assertTrue(FantasyPricing.basePrice(3, Position.ATT) > FantasyPricing.basePrice(4, Position.ATT));
        assertTrue(FantasyPricing.basePrice(4, Position.ATT) > FantasyPricing.basePrice(5, Position.ATT));
    }

    @Test
    @DisplayName("no player can cost more than the published maximum")
    void theMaximumIsTheMaximum() {
        assertEquals(16.0, FantasyPricing.maximumPossiblePrice());
        for (int tier = 1; tier <= FantasyRules.TIERS_REQUIRED; tier++) {
            for (Position p : Position.values()) {
                assertTrue(FantasyPricing.price(tier, p, 0, 500) <= FantasyPricing.maximumPossiblePrice(),
                        "tier " + tier + " " + p + " exceeded the published maximum");
            }
        }
    }

    // ---------------------------------------------------------------- the brute force

    /** Every way a tier can hold three players across the four fantasy positions. */
    private static List<int[]> arrangements() {
        List<int[]> out = new ArrayList<>();
        for (int g = 0; g <= 3; g++) {
            for (int d = 0; d <= 3; d++) {
                for (int m = 0; m <= 3; m++) {
                    for (int a = 0; a <= 3; a++) {
                        if (g + d + m + a == FantasyRules.PER_TIER) {
                            out.add(new int[]{g, d, m, a});
                        }
                    }
                }
            }
        }
        return out;
    }

    /** Priced at the bottom of every band: the cheapest a legal fifteen can be. */
    private static double cheapestLegalSquad() {
        return extreme(false);
    }

    /** Priced at the top of every band: the dearest a legal fifteen can be. */
    private static double dearestLegalSquad() {
        return extreme(true);
    }

    private static double extreme(boolean dearest) {
        List<int[]> perTier = arrangements();
        double best = dearest ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        for (int[] t1 : perTier) {
            for (int[] t2 : perTier) {
                for (int[] t3 : perTier) {
                    for (int[] t4 : perTier) {
                        for (int[] t5 : perTier) {
                            int[][] tiers = {t1, t2, t3, t4, t5};
                            int[] totals = new int[4];
                            for (int[] tier : tiers) {
                                for (int i = 0; i < 4; i++) {
                                    totals[i] += tier[i];
                                }
                            }
                            if (totals[0] != FantasyRules.squadSlots(Position.GK)
                                    || totals[1] != FantasyRules.squadSlots(Position.DEF)
                                    || totals[2] != FantasyRules.squadSlots(Position.MID)
                                    || totals[3] != FantasyRules.squadSlots(Position.ATT)) {
                                continue;
                            }
                            double cost = 0.0;
                            for (int tierIndex = 0; tierIndex < tiers.length; tierIndex++) {
                                for (int p = 0; p < 4; p++) {
                                    double unit = dearest
                                            ? FantasyPricing.price(tierIndex + 1, FANTASY[p], 0, 100)
                                            : FantasyPricing.price(tierIndex + 1, FANTASY[p], 99, 100);
                                    cost += unit * tiers[tierIndex][p];
                                }
                            }
                            best = dearest ? Math.max(best, cost) : Math.min(best, cost);
                        }
                    }
                }
            }
        }
        return best;
    }
}
