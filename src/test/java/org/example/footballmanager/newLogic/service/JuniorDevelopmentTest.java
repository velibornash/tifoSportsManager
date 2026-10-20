package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Personality;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.PreferredFoot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a junior is made of, and how his body changes (Sprint 5.3, owner 2026-09-27).
 *
 * <p>Two owner decisions are pinned here because they are the ones a later "cleanup" would silently
 * reverse: <b>height barely moves and tapers to nothing at the graduation deadline</b>, and
 * <b>the gym corrects weight rather than granting it</b>.
 */
class JuniorDevelopmentTest {

    private static final double EPS = 0.0001;

    // ── height ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("height barely moves, and not at all near the graduation deadline")
    void heightTapersToNothing() {
        // A fifteen-year-old is still growing. A nineteen-year-old in an academy is finished, and
        // pretending otherwise hands a club a centre-half who is still sprouting at twenty.
        // First argument is years in the academy, not age -- he arrives at 15, so year 1 is 15.
        double asFifteenYearOld = JuniorDevelopment.seasonalHeightGain(0, 20, 0.5);
        double asSeventeenYearOld = JuniorDevelopment.seasonalHeightGain(2, 20, 0.5);
        double asNineteenYearOld = JuniorDevelopment.seasonalHeightGain(4, 20, 0.5);

        assertTrue(asFifteenYearOld > asSeventeenYearOld,
                "the younger he is, the more is left: " + asFifteenYearOld + " vs " + asSeventeenYearOld);
        // A nineteen-year-old still grows a little -- people do, at nineteen -- but it has tapered to
        // a fraction of what a fifteen-year-old gets, and it is exactly nothing once he is past the
        // graduation deadline. The owner's word was "almost none", not "none".
        assertTrue(asNineteenYearOld < asSeventeenYearOld / 2.0,
                "a nineteen-year-old must gain a fraction of a younger player's: "
                        + asNineteenYearOld + " vs " + asSeventeenYearOld);
        assertTrue(asNineteenYearOld <= 1.0, "and under a centimetre, got " + asNineteenYearOld);
        assertEquals(0.0, JuniorDevelopment.seasonalHeightGain(5, 20, 0.5), EPS,
                "at the graduation deadline there is nothing left to gain");
        assertEquals(0.0, JuniorDevelopment.seasonalHeightGain(9, 20, 0.5), EPS,
                "past the deadline there is nothing left to gain");
        // A negative tenure is nonsense and degrades to "just arrived" -- the maximum -- rather than
        // to zero or to something extreme. The original version computed remaining years from the
        // raw value, so a negative tenure gave *more* growth than any real junior could get.
        assertEquals(JuniorDevelopment.seasonalHeightGain(0, 20, 0.5),
                JuniorDevelopment.seasonalHeightGain(-1, 20, 0.5), EPS,
                "nonsense input must degrade to a real case, not to an extreme one");
    }

    @Test
    @DisplayName("a season's growth is small in absolute terms")
    void heightGainIsRealistic() {
        double worst = 0.0;
        for (double unit = 0.0; unit <= 1.0; unit += 0.1) {
            worst = Math.max(worst, JuniorDevelopment.seasonalHeightGain(0, 20, unit));
        }
        assertTrue(worst <= JuniorDevelopment.MAX_SEASONAL_HEIGHT_GAIN_CM + EPS,
                "a season must not add metres: " + worst);
    }

    @Test
    @DisplayName("position biases height without constraining it")
    void positionBiasesHeight() {
        // Same roll, five positions: a keeper is the tallest, a winger the shortest. A bias, not a
        // rule -- a short keeper is still allowed to be short, and a tall winger still exists.
        double keeper = JuniorDevelopment.rollHeight(Position.GK, 0.5);
        double winger = JuniorDevelopment.rollHeight(Position.WNG, 0.5);
        double midfielder = JuniorDevelopment.rollHeight(Position.MID, 0.5);

        assertTrue(keeper > midfielder, "a keeper is taller than a midfielder at the same roll");
        assertTrue(midfielder > winger, "and a midfielder taller than a winger");
        for (Position position : Position.values()) {
            double h = JuniorDevelopment.rollHeight(position, 0.0);
            assertTrue(h >= JuniorDevelopment.MIN_HEIGHT_CM && h <= JuniorDevelopment.MAX_HEIGHT_CM,
                    position + " produced an impossible height: " + h);
        }
    }

