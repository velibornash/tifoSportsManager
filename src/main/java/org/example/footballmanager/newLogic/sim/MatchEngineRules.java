package org.example.footballmanager.newLogic.sim;

/**
 * Home advantage, stated once (owner, 2026-10-11).
 *
 * <p><b>Why this class exists.</b> The forecaster and the match engine had <b>no home advantage
 * between them</b>. {@code ScheduleInsightService} told a manager that his side had the edge and
 * predicted a home win; the simulation had never heard of the idea, so the same fixture the game had
 * promised at 60% came out at 50% a thousand times in a row and nobody could say why.
 *
 * <p>That is worse than having no forecaster at all. A number a manager is shown and cannot act on is
 * a lie, and the fix is not to soften the forecast — it is for the engine to have the thing the
 * forecast is talking about.
 *
 * <p><b>Two numbers, and they must agree.</b> The first is the effect in OVR, which the forecaster
 * adds to the home side's edge and the engine adds to the home side's effective strength. The second is
 * the effect on expected goals, which is a different scale — goals, not ratings — and is stated here
 * rather than derived, because deriving one from the other would be a conversion nobody has checked.
 *
 * <p>The size: home advantage in real football is worth a few points of win probability, not a
 * guaranteed win. Roughly one to one-and-a-half expected goals across a season's worth of fixtures.
 * 3.0 OVR and 0.12 xG is in that band — enough that a manager can see it and choose his side's
 * advantage accordingly, small enough that it never decides a match on its own.
 */
public final class MatchEngineRules {

    private MatchEngineRules() {
    }

    /** The edge the ground gives the home side, in OVR. Added to the home team's strength. */
    public static final double HOME_ADVANTAGE_OVR = 3.0;

    /** The same edge, in expected goals. */
    public static final double HOME_ADVANTAGE_XG = 0.12;

    /**
     * The same edge, in duel power.
     *
     * <p>Duel power is a weighted sum of skills (technique, pace, defender) on a 0-20 scale, so a squad
     * gap of a few points is a few points of power. {@link #DUEL_SENSITIVITY} is 0.18, which turns a
     * one-point gap into about 52%. A flat 0.9 therefore shifts a home duel from an even contest to
     * roughly 54% - visible over a match, never decisive in one.
     *
     * <p>Stated rather than converted. The conversion factor between OVR, expected goals and duel
     * power is a guess in all three directions, and a named constant that can be checked against a
     * measured home win rate is worth more than a formula nobody can verify.
     */
    public static final double HOME_ADVANTAGE_DUEL_POWER = 0.9;

    /** Whether a team label is the home side. Named once so the engine cannot disagree with itself. */
    public static boolean isHome(String team) {
        return "HOME".equalsIgnoreCase(team);
    }
}
