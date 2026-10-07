package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.MatchType;
import org.example.footballmanager.newLogic.model.NationalStage;

/**
 * The one ranking-points system (owner, 2026-10-07).
 *
 * <p><b>What the owner asked for, in their own terms.</b> Every team starts on 1500 and earns or loses
 * points for <i>every match, according to how the result compared with what was expected of it</i>. A
 * one-off bonus is added for qualifying into an international cup, and for trophies and for the stage a
 * team reached. Different competitions and different divisions are worth different amounts. Friendlies
 * count, at the smallest amount there is.
 *
 * <p><b>This is not Elo, and the difference is the whole point.</b> The rating that already existed
 * ({@link RatingEngine}) is head-to-head: it weights a result by the rating gap, so beating a strong
 * side moves you more than beating a weak one. The owner rejected that: *"snaga tima moze da utice na
 * projekciju rezultata ali ne i na rejting poene"* — a team's strength may move the <i>forecast</i>,
 * never the points. So this system has no gap term anywhere, and there is a property test asserting
 * exactly that: identical margins and different opponents must score identically.
 *
 * <p>The one idea: <b>staying inside the outcome you were expected to achieve is worth nothing,
 * crossing it is worth a lot, and the size of the crossing is graded.</b> Winning by less than you were
 * expected to is still a win, so it is worth 0 and never a penalty. Losing by less than you were
 * expected to is still a loss, so it earns 0 and never a penalty either.
 *
 * <p>Pure arithmetic, no Spring, no repository, no clock. Everything here is a function of its
 * arguments, which is what makes the whole thing testable row by row.
 */
public final class RankingPointsEngine {

    private RankingPointsEngine() {
    }

    // ── The starting point ──────────────────────────────────────────────────────────────────────────

    /** Every club and every national side starts here, so all of them begin level. */
    public static final double START_POINTS = 1500.0;

    // ── Season decay ────────────────────────────────────────────────────────────────────────────────

    /**
     * How much of each season's points still counts, newest first (owner, 2026-10-07).
     *
     * <p>A rolling window of four seasons at {@code 100 / 75 / 50 / 25}. Chosen over resetting each
     * season, because a reset throws away the difference between a side that has been good for years
     * and one that had one good year; and over accumulating forever, because a World Cup won eight
     * seasons ago should still count for something and not for everything.
     */
    public static final double[] SEASON_WEIGHTS = {1.00, 0.75, 0.50, 0.25};

    /**
     * The weight for a season that ended {@code seasonsAgo} seasons before the current one.
     *
     * <p>Anything older than the window is not in the table, and so is worth nothing: at
     * {@link #SEASON_WEIGHTS#length} the answer is {@code 0} rather than an exception, because a
     * replay walking five seasons of history is not a bug.
     */
    public static double seasonWeight(int seasonsAgo) {
        if (seasonsAgo < 0 || seasonsAgo >= SEASON_WEIGHTS.length) {
            return 0.0;
        }
        return SEASON_WEIGHTS[seasonsAgo];
    }

    // ── What a team was expected to do ──────────────────────────────────────────────────────────────

    /**
     * How close the forecast has to be to counting as a win or a loss.
     *
     * <p><b>Chosen from measurement, and the first value was wrong in a way nothing would have
     * revealed.</b> This was 2.0, on the reasoning that a forecast 3-1 is a margin of two. But
     * {@link ScheduleInsightService} produces expected goals, and across the whole reachable range of
     * squad strengths (38-92) the forecast margin only ever spans <b>-1.10 to +1.79</b>, with two
     * exactly equal sides sitting at <b>+0.50</b> because of home advantage. A threshold of 2.0 therefore
     * made <i>expected to win</i> unreachable: no fixture in the game could ever pay out the top rungs of
     * the ladder, and the strongest possible favourite was scored as a coin flip.
     *
     * <p>1.0 puts a genuine edge over a threshold the model can actually reach: a 92-rated side against
     * a 62-rated one forecasts +1.58 and counts as an expected win, while two sides within a few points
     * forecast under 0.5 and do not.
     *
     * <p>{@code MatchPreviewPredictionTest.theTopRungIsReachable} fails if this ever drifts back out of
     * the model's range.
     */
    public static final double EXPECTED_WIN_MARGIN = 1.0;

    /** What the forecast said a team would do. */
    public enum Expected {
        /** Forecast to win by {@link #EXPECTED_WIN_MARGIN} or more. */
        WIN,
        /** Forecast inside one goal either way — a coin-flip fixture. */
        DRAW,
        /** Forecast to lose by {@link #EXPECTED_WIN_MARGIN} or more. */
        LOSS
    }

