package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.PlayerRole;
import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.model.StaffMember;
import org.example.footballmanager.newLogic.model.StaffRole;

/**
 * How much of a week's training a player actually gets (owner, 2026-09-27).
 *
 * <h2>The rule</h2>
 * A player gets <b>100%</b> of a week's training only when all three of these are maximal:
 * <ol>
 *   <li>his <b>talent</b>,</li>
 *   <li>his coach's rating <b>for the skill being trained</b>,</li>
 *   <li>and he has played <b>120 minutes or more</b> that week, in anything - league, cup,
 *       European, national team or friendly.</li>
 * </ol>
 * Anything short of maximal reduces it proportionally, and the owner's word for the shape of that
 * reduction was <i>not dramatic, a lazy curve</i>.
 *
 * <h2>Why a weighted mean and not a product</h2>
 * The obvious implementation multiplies the three factors. It is also the wrong one, and badly so:
 * three factors at 80% multiply to <b>51%</b>, which is precisely the dramatic collapse the owner
 * ruled out. Sharing a budget of 100% instead means three factors at 80% give <b>80%</b> - lazy, and
 * it still requires all three to be maximal for the full hundred.
 *
 * <h2>What this number is not</h2>
 * It is <b>not</b> affected by how good the player already is, or how old he is. Those change the
 * <i>fragment</i> gained — the actual skill points — and live in {@code TrainingGrowth}. Keeping
 * them apart is deliberate: a 34-year-old at 20/20 skills and a 19-year-old at 2/20 can both be
 * getting 100% of the training, and the difference between them shows up as how much those points
 * are worth.
 */
public final class TrainingPercent {

    private TrainingPercent() { }

    /** Talent's share of the budget. Raw ability to learn. */
    public static final double WEIGHT_TALENT = 0.40;
    /** The coach's share. He supports development; he does not limit it. */
    public static final double WEIGHT_COACH = 0.35;
    /** Minutes' share. Match sharpness. */
    public static final double WEIGHT_MINUTES = 0.25;

    /** Talent is 1-10 today. Normalising by the maximum means the formula does not care. */
    public static final double MAX_TALENT = 10.0;

    /** Skills are 1-20, for players and coaches alike. */
    public static final double MAX_SKILL = 20.0;

    /** Minutes in a week for the full effect. */
    public static final int MINUTES_FOR_FULL_EFFECT = 120;

    /** What a player who never plays still gets, as a share of the minutes factor. */
    private static final double MINUTES_FLOOR = 0.30;

    /** The three curves. All concave, all "lazy" in the owner's sense. */
    private static final double TALENT_EXPONENT = 0.75;
    private static final double COACH_EXPONENT = 0.50;
    private static final double MINUTES_EXPONENT = 0.70;

    /**
     * The percentage of full training one player gets this week, 0-100.
     *
     * @param talent      the player's talent, on whatever scale the game uses
     * @param coachRating the coach's rating for the skill being trained, 1-20
     * @param minutes     minutes played this week, in any competition
     */
    public static double percent(double talent, double coachRating, int minutes) {
        return 100.0 * (WEIGHT_TALENT * talentFactor(talent)
                + WEIGHT_COACH * coachFactor(coachRating)
                + WEIGHT_MINUTES * minutesFactor(minutes));
    }

    /** Convenience for the common case, reading talent and the coach off the model objects. */
    public static double percentFor(Player player, StaffMember coach, SkillName skill, int minutes) {
        if (player == null) return 0;
        return percent(player.getTalent(),
                coach == null ? 1 : coach.coachRating(skill),
                minutes);
    }

    /**
     * Talent, gently. A talent of 1 is a raw ratio of 0.1, which becomes 0.18 — so even the worst
     * prospect learns something, which is what a 20% rating is supposed to mean.
     */
    public static double talentFactor(double talent) {
        return Math.pow(clamp01(talent / MAX_TALENT), TALENT_EXPONENT);
    }