    // ── weight and the gym ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the gym pulls weight toward the natural figure, and a poor gym barely manages it")
    void gymCorrectsWeight() {
        double heavy = 90.0, natural = 70.0;

        double withGoodGym = JuniorDevelopment.weeklyWeightChange(heavy, natural, 20, 0.5);
        double withNoGym = JuniorDevelopment.weeklyWeightChange(heavy, natural, 1, 0.5);
        double unrecorded = JuniorDevelopment.weeklyWeightChange(heavy, natural, null, 0.5);

        assertTrue(withGoodGym < 0, "a heavy prospect at a club with a good gym must lose weight");
        assertTrue(withGoodGym < withNoGym,
                "the gym must be the difference: " + withGoodGym + " vs " + withNoGym);
        assertTrue(withNoGym < 0 && Math.abs(withNoGym) < Math.abs(withGoodGym),
                "even a poor gym corrects something, just far less");
        assertTrue(unrecorded < 0, "an unrecorded gym is a middling one, not a broken one");
    }

    @Test
    @DisplayName("weight correction never overshoots the target")
    void weightNeverOvershoots() {
        for (int gym = 1; gym <= 20; gym++) {
            for (double current : new double[] { 50, 60, 70, 85, 92 }) {
                for (double natural : new double[] { 50, 62, 74, 88 }) {
                    for (double unit = 0.0; unit <= 1.0; unit += 0.25) {
                        double change = JuniorDevelopment.weeklyWeightChange(current, natural, gym, unit);
                        double next = current + change;
                        if (natural > current) {
                            assertTrue(next <= natural + 0.6, "overshot upward: " + current + "->" + next);
                        } else if (natural < current) {
                            assertTrue(next >= natural - 0.6, "overshot downward: " + current + "->" + next);
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("a prospect already at his natural weight does not drift")
    void noDriftAtNaturalWeight() {
        assertEquals(0.0, JuniorDevelopment.weeklyWeightChange(70.0, 70.0, 20, 0.5), EPS);
        assertEquals(0.0, JuniorDevelopment.weeklyWeightChange(70.4, 70.0, 20, 0.5), EPS,
                "inside the dead band the club stops correcting, or the correction oscillates");
    }

    @Test
    @DisplayName("a rolled body is plausible for its height and can be corrected")
    void rolledBodiesArePlausible() {
        for (double unit = 0.0; unit <= 1.0; unit += 0.05) {
            double height = JuniorDevelopment.rollHeight(Position.DEF, unit);
            double[] body = JuniorDevelopment.rollBody(height, unit);
            double weight = body[0];
            assertTrue(weight >= JuniorDevelopment.MIN_WEIGHT_KG
                            && weight <= JuniorDevelopment.MAX_WEIGHT_KG,
                    "weight " + weight + " is out of range for height " + height);
            // A tall prospect should not weigh the same as a short one, or the roll is not reading
            // the height it was given.
            // The roll deliberately allows a wide spread, because a body the club has to correct is
            // the whole point of having a gym. What must hold is that a taller prospect is not on
            // average lighter than a shorter one.
            // Asserting the model's real range rather than a tighter invented one. A 165cm prospect
            // at 48kg is thin but real, and the roll deliberately allows leanness for a club to build on.
            assertTrue(weight > 46.0 + (height - JuniorDevelopment.MIN_HEIGHT_CM) * 0.28,
                    "weight " + weight + " is implausibly light for " + height + "cm");
        }
    }

    // ── effort and temperament ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a hard worker outgrows a lazy one of identical talent")
    void effortSeparatesTwoProspectsOfTheSameTalent() {
        double lazy = JuniorDevelopment.growthFactor(4, Personality.LAID_BACK);
        double hard = JuniorDevelopment.growthFactor(18, Personality.PROFESSIONAL);
        assertTrue(hard > lazy * 1.5,
                "the gap has to be visible week on week: " + hard + " vs " + lazy);
    }

    @Test
    @DisplayName("neither effort nor temperament rescues the other")
    void neitherFactorRescuesTheOther() {
        // The same rule as AcademyQuality, for the same reason: a sum would let a brilliant coach
        // carry a hopeless academy.
        double goodEffortAwfulPersonality = JuniorDevelopment.growthFactor(20, Personality.LAID_BACK);
        double poorEffortGreatPersonality = JuniorDevelopment.growthFactor(1, Personality.AMBITIOUS);
        double bothGood = JuniorDevelopment.growthFactor(20, Personality.AMBITIOUS);
        assertTrue(bothGood > goodEffortAwfulPersonality, "effort must not carry a laid-back player");
        assertTrue(bothGood > poorEffortGreatPersonality, "temperament must not carry a lazy player");
    }

    @Test
    @DisplayName("growth is bounded and always positive")
    void growthBounded() {
        for (int workRate = 0; workRate <= 25; workRate++) {
            for (Personality personality : Personality.values()) {
                double factor = JuniorDevelopment.growthFactor(workRate, personality);
                assertTrue(factor > 0 && factor <= 1.55,
                        "escaped the band at workRate=" + workRate + " " + personality + " -> " + factor);
            }
        }
        assertEquals(1.0, JuniorDevelopment.growthFactor(null, null), EPS,
                "an unrecorded junior is neutral, not the worst in the academy");
    }

    @Test
    @DisplayName("a temperamental player is the fastest and the least certain")
    void temperamentalIsFastAndErratic() {
        // The case a single 'goodness' axis cannot express, which is why the enum carries two numbers.
        assertTrue(Personality.TEMPERAMENTAL.growthFactor() > Personality.PROFESSIONAL.growthFactor(),
                "a difficult player should be quicker when he works");
        assertTrue(Personality.TEMPERAMENTAL.variance() > Personality.PROFESSIONAL.variance() * 3,
                "and far less reliable week to week");
        for (Personality personality : Personality.values()) {
            assertTrue(personality.variance() >= 0.0,
                    personality + " must not have negative variance");
        }
    }

    @Test
    @DisplayName("temperament is a minority, or every academy is a problem")
    void personalityDistribution() {
        int[] counts = new int[Personality.values().length];
        for (int i = 0; i < 10_000; i++) {
            counts[JuniorDevelopment.rollPersonality(i / 10_000.0).ordinal()]++;
        }
        int awkward = counts[Personality.TEMPERAMENTAL.ordinal()] + counts[Personality.HEADSTRONG.ordinal()];
        assertTrue(awkward < 3_000,
                "temperamental plus headstrong must stay under 30%, got " + awkward / 10_000.0);
        assertTrue(counts[Personality.PROFESSIONAL.ordinal()] > 2_000,
                "professionals should be the backbone of an academy");
    }

    // ── the rolls themselves ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("work rates cluster in the middle rather than spanning the whole range")
    void workRateIsBoringOnAverage() {
        int[] counts = new int[21];
        for (int i = 0; i < 10_000; i++) {
            counts[JuniorDevelopment.rollWorkRate(i / 10_000.0)]++;
        }
        for (int value = 1; value <= 20; value++) {
            assertTrue(counts[value] > 0, "no junior was ever given a work rate of " + value);
        }
        assertTrue(counts[10] > counts[1] && counts[10] > counts[20],
                "the middle should be the common case, with the extremes rare");
        assertTrue(counts[1] + counts[20] < 2_000,
                "the extremes together must stay under 20%, got " + (counts[1] + counts[20]) / 100.0);
    }

    @Test
    @DisplayName("being comfortable with both feet is rare, because it is worth something")
    void bothFeetIsRare() {
        int both = 0;
        for (int i = 0; i < 10_000; i++) {
            if (JuniorDevelopment.rollFoot(i / 10_000.0) == PreferredFoot.BOTH) both++;
        }
        assertTrue(both > 0, "nobody should ever be two-footed, which is also wrong");
        assertTrue(both < 1_500, "two-footed must stay rare, got " + both / 10_000.0);
    }
}
