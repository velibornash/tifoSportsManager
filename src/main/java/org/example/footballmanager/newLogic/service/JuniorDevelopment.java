package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Junior;
import org.example.footballmanager.newLogic.model.Personality;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.PreferredFoot;
import org.example.footballmanager.newLogic.model.Stadium;

/**
 * What a junior is made of, and how his body changes while he is in the school (Sprint 5.3).
 *
 * <p><b>The premise.</b> A prospect used to be a talent band, one ability figure and a weekly delta —
 * so two seventeen-year-olds of identical talent developed identically, and a fifteen-year-old's body
 * did not move at all between intake and graduation. In football, the things you scout a teenager on
 * are not only ability: it is whether he is professional, whether he works, whether he is a pain in the
 * changing room, and whether the body he arrived with is the body he will play with.
 *
 * <h2>Height barely moves; weight is corrected</h2>
 * That is the owner's split, and it is the correct one physiologically. A fifteen-year-old is still
 * growing; a nineteen-year-old is finished. So height gains are <b>tapering with age</b> and approach
 * nothing by the end of the window.
 *
 * <p>Weight is different because it is not a growth question but a <b>conditioning</b> one. A junior
 * has a {@link Junior#getNaturalWeight() natural weight} — the body he was given — and a
 * {@link Junior#getWeight() current weight}. The current figure drifts weekly, and the
 * {@code GYM} facility sets how fast the club can pull it back toward the natural figure.
 *
 * <p><b>The gym does not give you a better body; it lets you correct the one you got.</b> A club
 * without one simply lets a heavy prospect stay heavy, which is a risk the manager accepted by not
 * spending the money. That is the whole point of tying it to a facility that already exists and is
 * already paid for.
 *
 * <p>Pure and static, like {@link AcademyQuality}, because this arithmetic is the part most likely to
 * be "simplified" later by someone who does not know why the age taper exists.
 */
public final class JuniorDevelopment {

    /** The range a rolled height lands in, in centimetres. Wide: fifteen-year-olds vary enormously. */
    static final double MIN_HEIGHT_CM = 158.0;
    static final double MAX_HEIGHT_CM = 200.0;

    /** The range a rolled weight lands in, in kilograms. */
    static final double MIN_WEIGHT_KG = 50.0;
    static final double MAX_WEIGHT_KG = 92.0;

    /** How far a fifteen-year-old may still grow in a season, before the age taper. */
    static final double MAX_SEASONAL_HEIGHT_GAIN_CM = 4.5;

    /** How far the current weight may move toward the natural weight in one week, at a perfect gym. */
    static final double MAX_WEEKLY_WEIGHT_CORRECTION_KG = 0.9;

    /** Inside this distance of the target the club stops correcting, so the correction cannot oscillate. */
    static final double DEAD_BAND_KG = 0.5;

    /**
     * How much of a goalkeeper's height is forced toward the tall end, and a striker's toward the
     * short one.
     *
     * <p>Small, and it is a nudge rather than a rule: a "short" striker is still allowed to be tall,
     * and a goalkeeper who is 178cm is still a goalkeeper. Positions bias the roll, they do not
     * constrain it.
     */
    static final double POSITION_HEIGHT_BIAS = 0.12;

    private JuniorDevelopment() {
    }

    /** A height appropriate to the position, in centimetres. */
    public static double rollHeight(Position position, double unitRandom) {
        double base = MIN_HEIGHT_CM + unitRandom * (MAX_HEIGHT_CM - MIN_HEIGHT_CM);
        double bias = switch (position == null ? Position.MID : position) {
            case GK -> POSITION_HEIGHT_BIAS;
            case DEF -> POSITION_HEIGHT_BIAS * 0.4;
            case MID -> 0.0;
            // Wingers are the small outfielders, and the bias is the reason: a 6'4" winger is a
            // curiosity, not a winger. WNG is in the enum and the compiler said so.
            case WNG -> -POSITION_HEIGHT_BIAS * 0.7;
            case ATT -> -POSITION_HEIGHT_BIAS * 0.4;
        };
        return clamp(base * (1.0 + bias), MIN_HEIGHT_CM, MAX_HEIGHT_CM);
    }

