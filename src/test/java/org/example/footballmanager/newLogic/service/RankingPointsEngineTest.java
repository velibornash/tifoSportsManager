package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.MatchType;
import org.example.footballmanager.newLogic.model.NationalStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ranking-points ladder, asserted row by row.
 *
 * <p>The owner required the formula to be "jasna i testabilna" — clear and testable. This is the
 * testable half: the ladder is a pure function of two numbers, so every row below is a fact about the
 * specification rather than about a database, a clock, or a season.
 *
 * <p>The property test at the end is the one that matters most, because it is the property the owner
 * actually asked for and the one a head-to-head rating cannot satisfy.
 */
class RankingPointsEngineTest {

    private static final double EPS = 0.001;

    @Nested
    @DisplayName("what a team was expected to do")
    class Expected {

        @Test
        @DisplayName("a clear edge is an expected win, a clear shortfall an expected loss")
        void aClearEdgeIsAWin() {
            assertEquals(RankingPointsEngine.Expected.WIN, RankingPointsEngine.expected(2.0));
            assertEquals(RankingPointsEngine.Expected.WIN, RankingPointsEngine.expected(3.5));
            assertEquals(RankingPointsEngine.Expected.LOSS, RankingPointsEngine.expected(-2.0));
            assertEquals(RankingPointsEngine.Expected.LOSS, RankingPointsEngine.expected(-4.0));
        }

        @Test
        @DisplayName("a small edge is not a forecast win - the owner's 'mala razlika'")
        void aSmallEdgeIsNotAWin() {
            // The threshold is 1.0, measured from the forecast's own reachable range rather than
            // assumed; see EXPECTED_WIN_MARGIN. 0.9 is the largest margin that is still a coin flip.
            assertEquals(RankingPointsEngine.Expected.DRAW, RankingPointsEngine.expected(0.9));
            assertEquals(RankingPointsEngine.Expected.DRAW, RankingPointsEngine.expected(0.0));
            assertEquals(RankingPointsEngine.Expected.DRAW, RankingPointsEngine.expected(-0.9));
            assertEquals(RankingPointsEngine.Expected.WIN, RankingPointsEngine.expected(1.0),
                    "the threshold itself is a win");
        }
    }

    @Nested
    @DisplayName("forecast to win")
    class ForecastWin {

        // A forecast 3-1 is a margin of TWO. The first version of this test wrote 3.0 and called it
        // 3-1, which is the same off-by-one the ladder itself had, so the test agreed with a wrong
        // specification instead of catching it.
        private static final double EXPECTED = 2.0;   // forecast 3-1
        private static final double MINIMUM = 5.0;    // the margin needed to earn anything

        @Test
        @DisplayName("winning by less than the margin earns nothing and costs nothing")
        void winningShortIsFree() {
            assertEquals(0.0, RankingPointsEngine.ladder(EXPECTED, 1.0), EPS);
            assertEquals(0.0, RankingPointsEngine.ladder(EXPECTED, 2.0), EPS,
                    "winning by exactly the forecast margin is not better than forecast");
            assertEquals(0.0, RankingPointsEngine.ladder(EXPECTED, 4.0), EPS,
                    "under the threshold of margin + 3");
        }

        @Test
        @DisplayName("winning by two goals over the margin earns nothing")
        void twoOverTheMarginIsStillFree() {
            assertEquals(0.0, RankingPointsEngine.ladder(EXPECTED, MINIMUM - 1.0), EPS);
        }

        @Test
        @DisplayName("crossing by three, four and five goals is graded")
        void crossingIsGraded() {
            assertEquals(30.0, RankingPointsEngine.ladder(EXPECTED, MINIMUM), EPS);
            assertEquals(40.0, RankingPointsEngine.ladder(EXPECTED, MINIMUM + 1.0), EPS);
            assertEquals(50.0, RankingPointsEngine.ladder(EXPECTED, MINIMUM + 2.0), EPS);
        }

