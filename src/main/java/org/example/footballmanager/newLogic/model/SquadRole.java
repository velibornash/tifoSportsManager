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
        return switch (this) {
            case STAR -> 0.55;
            case STARTER -> 0.34;
            case ROTATION -> 0.20;
            case PROSPECT -> 0.11;
            case YOUTH -> 0.06;
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
