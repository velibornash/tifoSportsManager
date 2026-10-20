package org.tifo.footballmanager.newLogic.fantasy;

import org.tifo.footballmanager.newLogic.model.Position;

/**
 * What a player costs in fantasy (owner, 2026-10-17).
 *
 * <h2>The table</h2>
 *
 * <p>The owner's scale, exactly as he specified it: base by tier, ordered by position inside every tier,
 * with attackers dearest and midfielders cheapest.
 *
 * <pre>
 *          ATT   DEF    GK   MID
 *   T1    13.0  11.5  10.5  10.0
 *   T2    10.5   9.0   8.5   8.0
 *   T3     8.5   7.5   7.0   6.5
 *   T4     6.5   6.0   5.5   5.0
 *   T5     5.5   5.0   4.5   4.0
 * </pre>
 *
 * <p>It is a lookup table and not a formula on purpose. A formula was used to derive it and the rounding
 * does not reproduce it exactly in the lower tiers, so the table is the specification and the code is the
 * table. Twenty numbers, verifiable by eye.
 *
 * <h2>Why a base is not the whole price</h2>
 *
 * <p>A flat table has a defect that only shows up in the arithmetic: every player in a tier and position
 * costs the same, and the per-tier rule pins exactly three players from each tier, so <b>every legal squad
 * costs between 111.0 and 115.5</b>. A budget would then be decoration — there are cheap and expensive
 * <i>slots</i> but no cheap and expensive <i>players</i>.
 *
 * <p>The owner's own statement of the point is the reason the nudge exists: <i>"fora i jeste da nadjes
 * najbolji miks jeftinih i skupih igraca"</i>. {@link #nudge} creates that mix, by ranking each player
 * inside his own tier and position and adding up to {@link #NUDGE_MAX}.
 *
 * <h2>The one consequence to know about</h2>
 *
 * <p><b>A nudge big enough to matter is bigger than the step between adjacent tiers, so the tier ordering
 * does not survive at the extremes.</b> The smallest tier step is 1.0 (tier 4 to tier 5) and the nudge
 * reaches 3.0, so for the same position the best player of a lower tier can cost more than the worst of
 * the tier above: the best fourth-division attacker is 6.5 + 3.0 = 9.5, more than the worst
 * third-division attacker at 8.5, and the best fifth-division attacker is 5.5 + 3.0 = 8.5, more than the
 * worst fourth-division attacker at 6.5.
 *
 * <p>That is a deliberate trade and it is the owner's to revisit. The two requirements cannot both hold:
 * a strict tier ordering caps the nudge below 1.0, which caps the legal range of a squad at about 120, and
 * a budget above 120 then never binds. Cheap-but-good lower-league players and expensive-but-poor
 * higher-league ones <i>are</i> the mix. {@link #basePrice} keeps the owner's ordering exactly; only the
 * nudge crosses a tier.
 */
public final class FantasyPricing {

    private FantasyPricing() {
    }

    /**
     * What a manager has to build a squad with.
     *
     * <p>145.0 (owner, 2026-10-18), raised from 135.0. The floor is 111.0 — the cheapest legal fifteen — so
     * this is 34.0 above it, which buys about two thirds of the available upgrades rather than half. The
     * owner's scale is unchanged; only the amount of it a manager can afford moved.
     */
    public static final double BUDGET = 145.0;

    /** The most a player can be nudged above his tier-and-position base. */
    public static final double NUDGE_MAX = 3.0;

    /** Prices are quoted to half a point, so a price is readable and two prices are comparable. */
    public static final double PRICE_STEP = 0.5;

    /** The cheapest a legal fifteen can possibly be, for reference and for the budget guard. */
    public static final double CHEAPEST_LEGAL_SQUAD = 111.0;

    /**
     * The base price: the owner's table, read off directly.
     *
     * @throws IllegalArgumentException when the tier has no fantasy price. A tier the table does not
     *         cover is a country whose pyramid does not match the rules, and pricing it silently would
     *         hand out free players.
     */
    public static double basePrice(int tier, Position position) {
        return switch (tier) {
            case 1 -> switch (FantasyRules.fantasyPositionOf(position)) {
                case GK -> 10.5;
                case DEF -> 11.5;
                case MID -> 10.0;
                case ATT -> 13.0;
                default -> throw noPrice(tier, position);
            };
            case 2 -> switch (FantasyRules.fantasyPositionOf(position)) {
                case GK -> 8.5;
                case DEF -> 9.0;
                case MID -> 8.0;
                case ATT -> 10.5;
                default -> throw noPrice(tier, position);
            };
            case 3 -> switch (FantasyRules.fantasyPositionOf(position)) {
                case GK -> 7.0;
                case DEF -> 7.5;
                case MID -> 6.5;
                case ATT -> 8.5;
                default -> throw noPrice(tier, position);
            };
            case 4 -> switch (FantasyRules.fantasyPositionOf(position)) {
                case GK -> 5.5;
                case DEF -> 6.0;
                case MID -> 5.0;
                case ATT -> 6.5;
                default -> throw noPrice(tier, position);
            };
            case 5 -> switch (FantasyRules.fantasyPositionOf(position)) {
                case GK -> 4.5;
                case DEF -> 5.0;
                case MID -> 4.0;
                case ATT -> 5.5;
                default -> throw noPrice(tier, position);
            };
            default -> throw noPrice(tier, position);
        };
    }

    /**
     * The premium for being good, inside a tier and a position.
     *
     * <p>Ranked from the best in the band to the worst, so {@code rankFromBest} 0 is the best player and
     * {@code bandSize - 1} is the worst. The best is worth the full {@link #NUDGE_MAX} and the worst
     * nothing, quantised to {@link #PRICE_STEP} so a price stays readable.
     *
     * <p>A band of one player is nudged by nothing: there is no rank to be better than.
     */
    public static double nudge(int rankFromBest, int bandSize) {
        if (bandSize <= 1) {
            return 0.0;
        }
        int rank = Math.max(0, Math.min(rankFromBest, bandSize - 1));
        double fraction = 1.0 - ((double) rank / (bandSize - 1));
        return roundToStep(NUDGE_MAX * fraction);
    }

    /**
     * What a player costs: his tier-and-position base plus his premium for being good.
     *
     * <p>Never negative and never below his base, so a price can be read as "at least what this tier and
     * position costs", which is what makes the owner's ordering meaningful at the base.
     */
    public static double price(int tier, Position position, int rankFromBest, int bandSize) {
        return roundToStep(basePrice(tier, position) + nudge(rankFromBest, bandSize));
    }

    /** The most any player can cost, for a budget guard that does not need the pool. */
    public static double maximumPossiblePrice() {
        double dearestBase = 0.0;
        for (int tier = 1; tier <= FantasyRules.TIERS_REQUIRED; tier++) {
            for (Position p : new Position[]{Position.GK, Position.DEF, Position.MID, Position.ATT}) {
                dearestBase = Math.max(dearestBase, basePrice(tier, p));
            }
        }
        return roundToStep(dearestBase + NUDGE_MAX);
    }

    private static double roundToStep(double value) {
        return Math.round(value / PRICE_STEP) * PRICE_STEP;
    }

    private static IllegalArgumentException noPrice(int tier, Position position) {
        return new IllegalArgumentException("No fantasy price for tier " + tier + " and position " + position);
    }
}
