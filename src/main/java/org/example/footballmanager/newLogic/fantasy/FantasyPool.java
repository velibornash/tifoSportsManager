package org.example.footballmanager.newLogic.fantasy;

import java.util.List;

/**
 * A country's fantasy pool, priced.
 *
 * <p>{@link #tierCount} is how many tiers the country's pyramid actually has, and it is the eligibility
 * test: the per-tier rule is three players from each tier, and three times five is a squad while three
 * times four is not. Carried here rather than recomputed by every caller so "why can't I play Serbia's
 * fantasy league" has one answer.
 */
public record FantasyPool(Long countryId, int tierCount, List<FantasyPoolPlayer> players) {

    /** True when the pyramid has exactly the tiers the squad rules need. */
    public boolean eligible() {
        return tierCount == FantasyRules.TIERS_REQUIRED;
    }

    /** Why this country cannot host a fantasy league, or null when it can. */
    public String ineligibleReason() {
        if (eligible()) {
            return null;
        }
        return "This country's pyramid has " + tierCount + " tier(s) and the fantasy rules need exactly "
                + FantasyRules.TIERS_REQUIRED + " — three players from each tier has to add up to "
                + FantasyRules.SQUAD_SIZE + ".";
    }
}
