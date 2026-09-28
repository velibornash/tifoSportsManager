package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.MatchValue;

/**
 * Elo rating movement (owner, 2026-09-28).
 *
 * <p>Pure arithmetic with no Spring, no repository and no entity: it takes numbers and returns
 * numbers. That is deliberate. A rating system is exactly the sort of thing that goes subtly wrong
 * in a way no integration test catches, and a self-contained function can be property-tested —
 * "a win never lowers a rating", "a loss never raises one", "the sum of a match is zero" — without a
 * database, a clock or a fixture.
 *
 * <h2>Why Elo rather than a hand-rolled table</h2>
 *
 * <p>The owner asked for the behaviour, not the formula, and the required behaviour is:
 * <ul>
 *   <li>beating a <b>higher</b>-rated side is worth more;</li>
 *   <li>losing to a <b>lower</b>-rated side costs more;</li>
 *   <li>a World Cup win is worth more than a qualifying win, plus a bonus for qualifying at all;</li>
 *   <li>an international cup match is worth more than a league match, and so on down to friendlies.</li>
 * </ul>
 *
 * <p>All four fall out of the standard formula, which is why it is worth using rather than inventing:
 * {@code delta = K × (actual − expected)}, where {@code expected} is low when you are the underdog.
 * Beating a stronger side produces a large positive delta because you exceeded a low expectation;
 * losing to a weaker one produces a large negative one for the same reason. There is no separate
 * "upset bonus" to get out of step with the main maths.
 *
 * <h2>National and club ratings are the same arithmetic, differently weighted</h2>
 *
 * <p>National teams move <b>only</b> on matches between nations, per the owner, so their K is driven
 * by the competition stage. Clubs move on everything they play, so their K is driven by
 * {@link MatchValue}. Keeping one formula and two weightings means an upset is computed the same way
 * everywhere — a tier-5 club beating a tier-1 club in a cup is an enormous gain precisely because
 * {@code expected} is tiny, which is what the owner meant by "neverovatan rating boost".
 */
public final class RatingEngine {

    /**
     * Base K for a club in ordinary league football.
     *
     * <p>32 is a deliberately brisk Elo. A season is twelve weeks, so a manager sees very few rated
     * matches; a textbook K of 16 would take several seasons to separate a strong side from a weak one,
     * and the ranking has to be meaningful by the time a cup draw needs it.
     */
    public static final double CLUB_BASE_K = 32.0;

    /**
     * Base K for a national team.
     *
     * <p>Lower than a club's on purpose. A country plays a handful of internationals a year, and the
     * owner wants a World Cup result to <b>move</b> the rating rather than blow it to a new extreme —
     * otherwise one nation wins one tournament and is ranked top of the world for a decade.
     */
    public static final double NATIONAL_BASE_K = 16.0;

    /** The rating every national team starts at, so all 48 begin level. */
    public static final double NATIONAL_START_RATING = 1500.0;

    private RatingEngine() {
    }

    /**
     * The score the system expected, as 1.0 for a certain win and 0.0 for a certain loss.
     *
     * <p>Standard Elo expectation. The 400 divisor is the scale: a 400-point gap is about a 10-to-1
     * favourite. Ratings are meant to sit in the hundreds, which is why a club's tier offset below is
     * expressed as a few hundred points rather than a rank number.
     */
    public static double expected(double ownRating, double opponentRating) {
        return 1.0 / (1.0 + Math.pow(10.0, (opponentRating - ownRating) / 400.0));
    }

    /**
     * The rating change for one side of one match.
     *
     * @param actual 1.0 win, 0.5 draw, 0.0 loss
     * @param k      the weight for this specific match — see {@link #clubK} and {@link #nationalK}
     * @return the signed change; negative means the rating falls
     */
    public static double delta(double ownRating, double opponentRating, double actual, double k) {
        return k * (actual - expected(ownRating, opponentRating));
    }

    /**
     * The weight for a club match.
     *
     * <p>Scale by {@link MatchValue}, and by how big the gap is: a mismatch should move more than a
     * coin-flip, or a rating stops distinguishing "held on to a draw against Roma" from "beat Roma".
     * The gap weight tops out at 1.6 so a hopeless mismatch cannot launch a club to the top of the
     * list on one result.
     */
    public static double clubK(MatchValue value, double ownRating, double opponentRating) {
        double gap = Math.abs(ownRating - opponentRating);
        double gapWeight = 1.0 + Math.min(0.6, gap / 800.0);
        return CLUB_BASE_K * value.scale() * gapWeight;
    }

    /** The weight for a club match, using the value alone. */
    public static double clubK(MatchValue value) {
        return CLUB_BASE_K * value.scale();
    }

    /**
     * The weight for a national-team match (owner, 2026-09-28).
     *
     * <p>The three stages the owner named, in descending order of consequence:
     * <ul>
     *   <li>a <b>World Cup</b> match counts most;</li>
     *   <li>a <b>qualifying</b> match counts next;</li>
     *   <li>everything else — friendlies, and any future stage — counts least.</li>
     * </ul>
     *
     * <p>Note this is about the <b>weight of a single match</b>, and is separate from the
     * <b>qualification bonus</b> in {@link #qualificationBonus} — reaching the tournament at all is
     * its own reward, not a bigger share of one match.
     */
    public static double nationalK(MatchValue value, NationalStage stage) {
        double stageWeight = switch (stage == null ? NationalStage.OTHER : stage) {
            case WORLD_CUP -> 2.0;
            case QUALIFYING -> 1.25;
            case OTHER -> 0.6;
        };
        return NATIONAL_BASE_K * value.scale() * stageWeight;
    }

    /**
     * The bonus for having qualified for the tournament at all (owner, 2026-09-28).
     *
     * <p>Applied once to every qualifier's rating when the group stage ends. The owner asked for "a
     * bonus for qualifying in general" as a separate thing from the value of a match, and that
     * distinction is the whole point: a nation that grinds through qualifying and narrowly goes out
     * has still done something, and its rating should say so even though it won no knockout match.
     */
    public static double qualificationBonus() {
        return 30.0;
    }

    /**
     * The rating a club starts at, from its league tier (owner, 2026-09-28).
     *
     * <p>"Clubs from higher tiers are ranked higher than clubs from lower leagues" — and not
     * marginally. A tier gap of 200 points is roughly a 2-to-1 expected result, which is about right
     * for a step between tiers and means a lower-tier club has to genuinely outperform its division
     * to climb.
     *
     * <p>Offset from the middle so that a tier-1 club and a national team sit in a comparable numeric
     * range and neither can drift somewhere silly within a season.
     */
    public static double clubStartRating(int tier, int highestTier) {
        if (tier < 1) {
            tier = 1;
        }
        int worst = Math.max(tier, highestTier);
        double base = 1500.0 + (worst - tier) * 200.0;
        return base;
    }
}