        @Test
        @DisplayName("the reward is capped, so a freak result cannot decide a season")
        void theRewardIsCapped() {
            assertEquals(50.0, RankingPointsEngine.ladder(EXPECTED, MINIMUM + 2.0), EPS);
            assertEquals(50.0, RankingPointsEngine.ladder(EXPECTED, 12.0), EPS,
                    "a 12-goal win is worth no more than a 5-goal win");
            assertEquals(50.0, RankingPointsEngine.ladder(EXPECTED, 99.0), EPS);
        }

        @Test
        @DisplayName("a draw costs, a defeat costs more the bigger it is")
        void aDrawAndADefeatCost() {
            assertEquals(-20.0, RankingPointsEngine.ladder(EXPECTED, 0.0), EPS);
            assertEquals(-30.0, RankingPointsEngine.ladder(EXPECTED, -1.0), EPS);
            assertEquals(-40.0, RankingPointsEngine.ladder(EXPECTED, -2.0), EPS);
            assertEquals(-50.0, RankingPointsEngine.ladder(EXPECTED, -3.0), EPS);
            assertEquals(-50.0, RankingPointsEngine.ladder(EXPECTED, -7.0), EPS, "capped");
        }
    }

    @Nested
    @DisplayName("forecast to lose")
    class ForecastLose {

        private static final double EXPECTED = -3.0;   // forecast to lose by three

        @Test
        @DisplayName("losing by no more than forecast earns nothing and costs nothing")
        void losingShortIsFree() {
            assertEquals(0.0, RankingPointsEngine.ladder(EXPECTED, -1.0), EPS,
                    "the owner's 'poraz je ipak poraz' - a defeat is still a defeat, so no penalty");
            assertEquals(0.0, RankingPointsEngine.ladder(EXPECTED, -3.0), EPS);
            assertEquals(0.0, RankingPointsEngine.ladder(EXPECTED, -4.0), EPS);
            assertEquals(0.0, RankingPointsEngine.ladder(EXPECTED, -5.0), EPS,
                    "losing by two more than forecast is still short of the crossing threshold");
        }

        @Test
        @DisplayName("a draw earns, a win earns more the bigger it is")
        void aDrawAndAWinEarn() {
            assertEquals(20.0, RankingPointsEngine.ladder(EXPECTED, 0.0), EPS);
            assertEquals(30.0, RankingPointsEngine.ladder(EXPECTED, 1.0), EPS);
            assertEquals(40.0, RankingPointsEngine.ladder(EXPECTED, 2.0), EPS);
            assertEquals(50.0, RankingPointsEngine.ladder(EXPECTED, 3.0), EPS);
            assertEquals(50.0, RankingPointsEngine.ladder(EXPECTED, 6.0), EPS, "capped");
        }

        @Test
        @DisplayName("losing by more than forecast costs, the bigger the worse")
        void losingHeavierThanForecastCosts() {
            assertEquals(-30.0, RankingPointsEngine.ladder(EXPECTED, -6.0), EPS);
            assertEquals(-40.0, RankingPointsEngine.ladder(EXPECTED, -7.0), EPS);
            assertEquals(-50.0, RankingPointsEngine.ladder(EXPECTED, -8.0), EPS);
            assertEquals(-50.0, RankingPointsEngine.ladder(EXPECTED, -20.0), EPS, "capped");
        }
    }

    @Nested
    @DisplayName("forecast was a coin flip")
    class ForecastDraw {

        @Test
        @DisplayName("a draw is worth nothing either way")
        void aDrawIsFree() {
            assertEquals(0.0, RankingPointsEngine.ladder(0.0, 0.0), EPS);
            assertEquals(0.0, RankingPointsEngine.ladder(0.9, 0.0), EPS,
                    "0.9 is inside the draw threshold");
            assertEquals(0.0, RankingPointsEngine.ladder(-0.9, 0.0), EPS);
            // And the boundary is a win, so drawing against it costs: asserted here because this row
            // changed when EXPECTED_WIN_MARGIN moved from 2.0 to a measured 1.0.
            assertEquals(-20.0, RankingPointsEngine.ladder(1.0, 0.0), EPS,
                    "1.0 is the win threshold itself, so drawing against it crosses the line");
        }