    /**
     * A body that matches the height, then a natural weight around it.
     *
     * <p>Weight is derived from height first, so a tall junior is not implausibly light. The extra
     * spread is what gives the gym something to correct — if every prospect started at his natural
     * weight there would be nothing for a conditioning programme to do.
     */
    public static double[] rollBody(double heightCm, double unitRandom) {
        double centre = 46.0 + (heightCm - MIN_HEIGHT_CM) * 0.62;
        double spread = (unitRandom - 0.5) * 2.0 * 7.0;
        return new double[] { clamp(centre + spread, MIN_WEIGHT_KG, MAX_WEIGHT_KG) };
    }

    /**
     * How much height this junior may still gain this season, in centimetres.
     *
     * <p>Tapers to <b>nothing</b> by the graduation deadline. A nineteen-year-old in an academy is
     * finished growing, and pretending otherwise would hand a club a centre-half who is still
     * sprouting at twenty.
     *
     * @param yearsSinceArrival how many seasons he has been in the academy, <b>not his age</b>.
     *        Named explicitly because the first version of this took an {@code age} and read
     *        {@code graduationAge - age}, which is a different number entirely and made every
     *        fifteen-year-old grow as much as a nineteen-year-old.
     * @param graduationAge the age he leaves at, inside the 15-20 window
     */
    public static double seasonalHeightGain(int yearsSinceArrival, int graduationAge, double unitRandom) {
        // Clamped at zero first. A negative tenure is nonsense, and without this it produced the
        // *maximum* growth of the whole scale, because a junior who arrived before he was born has
        // more years of growth left than any real one.
        int tenure = Math.max(0, yearsSinceArrival);
        int remaining = Math.max(0, graduationAge - (arrivalAgeBaseline() + tenure));
        if (remaining <= 0) return 0.0;
        // Linear taper: the younger he is, the more is left. At the deadline it is exactly zero.
        double fraction = Math.min(1.0, remaining / (double) JuniorAgeWindow.SPAN);
        // Clamped, because the jitter factor can exceed 1.0 and without this the "maximum" constant
        // was not the maximum -- a fifteen-year-old could gain 6.3cm in a season, which is a growth
        // spurt and not a modelling error.
        return clamp(MAX_SEASONAL_HEIGHT_GAIN_CM * fraction * (0.6 + 0.8 * unitRandom),
                0.0, MAX_SEASONAL_HEIGHT_GAIN_CM);
    }

    /**
     * The age a junior is assumed to arrive at, used only to turn "years in the academy" back into
     * "years of growth left". Tied to the graduation window's floor so the two cannot drift apart.
     */
    private static int arrivalAgeBaseline() {
        return org.example.footballmanager.newLogic.service.YouthAcademyService.GRADUATION_MIN_AGE;
    }

    /**
     * The weekly change in weight, in kilograms — negative when the club is pulling him back toward
     * his natural weight.
     *
     * @param gymLevel the club's {@code GYM} facility level, 1-20, or null when unrecorded
     * @return at most {@link #MAX_WEEKLY_WEIGHT_CORRECTION_KG} toward the target, in either direction
     */
    public static double weeklyWeightChange(double current, double natural, Integer gymLevel,
                                           double unitRandom) {
        double gap = natural - current;
        // A dead band wide enough to stop the correction oscillating. The first version used 0.05kg,
        // so a prospect 0.4kg from his target had 0.27kg taken off him in one week and would have
        // spent the next one putting it back on.
        if (Math.abs(gap) < DEAD_BAND_KG) return 0.0;
        double ability = gymLevel == null ? 0.5 : Math.max(0.0, Math.min(1.0, (gymLevel - 1) / 19.0));
        // A <b>fixed</b> weekly step scaled by the gym, not a fraction of the gap.
        //
        // The gap-proportional version was wrong twice over: on a 24kg gap it moved 28kg in a single
        // week, and capping it then made the gym irrelevant -- a poor gym and a good one both moved
        // 0.9kg and the difference disappeared exactly when the gap was large enough to care. A fixed
        // step means a twenty-kilo correction is about a season at a good gym and rather longer
        // without one, which is what a physique programme actually looks like.
        double step = MAX_WEEKLY_WEIGHT_CORRECTION_KG * (0.25 + 0.75 * ability) * (0.7 + 0.6 * unitRandom);
        // A club can build a prospect up faster than it can strip fat off him, which is also true of
        // real conditioning programmes.
        if (gap < 0) step *= 0.75;
        // Never past the target: overshooting turns a correction into an oscillation.
        return clamp(Math.signum(gap) * step, -Math.abs(gap), Math.abs(gap));
    }

