package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.MatchValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rating arithmetic, tested as properties rather than as examples.
 *
 * <p>Example-based tests would have passed for almost any implementation — including a broken one —
 * because they only prove the code does what it did yesterday. The invariants below are the ones a
 * wrong implementation violates: <b>a win never lowers a rating, a loss never raises one, a match is
 * zero-sum, and a bigger prize is worth more.</b>
 *
 * <p>Each of those corresponds to something the owner actually asked for, so a regression here is a
 * regression in the specification rather than in the code.
 */
class RatingEngineTest {

    private static final double TOLERANCE = 1e-9;

    @Nested
    @DisplayName("the properties that must hold for every input")
    class Invariants {

        @Test
        @DisplayName("winning never lowers a rating, and losing never raises one")
        void directionIsAlwaysCorrect() {
            for (int own = 1200; own <= 1800; own += 25) {
                for (int opp = 1200; opp <= 1800; opp += 25) {
                    for (double k : new double[]{8, 16, 32, 64}) {
                        assertTrue(RatingEngine.delta(own, opp, 1.0, k) > 0,
                                "a win lowered the rating: " + own + " vs " + opp + " k=" + k);
                        assertTrue(RatingEngine.delta(own, opp, 0.0, k) < 0,
                                "a loss raised the rating: " + own + " vs " + opp + " k=" + k);
                    }
                }
            }
        }

        @Test
        @DisplayName("a match is zero-sum - what one side gains the other loses")
        void aMatchIsZeroSum() {
            // Both sides must move by the same amount in opposite directions, or a rating system
            // quietly creates or destroys rating out of nothing.
            for (int own = 1200; own <= 1800; own += 50) {
                for (int opp = 1200; opp <= 1800; opp += 50) {
                    for (double actual : new double[]{0.0, 0.5, 1.0}) {
                        double up = RatingEngine.delta(own, opp, actual, 32.0);
                        double down = RatingEngine.delta(opp, own, 1.0 - actual, 32.0);
                        assertEquals(-up, down, TOLERANCE,
                                "match is not zero-sum: " + own + " v " + opp + " actual=" + actual);
                    }
                }
            }
        }

        @Test
        @DisplayName("a draw between equals changes nothing, and an unequal draw moves the underdog up")
        void drawsFollowExpectation() {
            assertEquals(0.0, RatingEngine.delta(1500, 1500, 0.5, 32.0), TOLERANCE,
                    "two equal sides drawing should not move either");

            double underdog = RatingEngine.delta(1400, 1600, 0.5, 32.0);
            assertTrue(underdog > 0, "drawing against a stronger side should raise the underdog");
            assertTrue(RatingEngine.delta(1600, 1400, 0.5, 32.0) < 0,
                    "and correspondingly lower the favourite");
        }

        @Test
        @DisplayName("an upset moves more than an expected result")
        void upsetsAreWorthMore() {
            // The owner's core requirement: beating a higher-rated side is worth more, and losing to
            // a lower-rated one costs more. If these were equal, the rating would carry no information
            // about which side was the underdog.
            double beatStronger = RatingEngine.delta(1400, 1600, 1.0, 32.0);
            double beatWeaker = RatingEngine.delta(1600, 1400, 1.0, 32.0);
            assertTrue(beatStronger > beatWeaker,
                    "beating a stronger side (" + beatStronger + ") must beat beating a weaker one ("
                            + beatWeaker + ")");

            double loseToWeaker = RatingEngine.delta(1400, 1300, 0.0, 32.0);
            double loseToStronger = RatingEngine.delta(1400, 1500, 0.0, 32.0);
            assertTrue(loseToWeaker < loseToStronger,
                    "losing to a weaker side must cost more than losing to a stronger one");
        }
    }

    @Nested
    @DisplayName("match value - international cup > league > cup > friendly")
    class MatchValueOrdering {

        @Test
        @DisplayName("the owner's order holds, in the order they gave it")
        void valueOrdering() {
            double international = RatingEngine.clubK(MatchValue.INTERNATIONAL);
            double league = RatingEngine.clubK(MatchValue.LEAGUE);
            double cup = RatingEngine.clubK(MatchValue.CUP);
            double friendly = RatingEngine.clubK(MatchValue.FRIENDLY);

            assertTrue(international > league, "international cup must beat league");
            assertTrue(league > cup, "league must beat cup");
            assertTrue(cup > friendly, "cup must beat friendly");
        }