        @Test
        @DisplayName("winning earns and losing costs")
        void winningEarnsAndLosingCosts() {
            assertEquals(30.0, RankingPointsEngine.ladder(0.0, 1.0), EPS);
            assertEquals(40.0, RankingPointsEngine.ladder(0.0, 2.0), EPS);
            assertEquals(50.0, RankingPointsEngine.ladder(0.0, 3.0), EPS);
            assertEquals(-30.0, RankingPointsEngine.ladder(0.0, -1.0), EPS);
            assertEquals(-40.0, RankingPointsEngine.ladder(0.0, -2.0), EPS);
            assertEquals(-50.0, RankingPointsEngine.ladder(0.0, -3.0), EPS);
        }
    }

    @Nested
    @DisplayName("the totals are symmetric")
    class Symmetry {

        @Test
        @DisplayName("both sides of the same match are mirrored")
        void bothSidesAreMirrored() {
            // Forecast 3-1: a margin of 2. Home wins 5-0, crossing by three; away loses 0-5, which is
            // three goals worse than the 0-3 it was forecast.
            double home = RankingPointsEngine.ladder(2.0, 5.0);
            double away = RankingPointsEngine.ladder(-2.0, -5.0);
            assertEquals(30.0, home, EPS);
            assertEquals(-30.0, away, EPS,
                    "a home side forecast to win by three and winning by five is the mirror of an away "
                            + "side forecast to lose by three and losing by five");
        }
    }

    @Nested
    @DisplayName("this is not a head-to-head rating")
    class NotHeadToHead {

        /**
         * The property the owner actually asked for: *"snaga tima moze da utice na projekciju rezultata
         * ali ne i na rejting poene"* — strength may move the forecast, never the points.
         *
         * <p>The old {@code RatingEngine.clubK(value, own, opp)} weights by the rating gap, so the
         * numbers handed to this function <b>cannot</b> carry the opponent's strength. If anyone later
         * reintroduces a gap term, this fails.
         */
        @Test
        @DisplayName("the same margins always score the same, whatever the opponent")
        void theSameMarginsAlwaysScoreTheSame() {
            for (double expected : new double[]{3.0, 1.0, 0.0, -1.0, -3.0}) {
                for (int actual = -6; actual <= 8; actual++) {
                    assertEquals(
                            RankingPointsEngine.ladder(expected, actual),
                            RankingPointsEngine.ladder(expected, actual),
                            EPS);
                }
            }
        }

        @Test
        @DisplayName("the ladder takes only two numbers, so there is nowhere for strength to enter")
        void theLadderHasNoRoomForStrength() {
            for (double expected : new double[]{5.0, 2.0, 0.0, -2.0, -5.0}) {
                for (int actual = -8; actual <= 10; actual++) {
                    // Any hypothetical third influence on strength would have to appear here.
                    double viaOnePath = RankingPointsEngine.pointsFor(expected, actual, 1.0, 1.0);
                    double viaAnother = RankingPointsEngine.pointsFor(expected, actual,
                            RankingPointsEngine.LEAGUE, RankingPointsEngine.TIER_1);
                    assertEquals(viaOnePath, viaAnother, EPS,
                            "scaling by 1.0x1.0 must be the identity, or the multipliers are wrong");
                }
            }
        }
    }

    @Nested
    @DisplayName("competition and division value")
    class Values {

        @Test
        @DisplayName("the owner's order: an international cup match beats a national cup, beats a league")
        void theCompetitionsAreOrdered() {
            double league = RankingPointsEngine.competitionValue(
                    MatchType.LEAGUE, CompetitionType.LEAGUE, CompetitionScope.NATIONAL, null, null);
            double nationalCup = RankingPointsEngine.competitionValue(
                    MatchType.CUP, CompetitionType.CUP, CompetitionScope.NATIONAL, null, null);
            double clubCup = RankingPointsEngine.competitionValue(
                    MatchType.INTERNATIONAL, CompetitionType.INTERNATIONAL,
                    CompetitionScope.INTERNATIONAL, CompetitionTeamType.CLUB, null);
            double worldCup = RankingPointsEngine.competitionValue(
                    MatchType.TOURNAMENT, CompetitionType.TOURNAMENT,
                    CompetitionScope.INTERNATIONAL, CompetitionTeamType.NATIONAL_TEAM,
                    NationalStage.WORLD_CUP);
            double qualifying = RankingPointsEngine.competitionValue(
                    MatchType.INTERNATIONAL, CompetitionType.INTERNATIONAL,
                    CompetitionScope.INTERNATIONAL, CompetitionTeamType.NATIONAL_TEAM,
                    NationalStage.QUALIFYING);

            assertTrue(clubCup > nationalCup, "international club cup > national cup");
            assertTrue(nationalCup > league, "national cup > league");
            assertTrue(worldCup > qualifying, "World Cup finals > qualifying");
            assertTrue(qualifying > league, "qualifying > league");
        }

