package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Junior;

/**
 * How wide a junior's reported talent range is, and how that narrows (Sprint 5, S5.2).
 *
 * <p><b>Why this exists.</b> A junior arrived with his true {@code talent} on day one, and it was
 * handed to the browser in {@code JuniorAcademyItemDTO} for every caller. A manager could sort the
 * academy by number on the first morning and never have to look at a player. The owner's ruling is
 * that what you are entitled to see is an <b>estimate that firms up</b>: wide on arrival, tight by
 * promotion, exact on the day he graduates.
 *
 * <p><b>Owner's specification, 2026-09-27:</b>
 * <ul>
 *   <li>half-width at intake is {@code ±(1 + rnd(0..3))} — between ±1 and ±4, rolled per junior;</li>
 *   <li>it narrows to <b>±1</b> (with decimals) by promotion;</li>
 *   <li>a better {@code YOUTH_COACH} narrows it <b>faster</b>;</li>
 *   <li>on promotion the exact talent is revealed.</li>
 * </ul>
 *
 * <p><b>This class never touches the true value.</b> It is handed a range and produces a range. The
 * caller decides whether the viewer is entitled to see one at all — that gate is
 * {@code PlusFeatureService}'s job and it is a separate axis (subscription versus uncertainty). Keeping
 * the two apart is the point: a range shown to someone who may not see talent is not a narrower leak,
 * it is the same leak.
 *
 * <p>Pure and static on purpose. The arithmetic is the part of this feature most likely to be
 * "improved" later by someone who does not know why it is shaped the way it is, and it is much easier
 * to defend in a unit test than inside a service with a dozen dependencies.
 */
public final class TalentRange {

    /** The half-width a report converges to. The owner's number, and the tightest it ever gets. */
    public static final double FINAL_HALF_WIDTH = 1.0;

    /** The floor of the intake roll, before the random part. */
    private static final double BASE_HALF_WIDTH = 1.0;

    /** The largest random addition to the intake half-width, so intake spans ±1 to ±4. */
    private static final int RANDOM_HALF_WIDTH = 3;

    /**
     * How much a perfect youth coach accelerates the narrowing, on top of the natural rate.
     *
     * <p>A development attribute of 1 leaves narrowing essentially at its natural pace; 20 doubles it.
     * The academy does not produce better prospects — it <b>knows what it has, sooner</b>, which is
     * what gives {@code YOUTH_COACH.development} its first consumer in the codebase.
     */
    private static final double MAX_COACH_SPEEDUP = 1.0;

    /**
     * The lowest and highest talent a player can actually have.
     *
     * <p>Junior talent is rolled on a <b>1-10 integer</b> scale ({@code rollTalent}'s own weights say
     * "1..10"), so this is not a guess about a convention — it is the size of the array being rolled.
     */
    public static final int MIN_TALENT = 1;
    public static final int MAX_TALENT = 10;

    private TalentRange() {
    }

    /**
     * Rolls the intake half-width for a newly signed junior: {@code 1 + rnd(0..3)}.
     *
     * <p>Rolled once and stored on the junior, because a range that re-rolled every week would be
     * theatre — the report would change because it was re-drawn, not because anything was learned.
     *
     * @param roll a value in {@code [0, RANDOM_HALF_WIDTH]}, supplied by the caller so this class
     *             stays deterministic and testable
     */
    public static double intakeHalfWidth(int roll) {
        int bounded = Math.max(0, Math.min(RANDOM_HALF_WIDTH, roll));
        return BASE_HALF_WIDTH + bounded;
    }

    /** The largest half-width a report can ever show. Used to clamp a stored value from bad data. */
    public static double maxHalfWidth() {
        return intakeHalfWidth(RANDOM_HALF_WIDTH);
    }

    /**
     * The half-width to report for a junior right now.
     *
     * @param junior          the junior being reported on; supplies the intake width and the ages
     * @param currentAge      his age today (it moves once a year at the season boundary)
     * @param graduationAge   the age he graduates at, from {@code YouthAcademyService.graduationAge}
     * @param youthCoachDevelopment the {@code YOUTH_COACH}'s development attribute, 1-20, or null
     * @return the half-width, never below {@link #FINAL_HALF_WIDTH} and never above
     *         {@link #maxHalfWidth()}
     */
    public static double currentHalfWidth(Junior junior, int currentAge, int graduationAge,
                                          Integer youthCoachDevelopment) {
        double intake = intakeWidthOf(junior);
        double finalWidth = FINAL_HALF_WIDTH;
        if (intake <= finalWidth) {
            return finalWidth;
        }

        double progress = observationProgress(junior, currentAge, graduationAge);
        double speed = coachSpeedup(youthCoachDevelopment);
        // Squashing progress rather than scaling it: a coach can make a report converge faster, but
        // must never make it *wider* than the natural rate would, nor skip past the ±1 floor.
        double remaining = Math.max(0.0, 1.0 - progress * speed);
        double width = finalWidth + (intake - finalWidth) * remaining;
        return round2(clamp(width, finalWidth, intake));
    }

