package org.example.footballmanager.newLogic.model;

/**
 * How much a match is worth to a rating (owner, 2026-09-28).
 *
 * <p>The owner set the order in one line: <b>an international cup match is worth more than a league
 * match, which is worth more than a cup match, which is worth more than a friendly.</b>
 *
 * <p>That ordering is not cosmetic — it is what makes a rating mean anything. A rating that moved
 * equally for a meaningless friendly and for a World Cup would be an average of a signal and its
 * absence. The scale factor is the only place that judgement is encoded, so it lives in one enum
 * rather than as a number at each call site.
 *
 * <p>Ordered strongest-first so callers can compare with {@code >=} instead of remembering the order.
 */
public enum MatchValue {

    /** International club cup, or any match between national teams. The heaviest. */
    INTERNATIONAL(1.35),

    /** Ordinary league football. The reference point: everything else is measured against it. */
    LEAGUE(1.00),

    /**
     * A domestic cup match.
     *
     * <p><b>Below a league match</b>, which is the owner's order and the thing this enum got wrong the
     * first time: it was written as 1.15, above league, on the reasoning that a knockout is more
     * consequential. It is not — a cup tie is a single match between clubs that meet anyway, while a
     * league match is the thing a rating is <i>for</i>. A property test asserting the owner's stated
     * order caught it, which is the argument for writing the order down as an assertion.
     */
    CUP(0.90),

    /**
     * A friendly. Deliberately the lowest and deliberately <b>below</b> 1.0.
     *
     * <p>A friendly still moves the rating, because a manager playing a reserve side against a
     * European qualifier is a real result and ignoring it would make friendlies free wins. But at
     * 0.7 they count for about two thirds of a league match, so a fixture pile-up cannot outvote a
     * season of meaningful football.
     */
    FRIENDLY(0.70);

    private final double scale;

    MatchValue(double scale) {
        this.scale = scale;
    }

    public double scale() {
        return scale;
    }

    /** Whether this match should move a national rating at all, as opposed to a club's. */
    public boolean isNational() {
        return this == INTERNATIONAL;
    }
}