    /**
     * The growth multiplier from effort and temperament together.
     *
     * <p>Multiplied, not added, for the same reason as {@link AcademyQuality}: a hardworking
     * Temperamental and a laid-back professional should land in the same place, because neither trait
     * rescues the other.
     */
    public static double growthFactor(Integer workRate, Personality personality) {
        double effort = workRate == null ? 1.0 : 0.80 + 0.40 * ((Math.max(1, Math.min(20, workRate)) - 10) / 10.0);
        return clamp(effort * Personality.orDefault(personality).growthFactor(), 0.60, 1.55);
    }

    /**
     * How far this junior's weekly development may wander from its expected figure.
     *
     * <p>Proportional, so a Temperamental is erratic <i>week to week</i> rather than erratic in
     * absolute terms — which is what a difficult player actually is.
     */
    public static double growthVariance(Personality personality) {
        return Personality.orDefault(personality).variance();
    }


    /**
     * A work rate rolled across the <b>whole 1-20 scale</b>, bell-shaped.
     *
     * <p>The first version produced only 6-15, which meant a 15/20 worker was the best the roll could
     * ever give and a 5/20 idler could not exist — the attribute was on a 1-20 scale and used ten
     * values of it. Bell-shaped so the extremes are real but rare: a fifteen-year-old who works like
     * a twenty-year-old is a scouting find, not the default.
     */
    public static int rollWorkRate(double unitRandom) {
        // Average two draws to get a bell without needing a normal generator, then stretch it across
        // the scale so a roll of 0.5 lands in the middle rather than at a quarter.
        double a = clamp(unitRandom, 0.0, 0.9999);
        double b = clamp((a * 7919.0) % 1.0, 0.0, 0.9999);
        double bell = (a + b) / 2.0;
        int value = (int) Math.round(1 + bell * 19.0);
        return Math.max(1, Math.min(20, value));
    }

    /**
     * Personality, weighted so the awkward ones are a minority.
     *
     * <p>Uniform would make half the academy difficult to manage, which is not a feature — it is a
     * distribution nobody would accept in a real squad. Temperamental and Headstrong together are
     * under a third.
     */
    public static Personality rollPersonality(double unitRandom) {
        double roll = unitRandom;
        if (roll < 0.34) return Personality.PROFESSIONAL;
        if (roll < 0.60) return Personality.AMBITIOUS;
        if (roll < 0.76) return Personality.TEMPERAMENTAL;
        if (roll < 0.90) return Personality.LAID_BACK;
        return Personality.HEADSTRONG;
    }

    /** A preferred foot, with {@link PreferredFoot#BOTH} rare on purpose. */
    public static PreferredFoot rollFoot(double unitRandom) {
        if (unitRandom < 0.10) return PreferredFoot.BOTH;
        return unitRandom < 0.55 ? PreferredFoot.LEFT : PreferredFoot.RIGHT;
    }

    /**
     * The convenience for the "should a weekly roll skip a body update" case.
     *
     * <p>Present only so the age window has one definition. It is a window, not a class — the two
     * constants belong together and someone will otherwise tune one of them.
     */
    static final class JuniorAgeWindow {
        static final int SPAN = 5;

        private JuniorAgeWindow() {
        }
    }

    static double clamp(double value, double min, double max) {
        if (Double.isNaN(value)) return min;
        return Math.max(min, Math.min(max, value));
    }

    static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** The gym level of a club's ground, or null when it has no recorded stadium. */
    static Integer gymLevelOf(Stadium stadium) {
        return stadium == null ? null : stadium.getGymLevel();
    }
}