    /**
     * What the forecast said, from the expected-goal margin.
     *
     * <p>Uses the forecast's own expected goals rather than a bare win/draw/loss flag, because the
     * owner asked for "mala razlika" — a small edge is not an expected win. A side forecast to win by
     * one goal is a side that will be judged on the result, not on a 3-1 scoreline.
     */
    public static Expected expected(double expectedMargin) {
        if (expectedMargin >= EXPECTED_WIN_MARGIN) {
            return Expected.WIN;
        }
        if (expectedMargin <= -EXPECTED_WIN_MARGIN) {
            return Expected.LOSS;
        }
        return Expected.DRAW;
    }

    // ── The ladder ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Points for crossing the expected outcome by {@code goals} goals.
     *
     * <p>Graded and capped, at the owner's choice. The cap matters: a 9-0 against a side forecast to
     * win by one is the biggest available shock, and without a cap a single freak result would decide a
     * season. 30 / 40 / 50 for the first three goal of crossing, and no more.
     */
    private static double crossing(int goals) {
        return switch (goals) {
            case 1 -> 30.0;
            case 2 -> 40.0;
            default -> 50.0;
        };
    }

    /** Points for the milder crossing: reaching exactly a draw when one side was not expected to get it. */
    private static final double DRAW_CROSSING = 20.0;

    // ── Competition value ───────────────────────────────────────────────────────────────────────────

    /** An ordinary league match: the reference everything else is measured against. */
    public static final double LEAGUE = 1.00;

    /** A domestic cup tie. Worth more than a league match because it is rarer and cannot be rescheduled. */
    public static final double NATIONAL_CUP = 1.25;

    /** An international club cup match — the heaviest club football there is. */
    public static final double INTERNATIONAL_CLUB_CUP = 1.50;

    /** A World Cup qualifying match. */
    public static final double NATIONAL_QUALIFYING = 1.20;

    /** A World Cup finals match. */
    public static final double NATIONAL_TOURNAMENT = 2.00;

    /**
     * A friendly, club or national.
     *
     * <p>The smallest amount there is, at the owner's choice — it counts, so a reserve side beating a
     * European qualifier is still a result, but a fixture pile-up cannot outvote a season of
     * meaningful football.
     */
    public static final double FRIENDLY_VALUE = 0.30;

    /**
     * What a match of this competition is worth, before the division it is played in.
     *
     * <p>Split by scope as well as type, because {@link CompetitionType#CUP} is the same enum value for
     * a domestic cup and a continental one and they are not the same match. Falls back to league for
     * anything unrecognised rather than to friendly, so an unrecognised competition is never the
     * cheapest thing on the list by accident.
     */
    public static double competitionValue(MatchType matchType, CompetitionType type,
                                          CompetitionScope scope, CompetitionTeamType teamType,
                                          NationalStage stage) {
        // Friendliness lives on MatchType, not CompetitionType - there is no friendly *competition*, and
        // an exhibition is likewise not a competition at all. Both are checked here so a caller cannot
        // award competition points for a game that has none.
        if (matchType == MatchType.FRIENDLY || matchType == MatchType.EXHIBITION) {
            return FRIENDLY_VALUE;
        }
        if (scope == CompetitionScope.INTERNATIONAL) {
            // **A continental club cup and a national-team qualifier share BOTH CompetitionType and
            // CompetitionScope**, so neither field can tell them apart and a first version scored every
            // Champions Cup tie at the qualifying rate. `teamType` is the only thing that separates a
            // club entering a European cup from a country playing a World Cup qualifier.
            if (teamType == CompetitionTeamType.NATIONAL_TEAM) {
                return stage == NationalStage.WORLD_CUP ? NATIONAL_TOURNAMENT : NATIONAL_QUALIFYING;
            }
            return INTERNATIONAL_CLUB_CUP;
        }
        if (type == CompetitionType.CUP) {
            return NATIONAL_CUP;
        }
        return LEAGUE;
    }

    // ── Division weight ──────────────────────────────────────────────────────────────────────────────

    /** Tier 1 is the top division, and the owner was explicit that tier 1 is worth most. */
    public static final double TIER_1 = 1.00;
    public static final double TIER_2 = 0.85;
    public static final double TIER_3 = 0.70;
    public static final double TIER_4 = 0.55;
    public static final double TIER_5 = 0.40;

    /**
     * How much a competition is worth when played in this division.
     *
     * <p>Falling away by tier is why the per-match amounts are decimals: a tier-3 win that crosses the
     * expected outcome by two goals is {@code 40 x 0.70 = 28.0} points, and a tier-1 one is 40.0.
     *
     * <p>A national team has no tier and passes {@code 1}, so it is scored at full weight.
     *
     * <p>Anything outside the five tiers falls to {@link #TIER_1} rather than to {@link #TIER_5}. The
     * first version clamped to tier 5, which meant a competition with no recorded division was scored
     * as the cheapest football on the list - the opposite of what "unknown" should mean, and a silent
     * way to under-rate a side.
     */
    public static double tierWeight(int tier) {
        return switch (tier) {
            case 2 -> TIER_2;
            case 3 -> TIER_3;
            case 4 -> TIER_4;
            case 5 -> TIER_5;
            default -> TIER_1;
        };
    }

