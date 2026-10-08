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
 * <p><b>Owner, 2026-10-08 — narrowing now runs on the season, not on the age.</b> Progress used to
 * be measured in ages, over the span {@code graduationAge - arrivalAge}. That worked only because
 * tenure was long: one to five seasons, so an age moved once a year and the band crept. Tenure is now
 * exactly one season, which collapses an age-based span to almost nothing — a nineteen-year-old
 * spanned zero ages and sat at ±1 from arrival, handing over the exact ceiling for free. The clock is
 * therefore the <b>weeks of the tenure</b>, and the coach narrows week by week, which is what the
 * owner asked for: the estimate firms up <i>while the season is running</i>.
 *
 * <p><b>This class never touches the true value.</b> It is handed a range and produces a range. The
 * caller decides whether the viewer is entitled to see one at all — that gate is
 * {@code PlusFeatureService}'s job and it is a separate axis (subscription versus uncertainty). Keeping
 * the two apart is the point: a range shown to someone who may not see talent is not a narrower leak,
 * it is the same leak.
 *
 * <p>Pure and static on purpose, and it stays that way: the calendar arithmetic lives in
 * {@code YouthAcademyService.weeksObserved}, so this class knows two numbers and no seasons. The
 * arithmetic is the part of this feature most likely to be "improved" later by someone who does not
 * know why it is shaped the way it is, and it is much easier to defend in a unit test than inside a
 * service with a dozen dependencies.
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
 * @param junior               the junior being reported on; supplies the intake width
 * @param weeksObserved        weeks of the tenure behind the club, as the caller counts them
 * @param tenureWeeks          the length of the whole tenure
 * @param youthCoachDevelopment the {@code YOUTH_COACH}'s development attribute, 1-20, or null
 * @return the half-width, never below {@link #FINAL_HALF_WIDTH} and never above
 *         {@link #maxHalfWidth()}
 */
    public static double currentHalfWidth(Junior junior, int weeksObserved, int tenureWeeks,
                                          Integer youthCoachDevelopment) {
        double intake = intakeWidthOf(junior);
        double finalWidth = FINAL_HALF_WIDTH;
        if (intake <= finalWidth) {
            return finalWidth;
        }

        double progress = observationProgress(weeksObserved, tenureWeeks);
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
 * How much of the tenure has been observed, 0..1.
 *
 * <p>Measured in <b>weeks of the tenure</b> — arrival to the decision week. It was ages, and with
 * tenure now fixed at one season an age spans either zero or one step, so the estimate would have
 * jumped straight from widest to ±1 and never crept. Weeks are also the honest clock: the coach
 * watches the player train for ten of them, and the eleventh is the morning the manager signs him.
 *
 * <p>Both degenerate cases collapse to "no progress" rather than dividing by zero:
 *
 * <ul>
 *   <li><b>A tenure length of zero or less</b> → {@code 0}. Nothing to measure against, so nothing is
 *       claimed. Returning "fully observed" here would hand every caller a confident ±1.</li>
 *   <li><b>More weeks observed than the tenure has</b> → {@code 1}. A junior nobody resolved is still
 *       resolved eventually, and by then he is as known as he is ever going to get.</li>
 * </ul>
     *
 * @param weeksObserved weeks of the tenure behind the club, never negative from the caller
 * @param tenureWeeks   the length of the whole tenure in weeks
 */
    static double observationProgress(int weeksObserved, int tenureWeeks) {
        if (tenureWeeks <= 0) return 0.0;
        return clamp((double) weeksObserved / tenureWeeks, 0.0, 1.0);
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