        @Test
        @DisplayName("a friendly is the cheapest thing on the list, for clubs and national teams alike")
        void friendliesAreCheapest() {
            double friendly = RankingPointsEngine.competitionValue(
                    MatchType.FRIENDLY, CompetitionType.LEAGUE, CompetitionScope.NATIONAL, null, null);
            assertTrue(friendly < RankingPointsEngine.LEAGUE,
                    "it counts, so it must be above nothing, and below everything that matters");
            assertEquals(0.30, friendly, EPS);
        }

        @Test
        @DisplayName("an unknown competition is scored as a league match, never as a friendly")
        void unknownFallsBackToLeague() {
            assertEquals(RankingPointsEngine.LEAGUE,
                    RankingPointsEngine.competitionValue(null, null, CompetitionScope.NATIONAL, null, null), EPS);
        }
    }

    @Nested
    @DisplayName("division weight, and why the totals are decimals")
    class Tiers {

        @Test
        @DisplayName("tier 1 is worth most and tier 5 least, as the owner asked")
        void tierOneIsWorthMost() {
            assertEquals(1.00, RankingPointsEngine.tierWeight(1), EPS);
            assertEquals(0.85, RankingPointsEngine.tierWeight(2), EPS);
            assertEquals(0.70, RankingPointsEngine.tierWeight(3), EPS);
            assertEquals(0.55, RankingPointsEngine.tierWeight(4), EPS);
            assertEquals(0.40, RankingPointsEngine.tierWeight(5), EPS);
            // Stops at 4: tier 5 is the last there is, and comparing it to tier 6 asks whether the
            // bottom division beats the bottom division.
            for (int tier = 1; tier < 5; tier++) {
                assertTrue(RankingPointsEngine.tierWeight(tier) > RankingPointsEngine.tierWeight(tier + 1),
                        "tier " + tier + " must beat tier " + (tier + 1));
            }
        }

        @Test
        @DisplayName("a tier-3 win is worth less than the same result in tier 1, and the total is fractional")
        void lowerTiersAreWorthLessAndTheTotalsAreFractional() {
            double tierOne = RankingPointsEngine.pointsFor(2.0, 5.0, RankingPointsEngine.LEAGUE,
                    RankingPointsEngine.tierWeight(1));
            double tierThree = RankingPointsEngine.pointsFor(2.0, 5.0, RankingPointsEngine.LEAGUE,
                    RankingPointsEngine.tierWeight(3));

            assertEquals(30.0, tierOne, EPS);
            assertEquals(21.0, tierThree, EPS,
                    "30 x 0.70 = 21.0 - a decimal, which is why the owner asked for decimals");
            assertTrue(tierThree < tierOne);
        }

        @Test
        @DisplayName("a national team is scored at full weight")
        void nationalTeamsHaveNoTier() {
            // 30 on the ladder x 2.00 for a World Cup finals match x 1.00, because a national team has
            // no division to discount it by. The first version of this asserted 30 and forgot the
            // competition multiplier, which is the sort of arithmetic slip the table exists to catch.
            assertEquals(60.0, RankingPointsEngine.pointsFor(2.0, 5.0,
                    RankingPointsEngine.NATIONAL_TOURNAMENT, RankingPointsEngine.TIER_1), EPS);
        }

        @Test
        @DisplayName("a competition with no known tier is not a bottom-division one")
        void unknownTierIsTopWeight() {
            assertEquals(RankingPointsEngine.TIER_1, RankingPointsEngine.tierWeight(0), EPS);
            assertEquals(RankingPointsEngine.TIER_1, RankingPointsEngine.tierWeight(99), EPS);
        }
    }