        @Test
        @DisplayName("a friendly still moves the rating, just less")
        void friendliesAreNotFree() {
            // A manager fielding reserves against a European qualifier got a real result. Zeroing it
            // would make friendlies free wins; weighting it equal to a league match would let a
            // fixture pile-up outvote a season.
            double friendly = RatingEngine.delta(1500, 1500, 1.0, RatingEngine.clubK(MatchValue.FRIENDLY));
            double league = RatingEngine.delta(1500, 1500, 1.0, RatingEngine.clubK(MatchValue.LEAGUE));
            assertTrue(friendly > 0, "a friendly win should still count for something");
            assertTrue(friendly < league, "but for less than a league match");
        }
    }

    @Nested
    @DisplayName("national teams")
    class National {

        @Test
        @DisplayName("a World Cup match outweighs a qualifier, which outweighs a friendly")
        void stageWeighting() {
            double worldCup = RatingEngine.nationalK(MatchValue.INTERNATIONAL, NationalStage.WORLD_CUP);
            double qualifying = RatingEngine.nationalK(MatchValue.INTERNATIONAL, NationalStage.QUALIFYING);
            double other = RatingEngine.nationalK(MatchValue.INTERNATIONAL, NationalStage.OTHER);

            assertTrue(worldCup > qualifying, "a World Cup match must outweigh a qualifier");
            assertTrue(qualifying > other, "a qualifier must outweigh a friendly");
        }

        @Test
        @DisplayName("qualifying carries its own bonus, separate from the value of a match")
        void qualificationBonusIsSeparate() {
            // The owner asked for two separate things: knockout matches are worth more, AND there is
            // a bonus for reaching the tournament. Collapsing them would make a nation that grinds
            // through qualifying and narrowly goes out look identical to one that never got there.
            assertTrue(RatingEngine.qualificationBonus() > 0);
            assertTrue(RatingEngine.qualificationBonus()
                            < RatingEngine.nationalK(MatchValue.INTERNATIONAL, NationalStage.WORLD_CUP),
                    "a bonus for the whole campaign should not outweigh a single World Cup match ("
                            + RatingEngine.qualificationBonus() + " vs "
                            + RatingEngine.nationalK(MatchValue.INTERNATIONAL, NationalStage.WORLD_CUP) + ")");
        }

        @Test
        @DisplayName("every nation starts level")
        void allNationsStartLevel() {
            assertEquals(RatingEngine.NATIONAL_START_RATING, 1500.0, TOLERANCE,
                    "48 nations starting at different ratings would seed the pots on nothing");
        }

        @Test
        @DisplayName("an unclassified stage is weighted lowest, not highest")
        void unknownStageIsSafest() {
            double unknown = RatingEngine.nationalK(MatchValue.INTERNATIONAL, null);
            double friendly = RatingEngine.nationalK(MatchValue.INTERNATIONAL, NationalStage.OTHER);
            assertEquals(friendly, unknown, TOLERANCE,
                    "a null stage must fall back to OTHER, not to the heaviest weight");
        }
    }

    @Nested
    @DisplayName("club starting ratings")
    class ClubStarts {

        @Test
        @DisplayName("a higher tier always starts ranked above a lower one")
        void higherTierStartsHigher() {
            double tier1 = RatingEngine.clubStartRating(1, 5);
            double tier3 = RatingEngine.clubStartRating(3, 5);
            double tier5 = RatingEngine.clubStartRating(5, 5);
            assertTrue(tier1 > tier3, "tier 1 must start above tier 3");
            assertTrue(tier3 > tier5, "tier 3 must start above tier 5");
        }

        @Test
        @DisplayName("the gap is a real gap, not a rounding artefact")
        void tierGapIsMeaningful() {
            double gap = RatingEngine.clubStartRating(1, 5) - RatingEngine.clubStartRating(2, 5);
            assertTrue(gap >= 150, "a one-tier step should be worth about two-to-one, was " + gap);

            // Which means the favourite really is the favourite: the two sides' expectations are
            // complements, so they must sum to 1 - the favourite expects ~0.67 of a 2:1 shot.
            assertEquals(1.0, RatingEngine.expected(1500, 1500 + gap)
                    + RatingEngine.expected(1500 + gap, 1500), TOLERANCE,
                    "a side's expectation and its opponent's must sum to one");
            // The stronger side is the one rated 1500+gap; its expectation is the second term.
            assertTrue(RatingEngine.expected(1500 + gap, 1500) > 0.6,
                    "200 points should make the stronger side a clear favourite, was "
                            + RatingEngine.expected(1500 + gap, 1500));
        }

        @Test
        @DisplayName("a nonsense tier does not produce a nonsense rating")
        void tiersAreClamped() {
            assertTrue(RatingEngine.clubStartRating(0, 5) > 0, "tier 0 should clamp to tier 1");
            assertTrue(RatingEngine.clubStartRating(-3, 5) > 0, "a negative tier should clamp, not explode");
            // highestTier below tier must not throw or return something absurd
            assertTrue(RatingEngine.clubStartRating(9, 3) > 0);
        }
    }
}
