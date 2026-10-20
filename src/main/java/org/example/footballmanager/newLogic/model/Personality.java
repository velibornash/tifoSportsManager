package org.example.footballmanager.newLogic.model;

/**
 * How a player is wired, and what that is worth in a dressing room (Sprint 5.3, owner 2026-09-27).
 *
 * <p>Rolled at intake and <b>carried into the senior squad</b>, because a temperamental nineteen-year-old
 * becomes a temperamental twenty-three-year-old. That is not a flourish: personality feeds the morale
 * → growth factor in {@code MoraleService}, so a club that signs a difficult teenager has signed a
 * difficult player.
 *
 * <p>Every value carries two numbers, because a single "goodness" axis would flatten the difference
 * between a player who is merely steady and one who genuinely improves the people around him:
 * a growth multiplier, and how <i>reliably</i> that growth arrives. {@link #TEMPERAMENTAL} is
 * deliberately the fastest and the least certain — the interesting case, and the one a single axis
 * cannot express.
 */
public enum Personality {

    /** Works to the plan. Predictable, and the safest thing to develop. */
    PROFESSIONAL("Professional", 1.00, 0.00),

    /** Hungry. Develops faster than a professional, and errs on the side of overreach. */
    AMBITIOUS("Ambitious", 1.10, 0.06),

    /** Better than average when it works, and a coin-flip when it does not. */
    TEMPERAMENTAL("Temperamental", 1.15, 0.22),

    /**
     * Needs pushing. Slow, but not hopeless — a coaching environment that works with him gets real
     * development out of him, which is the point of having him at all.
     */
    LAID_BACK("Laid-back", 0.82, 0.04),

    /**
     * Does not do what he is told, and not always badly. Highest ceiling and highest variance: the
     * player a manager has to handle rather than manage.
     */
    HEADSTRONG("Headstrong", 1.05, 0.18);

    private final String label;
    private final double growthFactor;
    private final double variance;

    Personality(String label, double growthFactor, double variance) {
        this.label = label;
        this.growthFactor = growthFactor;
        this.variance = variance;
    }

    public String label() {
        return label;
    }

    /**
     * How much this personality helps or hinders development.
     *
     * <p>Sized to stay inside a narrow band on purpose. Sprint 2.6 already established the reasoning
     * for morale: a wide swing turns a bad week into a feedback loop where a player develops less, is
     * picked less, and develops less again. Personality is the same lever wearing a different coat, and
     * it gets the same restraint.
     */
    public double growthFactor() {
        return growthFactor;
    }

    /**
     * How far his weekly development wanders from the expected figure.
     *
     * <p>Expressed as a proportion of the weekly delta, so a high-variance player is erratic in the
     * sense that matters — he is not reliable week to week — rather than erratic in absolute terms.
     */
    public double variance() {
        return variance;
    }

    /** The value used when a legacy row has no personality recorded. */
    public static Personality orDefault(Personality stored) {
        return stored == null ? PROFESSIONAL : stored;
    }
}