    /**
     * The lower and upper bound to show, rounded to two decimals per the house rule.
     *
     * @return {@code {low, high}}, or {@code null} when there is no true value to centre on — the
     *         caller's cue that it must not render a range at all
     */
    public static double[] bounds(double trueTalent, double halfWidth) {
        if (Double.isNaN(trueTalent) || Double.isInfinite(trueTalent)) return null;
        double width = Math.max(0.0, halfWidth);
        // Clamped to the scale, deliberately and at the cost of a small leak.
        //
        // A talent of 10 reported at +/-1 is 9-11, and no player has a talent of 11. Showing it anyway
        // is what a manager saw on the academy screen, and it reads as a broken number rather than a
        // wide estimate. Clamping costs something real: a band that ends at 10 tells you the true value
        // is high, so an exceptional prospect gives himself away at the edges of the scale. That is
        // the trade every scouting report makes, and the alternative -- impossible numbers on screen --
        // is worse and would be reported as a bug.
        double low = Math.max(MIN_TALENT, trueTalent - width);
        double high = Math.min(MAX_TALENT, trueTalent + width);
        // A talent stored outside the scale (a legacy row, a bad migration) would invert the band.
        if (low > high) {
            low = Math.max(MIN_TALENT, Math.min(MAX_TALENT, trueTalent));
            high = low;
        }
        return new double[] { round2(low), round2(high) };
    }

    /**
     * The exact talent, for the moment it is revealed.
     *
     * <p>An <b>integer</b>, because that is what the roll produces. The owner asked for "exact talent
     * with decimals"; the roll is a weighted integer on a 1-10 scale, and re-rolling it as a double
     * would move the graduation distribution, which the same owner ruled must not move. Showing the
     * integer is the honest reading of both decisions at once.
     */
    public static Double revealExact(double trueTalent) {
        if (Double.isNaN(trueTalent) || Double.isInfinite(trueTalent)) return null;
        return (double) (int) Math.round(trueTalent);
    }

    /**
     * How much of the observation period has elapsed, 0..1.
     *
     * <p>Measured in <b>ages</b>, not weeks. A junior's age is the only clock in the academy that moves
     * on its own, so a report that narrowed by week would be narrowing during a season in which
     * nothing about the player changed.
     *
     * <p>Two degenerate cases, and they deliberately do <b>not</b> get the same answer:
     *
     * <ul>
     *   <li><b>No arrival age recorded</b> → {@code 0}. We do not know how long the club has been
     *       watching, and the honest report is the widest one. Returning "fully observed" here would
     *       hand every pre-existing junior in the database a confident ±1 he has not earned.</li>
     *   <li><b>Arrived at or after his graduation age</b> → {@code 1}. The observation period is
     *       zero-length, so there is nothing to narrow and the report is already at its floor. This is
     *       the branch that stops a division by zero.</li>
     * </ul>
     */
    static double observationProgress(Junior junior, int currentAge, int graduationAge) {
        Integer arrivalAge = junior == null ? null : junior.getArrivalAge();
        if (arrivalAge == null) return 0.0;
        if (graduationAge <= arrivalAge) return 1.0;
        double elapsed = currentAge - arrivalAge;
        double span = graduationAge - arrivalAge;
        return clamp(elapsed / span, 0.0, 1.0);
    }

    /** The coach's acceleration, 1.0 (no help) to 2.0 (a perfect youth coach halves the wait). */
    static double coachSpeedup(Integer development) {
        if (development == null) return 1.0;
        int bounded = Math.max(1, Math.min(20, development));
        return 1.0 + MAX_COACH_SPEEDUP * (bounded - 1) / 19.0;
    }

    private static double intakeWidthOf(Junior junior) {
        Double stored = junior == null ? null : junior.getTalentRangeHalfWidth();
        if (stored == null || stored.isNaN() || stored.isInfinite()) {
            // Unrecorded is not zero, and it is not "fully observed" either. The roll never happened
            // for this junior, so the report starts at the widest the feature allows and narrows from
            // there. A pre-existing academy would otherwise be full of certainties nobody earned.
            return maxHalfWidth();
        }
        return clamp(stored, FINAL_HALF_WIDTH, maxHalfWidth());
    }

    static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static double clamp(double value, double min, double max) {
        if (Double.isNaN(value)) return min;
        return Math.max(min, Math.min(max, value));
    }
}
