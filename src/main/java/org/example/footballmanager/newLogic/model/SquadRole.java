package org.example.footballmanager.newLogic.model;

/**
 * How a player is regarded by his club.
 *
 * <p>This is not decoration: it decides what happens at a contract negotiation and what a wage
 * demand looks like. A 22-year-old prospect on a long contract and a 34-year-old starter both
 * "under contract", but only one of them is a negotiation.
 */
public enum SquadRole {

    /** The best players. A star wants a very large wage and will agitate if he does not get it. */
    STAR,

    /** A regular first-team player. Expects a fair wage, stays if paid. */
    STARTER,

    /** Squad player. Will accept a modest wage, will leave for a small rise. */
    ROTATION,

    /** Young player being developed. Cheap, and the club wants to keep him. */
    PROSPECT,

    /** Academy player. Cheapest of all, and the most likely to be sold. */
    YOUTH;

    /**
     * How much of his value he expects as a weekly wage, as a share of value annualised.
     *
     * <p>Derived from role because a squad's wage structure is a shape, not a flat rate: clubs pay
     * stars far more per unit of ability than prospects, and a model that paid everyone the same
     * made every squad cost the same.
     */
    public double wageExpectationFactor() {
        // Calibrated against the real relationship between a fee and the wages that follow it: a
        // club signing for 50m on a four-year deal typically pays around 30% of the fee per YEAR in
        // wages, so the total outlay is the fee plus roughly 120% of it over the contract.
        //
        // The first pass used 0.55 for a star, which is ~1.8x that and produced a 12m player
        // demanding EUR 105,000 a week. Not impossible at the very top, but high enough that such a
        // player was unaffordable for every club in the game, which silently broke S2.5's wage
        // ceiling rather than testing it.
        return switch (this) {
            case STAR -> 0.32;
            case STARTER -> 0.20;
            case ROTATION -> 0.12;
            case PROSPECT -> 0.07;
            case YOUTH -> 0.04;
        };
    }

    /** How reluctant the club is to sell, 0 (will sell) to 1 (will not). */
    public double reluctanceToSell() {
        return switch (this) {
            case STAR -> 0.92;
            case STARTER -> 0.75;
            case ROTATION -> 0.45;
            case PROSPECT -> 0.30;
            case YOUTH -> 0.12;
        };
    }

    /** Whether this role counts against the senior squad limit. */
    public boolean isSenior() {
        return this != YOUTH;
    }
}
