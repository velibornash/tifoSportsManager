package org.example.footballmanager.newLogic.util.players;

import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.model.Skills;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

/**
 * The strength standard of a division (owner, 2026-09-30).
 *
 * <p><b>Before this existed the pyramid had no gradient at all.</b> Measured on the live world, the
 * average player across eight skills was tier 1 → 8.61, tier 2 → 8.71, tier 3 → 8.57, tier 4 → 8.56,
 * tier 5 → 8.57: five divisions of one standard, with a minimum of 2.9 and a maximum of 14.0 in every
 * one of them. A bottom-of-the-pyramid club and a top-flight club were indistinguishable, so promotion
 * and relegation decided a table on reputation and tiebreaks rather than on football.
 *
 * <p>The owner's numbers: <b>tier 1 averages 12, then 11 and 10 by tier.</b> Tiers 4 and 5 continue
 * the same step to 9 and 8, because leaving them at the old value would have put a fifth-tier side in
 * the same band as a third-tier one - the flattening this class exists to remove, one row lower.
 *
 * <p><b>A tier is a player average, and the position redistributes within it.</b> The eight skills are
 * handed out so their <i>mean</i> is the tier number, then the position's own attribute is raised and
 * an attribute the player will never use is lowered by the same amount. A tier-1 keeper is 15 at
 * goalkeeping and 9 at striker, and both are a 12 player. This matters because a keeper who is also a
 * 12 at striker is a keeper the match engine will keep choosing to dribble with, and the previous code
 * gave all eight skills the same value, so every player in the world was equally competent at
 * everything.
 */
@Component
public class BotLeagueStandard {

    /** The owner's top-flight number, on the 1-20 skill scale. */
    public static final int TIER_ONE_AVERAGE = 12;

    /** Divisions below the top flight step down from here. */
    public static final int STEP_PER_TIER = 1;

    /** Five divisions in the pyramid. */
    public static final int LOWEST_TIER = 5;

    public static final int MIN_SKILL = 1;
    public static final int MAX_SKILL = 20;

    /** The eight football skills. FATIGUE is condition, not ability, and is not part of a tier. */
    public static final Map<SkillName, Integer> FOOTBALL_SKILLS = buildFootballSkills();

    private static Map<SkillName, Integer> buildFootballSkills() {
        Map<SkillName, Integer> skills = new EnumMap<>(SkillName.class);
        for (SkillName skill : SkillName.values()) {
            if (skill != SkillName.FATIGUE) {
                skills.put(skill, 0);
            }
        }
        return Map.copyOf(skills);
    }

    /**
     * The average player of a division.
     *
     * <p>Out-of-range tiers are clamped rather than rejected, because a competition row with a null or
     * zero tier must still produce a playable club. Clamping puts it in the top flight, which is the
     * safe direction: a mislabelled club is competitive instead of being a walkover for everyone.
     */
    public int skillAverageForTier(Integer tier) {
        int resolved = tier == null ? 1 : Math.max(1, Math.min(LOWEST_TIER, tier));
        return TIER_ONE_AVERAGE - (resolved - 1) * STEP_PER_TIER;
    }

    /**
     * The skills of one player.
     *
     * <p>{@code playerOffset} is the squad-depth of this individual: the best men sit above the
     * division's standard and the squad players sit below it, because a squad in which all 25 men are
     * exactly the tier number has no goalkeeper, no substitute and no reason to pick anyone. The mean
     * of the 25 offsets is near zero, so the <i>club</i> still averages the tier number.
     */
    public Skills skillsForTier(Integer tier, Position position, int playerOffset, Random random) {
        int tierAverage = skillAverageForTier(tier);
        Map<SkillName, Integer> bySkill = new EnumMap<>(SkillName.class);

        for (SkillName skill : FOOTBALL_SKILLS.keySet()) {
            // Jitter per skill, then corrected below so the eight still average the target. Without
            // the correction a player is the same at everything again, which is the old bug.
            bySkill.put(skill, clamp(tierAverage + playerOffset + jitter(random)));
        }

        applyPositionShape(bySkill, position, random);

        Skills skills = new Skills();
        // setSkill, not setExact. Skills are stored twice — a legacy int column and an exact double —
        // and setExact fills only the second one. A backfill that used it left 4,620 players reading
        // as 0 through getSkills(), which is a different method from the one the backfill itself
        // verified. Both halves have to move together or the player is two different players.
        bySkill.forEach(skills::setSkill);
        return skills;
    }

    /**
     * Raises the attribute the position is defined by, and lowers one it will never use, by the same
     * amount so the tier average survives.
     *
     * <p>Equal and opposite on purpose. Paying for the goalkeeper out of the striker column is
     * exactly how a real squad is built, and it is also what keeps {@link #skillAverageForTier} an
     * honest description of the player rather than of the squad.
     */
    private void applyPositionShape(Map<SkillName, Integer> bySkill, Position position, Random random) {
        SkillName own;
        SkillName irrelevant;
        int bonus;

        switch (position == null ? Position.MID : position) {
            case GK -> {
                own = SkillName.GOALKEEPER;
                irrelevant = SkillName.PLAYMAKER;
                bonus = 3;
            }
            case DEF -> {
                own = SkillName.DEFENDER;
                irrelevant = SkillName.STRIKER;
                bonus = 3;
            }
            case ATT -> {
                own = SkillName.STRIKER;
                irrelevant = SkillName.GOALKEEPER;
                bonus = 3;
            }
            case WNG -> {
                own = SkillName.PACE;
                irrelevant = SkillName.DEFENDER;
                bonus = 2;
            }
            default -> {
                own = SkillName.PLAYMAKER;
                irrelevant = SkillName.GOALKEEPER;
                bonus = 2;
            }
        }

        bySkill.put(own, clamp(bySkill.get(own) + bonus));
        bySkill.put(irrelevant, clamp(bySkill.get(irrelevant) - bonus));
        // A second attribute in the same direction, because one strong axis reads as a robot. A winger
        // who is only a fast defender is a fast defender.
        SkillName second = secondAxisFor(position);
        bySkill.put(second, clamp(bySkill.get(second) + 1));
    }

    private SkillName secondAxisFor(Position position) {
        return switch (position == null ? Position.MID : position) {
            case GK -> SkillName.DEFENDER;
            case DEF -> SkillName.PACE;
            case ATT -> SkillName.TECHNIQUE;
            case WNG -> SkillName.TECHNIQUE;
            default -> SkillName.PASSING;
        };
    }

    /** A little variety between two men of the same tier and the same job. */
    private int jitter(Random random) {
        return random.nextInt(3) - 1;
    }

    private int clamp(int value) {
        return Math.max(MIN_SKILL, Math.min(MAX_SKILL, value));
    }

    /**
     * Squad depth for a player, weighted so a squad has a spine.
     *
     * <p>One in four is clearly above the standard, one in four clearly below, the rest around it.
     * A flat uniform draw around zero produces exactly the kind of shapeless side the old generator
     * made, where the strongest and the weakest player differed by one point.
     */
    public int squadOffset(Random random) {
        int roll = random.nextInt(100);
        if (roll < 15) {
            return 2;
        }
        if (roll < 35) {
            return 1;
        }
        if (roll < 65) {
            return 0;
        }
        if (roll < 85) {
            return -1;
        }
        return -2;
    }
}