    /**
     * The coach, flatter than the others.
     *
     * <p>An exponent of 0.5 means a coach rated 10/20 still delivers 71% of his curve. That is on
     * purpose: a mediocre coach should hold a squad together rather than freeze it, because the
     * owner's rule is that nothing below maximum <i>reduces</i> the training, it does not remove it.
     */
    public static double coachFactor(double coachRating) {
        return Math.pow(clamp01(coachRating / MAX_SKILL), COACH_EXPONENT);
    }

    /**
     * Minutes, with the floor the owner asked for.
     *
     * <p>A player who never plays still gets 30% of the minutes factor rather than none, so a squad
     * of fifteen kids who are all forgotten does not freeze solid.
     */
    public static double minutesFactor(int minutes) {
        double ratio = clamp01(minutes / (double) MINUTES_FOR_FULL_EFFECT);
        return MINUTES_FLOOR + (1.0 - MINUTES_FLOOR) * Math.pow(ratio, MINUTES_EXPONENT);
    }

    /**
     * The coach whose rating applies: the head coach, or whoever the club actually employs for it.
     *
     * <p>There is no national-team staff in the game, so a player on international duty is still
     * coached by his club coach. That was the owner's answer to the "whose coach is it for the NT"
     * question and it holds for every competition.
     */
    public static StaffMember coachFor(Iterable<StaffMember> staff) {
        if (staff == null) return null;
        StaffMember fallback = null;
        for (StaffMember member : staff) {
            if (member == null) continue;
            if (member.getRole() == StaffRole.HEAD_COACH) {
                return member;
            }
            // A club with no head coach still has somebody who can teach, so an assistant is
            // better than nobody. Null is not an option: it would read as a rating of 1.
            if (fallback == null && member.getRole() == StaffRole.ASSISTANT) {
                fallback = member;
            }
        }
        return fallback;
    }

    /** The skill a player in this role should be working on, per the owner. */
    public static SkillName primarySkillFor(PlayerRole role) {
        if (role == null) return SkillName.PACE;
        return switch (role) {
            case GOALKEEPER -> SkillName.GOALKEEPER;
            case LEFT_BACK, RIGHT_BACK, LEFT_WING_BACK, RIGHT_WING_BACK,
                 CENTRE_BACK, RIGHT_CENTRE_BACK, DEFENSIVE_MIDFIELDER -> SkillName.DEFENDER;
            case CENTRE_MIDFIELDER, DEEP_PLAYMAKER -> SkillName.PASSING;
            case ATTACKING_MIDFIELDER -> SkillName.PLAYMAKER;
            case WINGER, INSIDE_FORWARD, WIDE_MIDFIELDER -> SkillName.TECHNIQUE;
            case STRIKER, SECOND_STRIKER, FALSE_NINE -> SkillName.STRIKER;
        };
    }

    /** The skill worked on alongside the primary one, or null when a role has no second. */
    public static SkillName secondarySkillFor(PlayerRole role) {
        if (role == null) return null;
        return switch (role) {
            case GOALKEEPER -> null;
            case LEFT_BACK, RIGHT_BACK, LEFT_WING_BACK, RIGHT_WING_BACK -> SkillName.STAMINA;
            case CENTRE_BACK, RIGHT_CENTRE_BACK, DEFENSIVE_MIDFIELDER -> SkillName.PASSING;
            case CENTRE_MIDFIELDER, DEEP_PLAYMAKER -> SkillName.PLAYMAKER;
            case ATTACKING_MIDFIELDER -> SkillName.TECHNIQUE;
            case WINGER, INSIDE_FORWARD, WIDE_MIDFIELDER -> SkillName.PASSING;
            case STRIKER, SECOND_STRIKER, FALSE_NINE -> SkillName.TECHNIQUE;
        };
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) return 0;
        return Math.max(0.0, Math.min(1.0, v));
    }
}
