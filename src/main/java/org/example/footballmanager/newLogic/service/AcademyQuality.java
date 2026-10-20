package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.StaffMember;
import org.example.footballmanager.newLogic.model.Stadium;

/**
 * How good a club's academy is, and what that is worth (Sprint 5.3, owner 2026-09-27).
 *
 * <p>Both inputs already existed and were read by nothing: {@code Stadium.youthLevel} was added in
 * Sprint 4.4 as the youth-facility level, and {@code YOUTH_COACH.development} is a staff attribute
 * whose scouting effect Sprint 4.3 explicitly deferred to this sprint. A manager could buy a training
 * ground and hire a youth coach and watch the academy produce exactly the same prospects as a
 * clubhouse with neither.
 *
 * <h2>What it multiplies: the rate, never the raw material</h2>
 * This scales <b>weekly development</b>, not the talent roll at intake. That is a deliberate
 * constraint, and it is the difference between a feature and a balance change:
 *
 * <ul>
 *   <li>The owner ruled that <b>graduation must not move</b>. If quality scaled the intake roll, it
 *       would move it — a good academy would sign better prospects, which is a different game.</li>
 *   <li>It matches Sprint 4, where facilities and coaches multiply <i>growth</i> and never innate
 *       ability. A good academy does not find better raw material; it develops what it has faster.</li>
 *   <li>It keeps the graduation <b>mechanics</b> intact — who graduates, when, and how many are
 *       unchanged. What changes is the level a graduate <i>reaches</i>, which is the entire point of
 *       spending money on a youth setup.</li>
 * </ul>
 *
 * <h2>The shape</h2>
 * Symmetric around 1.0 and bounded to ±30%, with each input worth at most ±15% on its own. One factor
 * therefore cannot rescue the other: a superb coach in a Portakabin is not a good academy, and this is
 * the same reasoning as the scouting reach model.
 *
 * <p><b>Unrecorded is neutral, not level 1.</b> Every pre-existing club has null in these columns, and
 * reading null as zero would quietly take development off every club in the database — the fourth
 * instance of that trap in this project, and the reason it is written down again here.
 */
public final class AcademyQuality {

    /** A neutral facility and a neutral coach. The midpoint of a 1-20 attribute. */
    private static final int NEUTRAL = 10;

    /** The most either input may contribute on its own, as a fraction. */
    static final double MAX_INPUT_WEIGHT = 0.15;

    /**
     * The floor, so a dire academy still develops somebody.
     *
     * <p>A guard rather than a figure any real input pair produces: level 1 is a deviation of -0.9, so
     * the formula bottoms out at 0.73 and never reaches this. It exists so that raising either input
     * weight later cannot quietly drive a club's development negative.
     */
    public static final double MIN_MULTIPLIER = 0.70;

    /** The ceiling. A superb academy is thirty per cent faster, not twice as fast. */
    public static final double MAX_MULTIPLIER = 1.30;

    private AcademyQuality() {
    }

    /**
     * The growth multiplier for a club's academy.
     *
     * @param youthLevel the {@code YOUTH} facility level, 1-20, or null when never recorded
     * @param coachDevelopment the {@code YOUTH_COACH}'s development attribute, 1-20, or null
     * @return 0.70 to 1.30, and exactly 1.0 when neither is recorded
     */
    public static double multiplier(Integer youthLevel, Integer coachDevelopment) {
        double facility = deviation(youthLevel);
        double coach = deviation(coachDevelopment);
        return clamp(1.0 + MAX_INPUT_WEIGHT * facility + MAX_INPUT_WEIGHT * coach,
                MIN_MULTIPLIER, MAX_MULTIPLIER);
    }

    /** Convenience for the two live lookups, both of which may legitimately be absent. */
    public static double multiplierFor(Stadium stadium, StaffMember youthCoach) {
        return multiplier(
                stadium == null ? null : stadium.getYouthLevel(),
                youthCoach == null ? null : youthCoach.getDevelopment());
    }

    /**
     * How a 1-20 attribute contributes: -1 at level 1, 0 at level 10, +1 at level 20.
     *
     * <p>Null contributes nothing rather than the worst case. A club that has never recorded a youth
     * facility is not thereby running the worst academy in the league.
     */
    static double deviation(Integer level) {
        if (level == null) return 0.0;
        int bounded = Math.max(1, Math.min(20, level));
        return (bounded - NEUTRAL) / 10.0;
    }

    /** A plain reading of the multiplier, so the academy page can explain itself. */
    public static String label(double multiplier) {
        if (multiplier >= 1.20) return "Excellent";
        if (multiplier >= 1.08) return "Good";
        if (multiplier > 0.92) return "Average";
        if (multiplier > 0.80) return "Poor";
        return "Barely a setup";
    }

    static double clamp(double value, double min, double max) {
        if (Double.isNaN(value)) return 1.0;
        return Math.max(min, Math.min(max, value));
    }

    static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
