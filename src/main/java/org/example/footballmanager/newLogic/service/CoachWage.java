package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.model.StaffMember;
import org.example.footballmanager.newLogic.model.StaffRole;

/**
 * What a coach costs, and why (owner, 2026-09-27).
 *
 * <p>The owner made the wage a function of the coach's <b>skills</b>, not of a single "development"
 * number or the club's reputation: a coach's pay is driven by the sum of his eight ratings plus the
 * number of skills he is maxed at.
 *
 * <p>That produces the trade-off the owner described — a cheap coach who is excellent at the two
 * skills you actually train, or a dear one who can rotate across all eight. A generalist is not
 * merely "a specialist with better numbers": the maxed-skill premium is what pays for the freedom to
 * run any programme without working around a weakness.
 */
public final class CoachWage {

    private CoachWage() { }

    /** What one point of skill across the eight is worth per week. */
    public static final double PER_SKILL_POINT = 145.0;

    /**
     * What one maxed skill is worth on top of the sum.
     *
     * <p>Large on purpose. Without it, a coach with two 20s and six 1s would cost more than a coach
     * with four 20s and four 1s, which is backwards: the second coach can keep a whole squad
     * improving and the first can only help two programmes.
     */
    public static final double PER_MAXED_SKILL = 1_150.0;

    /**
     * A floor per role, so a weak coach is cheap rather than free.
     *
     * <p>Hiring is priced by the club tier as well, because a scout is worth more to a small club
     * with ambition than to a giant that already knows everyone.
     */
    private static final double[] ROLE_FLOOR = {
            4_200,   // HEAD_COACH
            1_900,   // ASSISTANT
            1_600,   // GK_COACH
            1_800,   // PHYSIO
            1_700,   // SCOUT
            1_500,   // YOUTH_COACH
    };

    /**
     * The weekly wage implied by a coach's skills.
     *
     * @param coach     the coach
     * @param tier      the club's tier, 1 at the top; a top club pays more for the same man
     * @param reputation 0-100, used only to scale what the club will pay, never the coach's ability
     */
    public static double weeklyWageFor(StaffMember coach, int tier, double reputation) {
        if (coach == null) return 0;

        double skillPay = coach.coachSkillSum() * PER_SKILL_POINT;
        double masteryPay = coach.maxedSkillCount() * PER_MAXED_SKILL;
        double floor = roleFloor(coach.getRole());

        // A club at the top of the pyramid, and a club with money, can outbid one without. This is
        // the only place reputation touches pay, and it never touches what the coach can do.
        double clubWeight = 0.75 + (Math.max(1, Math.min(5, tier)) <= 2 ? 0.25 : 0.0)
                + (reputation / 100.0) * 0.35;

        return round2(Math.max(floor, (skillPay + masteryPay) * clubWeight));
    }

    private static double roleFloor(StaffRole role) {
        if (role == null) return ROLE_FLOOR[0];
        int index = Math.max(0, Math.min(ROLE_FLOOR.length - 1, role.ordinal()));
        return ROLE_FLOOR[index];
    }

    /**
     * A short explanation of the wage, for the staff screen.
     *
     * <p>Shown because a manager looking at two candidates needs to see <i>why</i> one costs more,
     * and "sum of skills" is only useful if the number is on the page.
     */
    public static String explain(StaffMember coach) {
        if (coach == null) return "";
        return "Sum of skills " + coach.coachSkillSum()
                + " x " + round2(PER_SKILL_POINT)
                + " plus " + coach.maxedSkillCount() + " maxed skill(s) x " + round2(PER_MAXED_SKILL);
    }

    /** How well this coach can teach one skill, 0..1, for the training percentage. */
    public static double teachingAbility(StaffMember coach, SkillName skill) {
        if (coach == null) return 0;
        return coach.coachRating(skill) / 20.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