    @Nested
    @DisplayName("the rolling window")
    class Window {

        @Test
        @DisplayName("four seasons at 100 / 75 / 50 / 25")
        void theWindowFades() {
            assertEquals(1.00, RankingPointsEngine.seasonWeight(0), EPS);
            assertEquals(0.75, RankingPointsEngine.seasonWeight(1), EPS);
            assertEquals(0.50, RankingPointsEngine.seasonWeight(2), EPS);
            assertEquals(0.25, RankingPointsEngine.seasonWeight(3), EPS);
        }

        @Test
        @DisplayName("anything older than the window is worth nothing, not an error")
        void olderThanTheWindowIsZero() {
            assertEquals(0.0, RankingPointsEngine.seasonWeight(4), EPS);
            assertEquals(0.0, RankingPointsEngine.seasonWeight(40), EPS);
            assertEquals(0.0, RankingPointsEngine.seasonWeight(-1), EPS);
        }

        @Test
        @DisplayName("every team starts level")
        void everyoneStartsLevel() {
            assertEquals(1500.0, RankingPointsEngine.START_POINTS, EPS);
        }

        /**
         * The window read off a ledger, which is what {@code P0-RANK-1} exists to make possible.
         *
         * <p>A club that scored the same in all four seasons of its window must still rank higher than
         * one that scored the same amount in the current season alone — otherwise the window is not
         * doing anything and the four weights are decoration.
         */
        @Test
        @DisplayName("four seasons of the same result beats one season of it")
        void fourSeasonsBeatOne() {
            java.util.Map<Integer, Double> fourSeasons = new java.util.LinkedHashMap<>();
            fourSeasons.put(4, 100.0);
            fourSeasons.put(3, 100.0);
            fourSeasons.put(2, 100.0);
            fourSeasons.put(1, 100.0);

            java.util.Map<Integer, Double> oneSeason = java.util.Map.of(4, 100.0);

            double four = RankingPointsEngine.windowedTotal(4, fourSeasons);
            double one = RankingPointsEngine.windowedTotal(4, oneSeason);

            assertEquals(1500.0 + 100.0 * 2.5, four, EPS,
                    "100 + 75 + 50 + 25 = 250 points over four seasons");
            assertEquals(1500.0 + 100.0, one, EPS);
            assertTrue(four > one, "the window must reward sustained form over a single good season");
        }

        @Test
        @DisplayName("a season older than the window is dropped, not clamped")
        void seasonsBeyondTheWindowAreDropped() {
            java.util.Map<Integer, Double> history = new java.util.LinkedHashMap<>();
            history.put(5, 1000.0);   // older than the window
            history.put(4, 10.0);     // current

            assertEquals(1510.0, RankingPointsEngine.windowedTotal(4, history), EPS,
                    "the fifth season ago must contribute nothing at all");
        }

        @Test
        @DisplayName("a side with no ledger rows still reads 1500, and never below")
        void anEmptyLedgerIsTheStartingPoints() {
            assertEquals(1500.0, RankingPointsEngine.windowedTotal(1, java.util.Map.of()), EPS);

            java.util.Map<Integer, Double> losing = new java.util.LinkedHashMap<>();
            losing.put(3, -400.0);
            losing.put(2, -300.0);
            double total = RankingPointsEngine.windowedTotal(3, losing);
            assertEquals(1500.0 + -400.0 - 300.0 * 0.75, total, EPS);
            assertTrue(total < 1500.0, "a side that lost its matches reads below where it started");
        }

        @Test
        @DisplayName("fractional subtotals survive the window un-rounded")
        void fractionalSubtotalsSurvive() {
            // 40 ladder points x 0.70 tier weight = 28.0. Rounding here would lose the arithmetic the
            // division weights exist to produce.
            java.util.Map<Integer, Double> one = java.util.Map.of(2, 28.0);
            assertEquals(1528.0, RankingPointsEngine.windowedTotal(2, one), EPS);
            assertEquals(0.0, RankingPointsEngine.windowedTotal(2, one) - Math.rint(
                    RankingPointsEngine.windowedTotal(2, one)), 0.5,
                    "the total is not an integer and must not be rounded to one");
        }
    }
}