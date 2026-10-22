package org.tifo.footballmanager.newLogic.model;

/**
 * How hard a club or a player works (Sprint 4.2).
 *
 * <p>This is the missing half of the training loop. Growth existed with no cost, so pushing every
 * player every week was free and the only sensible thing to do — which is the same reason a game with
 * no downside has one strategy. The whole point is that {@code VERY_HARD} genuinely beats
 * {@code NORMAL} on a rested player and genuinely punishes you on a tired one.
 *
 * <p>Every tier trades growth for fatigue. None of them is free, which is the design.
 */
public enum TrainingIntensity {

    /**
     * A pre-season or an injury rehabilitation. Barely trains, barely tires.
     *
     * <p>Growth is low but non-zero on purpose: a squad that is switched off for a week should
     * coast, not go backwards.
     */
    LIGHT(0.75, 4, 0.0),

    /** The default. What a club does when nobody has an opinion. */
    NORMAL(1.00, 12, 0.004),

    /**
     * The best week a player can have, and the most expensive one.
     *
     * <p>Only as safe as the player is rested. The injury chance here is deliberately non-zero even
     * for a fresh player, because a programme that can always hurt you is a programme nobody will
     * ever run, and one that never can is not a decision.
     */
    VERY_HARD(1.35, 26, 0.045);

    private final double growthMultiplier;
    private final int weeklyFatigue;
    private final double baseInjuryChance;

    TrainingIntensity(double growthMultiplier, int weeklyFatigue, double baseInjuryChance) {
        this.growthMultiplier = growthMultiplier;
        this.weeklyFatigue = weeklyFatigue;
        this.baseInjuryChance = baseInjuryChance;
    }

    public double growthMultiplier() {
        return growthMultiplier;
    }

    /**
     * How fast a player's form comes back under this much training.
     *
     * <p><b>This is the manager's lever over a player in a bad run.</b> The form settles a quarter of
     * the way back to its natural level every week, and the settle is where a player who is out of
     * form gets his way back. Light work is what a club does with a player it is nursing - the
     * enum's own description is <em>"a pre-season or an injury rehabilitation"</em> - so a light week
     * <b>doubles</b> the recovery, and a hard week <b>halves</b> it, because a week of maximum
     * training does not leave a player sharper, it leaves him tired.
     *
     * <p>The owner's words: <em>"razmisli kako da korisnik moze da utice na igracevu formu,
     * pogotovo nisku"</em> - think about how the user can affect a player's form, especially a low
     * one. Playing him is the first answer; how hard the club trains him is the second.
     */
    public double formRecoveryMultiplier() {
        return switch (this) {
            case LIGHT -> 2.0;
            case NORMAL -> 1.0;
            case VERY_HARD -> 0.5;
        };
    }

    /** Fatigue added per training week, before the player's own condition is considered. */
    public int weeklyFatigue() {
        return weeklyFatigue;
    }

    /** The chance a rested player is hurt by this much work in a week. */
    public double baseInjuryChance() {
        return baseInjuryChance;
    }

    /**
     * The chance this player is hurt by this much work this week.
     *
     * <p>Rises steeply with existing fatigue, because that is the real mechanic: pushing a tired
     * player is what gets people hurt. A 90-fatigue player on {@code VERY_HARD} is not a small
     * number above the base rate, he is most of a certainty, and the only answer is to back off.
     */
    public double injuryChance(int currentFatigue) {
        double fatigueAbove = Math.max(0.0, currentFatigue - 55) / 45.0;
        return baseInjuryChance + (baseInjuryChance * 6.0) * fatigueAbove;
    }

    /** The closest tier, or null if the name is not one. */
    public static TrainingIntensity byName(String name) {
        if (name == null || name.isBlank()) return null;
        // As forgiving as the training screen actually is. It will send "Very Hard", "very hard"
        // and "very-hard" depending on how the option was typed, and a settings value that only
        // accepts one of them reads to a manager as the setting being broken.
        String clean = name.trim().toUpperCase(java.util.Locale.ROOT)
                .replace(' ', '_').replace('-', '_');
        for (TrainingIntensity intensity : values()) {
            if (intensity.name().equals(clean)) {
                return intensity;
            }
        }
        return null;
    }
}