    // ── The formula ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Points for one match. {@code pointsFor(expectedMargin, actualMargin)} is the ladder scaled by what
     * the match was worth.
     *
     * <p>The ladder in full:
     *
     * <table>
     *   <caption>Points for crossing the expected outcome</caption>
     *   <tr><th>Forecast</th><th>Actual</th><th>Points</th></tr>
     *   <tr><td>win</td><td>win by &ge; margin + 3</td><td>+3 / +4 / +5 goals, capped</td></tr>
     *   <tr><td>win</td><td>win, but by less than the margin</td><td>0</td></tr>
     *   <tr><td>win</td><td>draw</td><td>&minus;2</td></tr>
     *   <tr><td>win</td><td>lose by 1 / 2 / &ge;3</td><td>&minus;3 / &minus;4 / &minus;5</td></tr>
     *   <tr><td>draw</td><td>win by 1 / 2 / &ge;3</td><td>+3 / +4 / +5</td></tr>
     *   <tr><td>draw</td><td>draw</td><td>0</td></tr>
     *   <tr><td>draw</td><td>lose by 1 / 2 / &ge;3</td><td>&minus;3 / &minus;4 / &minus;5</td></tr>
     *   <tr><td>lose</td><td>win by 1 / 2 / &ge;3</td><td>+3 / +4 / +5</td></tr>
     *   <tr><td>lose</td><td>draw</td><td>+2</td></tr>
     *   <tr><td>lose</td><td>lose by &le; the forecast</td><td>0</td></tr>
     *   <tr><td>lose</td><td>lose by &gt; the forecast</td><td>&minus;3 / &minus;4 / &minus;5</td></tr>
     * </table>
     *
     * <p>Note the symmetry the owner asked for in both directions: <b>winning by less than the
     * forecast is still a win and is worth 0 rather than a penalty</b>, and <b>losing by less than the
     * forecast is still a loss and earns 0 rather than costing anything</b>. Points are only ever won or
     * lost by crossing the line that was forecast, never by falling short inside it.
     *
     * @param expectedMargin forecast goals for minus forecast goals against
     * @param actualMargin   goals scored minus goals conceded
     * @param value          what the competition is worth
     * @param tierWeight     what the division is worth; {@code 1} for a national team
     * @return points to add to the team's total; may be fractional, and may be negative
     */
    public static double pointsFor(double expectedMargin, double actualMargin, double value, double tierWeight) {
        return ladder(expectedMargin, actualMargin) * value * tierWeight;
    }

    /**
     * How many goals past the threshold the result crossed by, capped at the ladder's top rung.
     *
     * <p>The threshold is three: staying inside the outcome you were expected to achieve is worth
     * nothing, and three goals beyond it is the first rung.
     */
    private static final double CROSSING_THRESHOLD = 3.0;

    private static int rungs(double goalsBeyond) {
        return (int) Math.min(3.0, goalsBeyond - CROSSING_THRESHOLD + 1.0);
    }

    private static int goalsWon(double actualMargin) {
        return (int) Math.min(3.0, Math.max(1.0, actualMargin));
    }

    private static int goalsLost(double actualMargin) {
        return (int) Math.min(3.0, Math.max(1.0, -actualMargin));
    }

    /** The unscaled ladder, exposed so the test table can assert it without restating the multipliers. */
    static double ladder(double expectedMargin, double actualMargin) {
        Expected forecast = expected(expectedMargin);
        boolean won = actualMargin > 0;
        boolean drew = actualMargin == 0;

        switch (forecast) {
            case WIN -> {
                if (won) {
                    // Won, as expected. How much MORE than the forecast?
                    double beyond = actualMargin - expectedMargin;
                    return beyond >= CROSSING_THRESHOLD ? crossing(rungs(beyond)) : 0.0;
                }
                // Fell below what was expected at all.
                return drew ? -DRAW_CROSSING : -crossing(goalsLost(actualMargin));
            }
            case DRAW -> {
                if (won) {
                    return crossing(goalsWon(actualMargin));
                }
                return drew ? 0.0 : -crossing(goalsLost(actualMargin));
            }
            case LOSS -> {
                if (won) {
                    return crossing(goalsWon(actualMargin));
                }
                if (drew) {
                    return DRAW_CROSSING;
                }
                // Lost, as expected. How much WORSE than the forecast? A forecast margin is negative,
                // so "worse" is conceded above what was forecast.
                double worse = -actualMargin + expectedMargin;
                return worse >= CROSSING_THRESHOLD ? -crossing(rungs(worse)) : 0.0;
            }
            default -> throw new IllegalStateException("Unreachable forecast: " + forecast);
        }
    }
}
