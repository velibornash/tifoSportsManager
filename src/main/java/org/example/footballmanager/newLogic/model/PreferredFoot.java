package org.example.footballmanager.newLogic.model;

/**
 * Which foot a player prefers (Sprint 5.3, owner 2026-09-27).
 *
 * <p>Rolled at intake, shown on the academy profile, and <b>carried into the senior player</b> — so a
 * right-footed goalkeeper does not arrive in the senior squad as a lefty. Sprint 4.5 already lets
 * height and weight influence heading, strength and pace; without a foot, a third of a player's
 * physical profile was missing at the moment it started to matter.
 *
 * <p>{@link #BOTH} is the most valuable state and the rarest, because it is a real scouting
 * distinction: a player who can genuinely play either side is worth more than the sum of his two feet.
 */
public enum PreferredFoot {

    LEFT("Left"),
    RIGHT("Right"),
    /** Comfortable with both. Rare on purpose — see the class comment. */
    BOTH("Both");

    private final String label;

    PreferredFoot(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static PreferredFoot orDefault(PreferredFoot stored) {
        return stored == null ? RIGHT : stored;
    }
}
