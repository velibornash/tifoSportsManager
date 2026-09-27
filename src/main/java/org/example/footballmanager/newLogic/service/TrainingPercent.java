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
    /**
     * The best coach a club has <b>for one specific skill</b>.
     *
     * <p>This is the difference between a specialist existing and a specialist mattering. Before
     * this, only the head coach was ever consulted, so a club could hire a goalkeeping coach, pay his
     * wage every week of the season, and see exactly nothing happen — which is worse than not having
     * the role, because it looked like the feature existed.
     *
     * <p>Who may teach what is a deliberate statement about the jobs:
     * <ul>
     *   <li>the head coach and his assistant can teach anything;</li>
     *   <li>a <b>goalkeeping coach</b> teaches goalkeeping and nothing else — if he could also
     *       improve a striker, the role would not need a name;</li>
     *   <li>a <b>physio</b> teaches stamina and condition, and that is the whole of his job;</li>
     *   <li>a <b>youth coach</b> can teach anything, but only a player young enough to be his actual
     *       responsibility — counting him towards a thirty-one-year-old's development would make
     *       hiring him a free upgrade rather than a decision;</li>
     *   <li>a <b>scout</b> is not a coach and is never returned here. He finds players; the growth
     *       he causes is his own recruitment, not his coaching.</li>
     * </ul>
     *
     * <p>The best available rating wins, and a tie goes to the head coach so that hiring anyone never
     * makes a club's coaching worse than it already was.
     */
    public static StaffMember coachForSkill(Iterable<StaffMember> staff, SkillName skill, int playerAge) {
        if (staff == null || skill == null) return null;
        StaffMember best = null;
        int bestRating = Integer.MIN_VALUE;
        for (StaffMember member : staff) {
            if (member == null || !teaches(member.getRole(), skill, playerAge)) continue;
            int rating = member.coachRating(skill);
            if (rating > bestRating) {
                bestRating = rating;
                best = member;
            }
        }
        return best;
    }

    private static int clampSkill(int value) {
        return Math.max(1, Math.min(20, value));
    }

    /** Whether this job can teach that skill to a player of that age. */
    public static boolean teaches(StaffRole role, SkillName skill, int playerAge) {
        if (role == null || skill == null) return false;
        return switch (role) {
            case HEAD_COACH, ASSISTANT -> true;
            case GK_COACH -> skill == SkillName.GOALKEEPER;
            case PHYSIO -> skill == SkillName.STAMINA || skill == SkillName.FATIGUE;
            case YOUTH_COACH -> playerAge < 23;
            case SCOUT -> false;
        };
    }

    /**
     * How much of the work actually lands, from the coach's man-management.
     *
     * <p>Never above 1.0, and that is the point rather than an oversight. The percentage already
     * answers "how much of the budget was spent"; this answers "was it worth spending", and mixing
     * the two would quietly change the training formula that was signed off. So a superb coach does
     * not add anything here — he simply wastes none of it, while a bad one actively makes the week
     * worse than the percentage implies.
     *
     * <p>A club with no coach at all gets 1.0 as well. Nobody hired is not the same as somebody hired
     * badly, and punishing a club for having an empty staff list would be inventing a problem.
     */
    public static double disciplineFactor(StaffMember coach) {
        if (coach == null) return 1.0;
        int manManagement = coach.getMotivation() == null ? 10 : clampSkill(coach.getMotivation());
        return 0.75 + 0.25 * (manManagement / 20.0);
    }

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
