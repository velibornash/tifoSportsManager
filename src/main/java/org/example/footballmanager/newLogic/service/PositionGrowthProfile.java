package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.SkillName;

/**
 * Who learns what, and when (Sprint 4.5).
 *
 * <p>Until now growth was governed by two global penalties — {@code STRIKER × 0.76} and
 * {@code PACE × 0.86} for everybody, and one age curve for all eight skills. That is a fair way to
 * stop finishing growing at thirty, and a poor way to describe football: a winger learns crossing
 * quickly, a centre-half does not, a striker's finishing peaks years before a goalkeeper's shot
 * stopping, and a full-back's pace goes before a centre-forward's does.
 *
 * <p>Three separate questions are answered here, and it is worth being explicit that they are
 * separate because they are usually confused:
 *
 * <ol>
 *   <li><b>Rate</b> — {@link #learningRate}: how fast this position picks up this skill at all.</li>
 *   <li><b>Timing</b> — {@link #peakAge} and {@link #ageFactor}: when this skill is worth working
 *       on, and how much it is worth after that.</li>
 *   <li><b>Ceiling</b> — {@link #naturalCeiling}: how far a player can plausibly take a skill that
 *       is not his job.</li>
 * </ol>
 *
 * <p>Keeping them apart is what stops a later change from quietly re-tuning an earlier one, and it is
 * why a manager's individual focus (Sprint 4.1) still works here: choosing to work a centre-half on
 * passing is honoured, and the ceiling shapes how far that goes rather than vetoing it.
 */
public final class PositionGrowthProfile {

    private PositionGrowthProfile() {
    }

    /** Nothing about a body is the default: a missing height or weight must not silently penalise. */
    private static final double AVERAGE_HEIGHT_M = 1.80;
    private static final double AVERAGE_WEIGHT_KG = 78.0;

    /**
     * How fast a player in this position learns this skill, 1.0 being the ordinary rate.
     *
     * <p>Read as "how natural a fit this is", not as a verdict. A centre-half learning goalkeeping
     * gets 0.10, which is slow rather than impossible — the alternative, refusing it, would take
     * away the one decision this system exists to let a manager make.
     */
    public static double learningRate(Position position, SkillName skill) {
        if (position == null || skill == null) return 1.0;
        return switch (position) {
            case GK -> switch (skill) {
                case GOALKEEPER -> 1.30;
                case STAMINA, DEFENDER, PACE -> 0.80;
                case TECHNIQUE, PASSING -> 0.55;
                case PLAYMAKER, STRIKER -> 0.30;
                case FATIGUE -> 1.0;
            };
            case DEF -> switch (skill) {
                case DEFENDER -> 1.20;
                case STAMINA, PACE, PASSING -> 0.95;
                case TECHNIQUE -> 0.75;
                case GOALKEEPER -> 0.55;
                case PLAYMAKER, STRIKER -> 0.40;
                case FATIGUE -> 1.0;
            };
            case MID -> switch (skill) {
                case PLAYMAKER, PASSING, TECHNIQUE -> 1.20;
                case STAMINA, STRIKER -> 0.85;
                case DEFENDER, PACE -> 0.80;
                case GOALKEEPER -> 0.30;
                case FATIGUE -> 1.0;
            };
            case ATT -> switch (skill) {
                case STRIKER -> 1.25;
                case PACE, TECHNIQUE -> 0.95;
                case STAMINA, PLAYMAKER -> 0.75;
                case DEFENDER, GOALKEEPER, PASSING -> 0.50;
                case FATIGUE -> 1.0;
            };
            case WNG -> switch (skill) {
                case PACE, TECHNIQUE, PASSING -> 1.20;
                case STRIKER, PLAYMAKER -> 0.85;
                case STAMINA, DEFENDER -> 0.70;
                case GOALKEEPER -> 0.25;
                case FATIGUE -> 1.0;
            };
        };
    }

    /**
     * The age at which this position is at its best at this skill.
     *
     * <p>Goalkeepers peak late and full-backs decline early, which is the whole reason this is keyed
     * on position and skill rather than being one number for everybody. A 29-year-old goalkeeper is
     * still improving; a 29-year-old winger is not what he was.
     */
    public static int peakAge(Position position, SkillName skill) {
        if (position == null || skill == null) return 27;
        return switch (position) {
            case GK -> switch (skill) {
                // A goalkeeper at thirty is still the best he will be, and the game is full of them.
                case GOALKEEPER, DEFENDER -> 31;
                case PACE -> 25;
                default -> 28;
            };
            case DEF -> switch (skill) {
                // The full-back's problem. He has to sprint at a winger for a decade.
                case PACE -> 25;
                case DEFENDER -> 29;
                case STAMINA -> 28;
                default -> 27;
            };
            case MID -> switch (skill) {
                case PLAYMAKER, PASSING, TECHNIQUE -> 28;
                case PACE -> 26;
                case STRIKER -> 26;
                default -> 27;
            };
            case ATT -> switch (skill) {
                case STRIKER -> 26;
                case PACE -> 26;
                case DEFENDER -> 27;
                case STAMINA -> 27;
                case GOALKEEPER -> 31;
                default -> 27;
            };
            case WNG -> switch (skill) {
                case PACE, TECHNIQUE, PASSING -> 25;
                case STRIKER -> 26;
                case GOALKEEPER -> 31;
                default -> 27;
            };
        };
    }

    /**
     * The age past which this skill stops improving at all.
     *
     * <p>A hard zero, and deliberately so, because "a 34-year-old cannot improve pace at all
     * regardless of talent" is the kind of rule that is only true if it is a rule. A taper that
     * approaches zero still lets a determined, talented thirty-four-year-old creep up forever, which
     * means he is never actually too old — the sentence everyone says about footballers turns out to
     * be false in the only place it could be checked.
     *
     * <p>Note this floors growth at zero and never goes negative. Decline is a real thing and is not
     * modelled here; modelling it inside a "learning curve" would quietly delete players' skills and
     * is a separate piece of work.
     */
    public static int learningCeilingAge(Position position, SkillName skill) {
        if (skill == null) return 36;
        if (position == Position.GK && skill == SkillName.GOALKEEPER) {
            return 38;
        }
        // Pace and finishing are the two things that go first, everywhere.
        if (skill == SkillName.PACE) return 32;
        if (skill == SkillName.STRIKER) return 34;
        return 36;
    }

    /** True when this player is past the age at which the skill can still improve. */
    public static boolean isTooOldToLearn(Player player, SkillName skill) {
        if (player == null || skill == null) return false;
        return player.getAge() >= learningCeilingAge(player.getPosition(), skill);
    }

    /**
     * How well this age is doing, relative to the position's peak.
     *
     * <p>Full rate up to the peak, then a decline that is deliberately uneven: before the peak it is
     * flat rather than rising, because there is no version of learning that gets <i>faster</i> in
     * your late twenties.
     */
    public static double ageFactor(Position position, SkillName skill, int age) {
        int peak = peakAge(position, skill);
        if (age >= learningCeilingAge(position, skill)) {
            return 0.0;
        }
        if (age <= peak) {
            // Young players learn faster, and it is the only bonus in the curve.
            return age <= 19 ? 1.15 : age <= 22 ? 1.05 : 1.0;
        }
        int yearsPast = age - peak;
        double remainingYears = learningCeilingAge(position, skill) - peak;
        if (remainingYears <= 0) return 0.0;
        return Math.max(0.0, 1.0 - (yearsPast / (double) remainingYears));
    }

    /**
     * The skill level this player can realistically reach, if it is not his natural game.
     *
     * <p>A soft ceiling, not a wall: growth is damped as he approaches it, so a centre-half worked on
     * passing still improves — he just approaches a plausible limit rather than the top of the
     * scale. Making it a hard cap would be a nicer-sounding rule and a worse game, because the
     * interesting decision is exactly "can I turn this player into something else".
     *
     * <p>Natural positions are given the full scale, because a centre-half's defending is his job and
     * there is no reason to hold him below it.
     */
    public static double naturalCeiling(Position position, SkillName skill) {
        if (position == null || skill == null) return 20.0;
        double rate = learningRate(position, skill);
        if (rate >= 1.15) {
            return 20.0;
        }
        // An off-job skill tops out roughly in proportion to how unnatural it is, but never below
        // level 7 — a professional footballer is not helpless at something outside his game.
        double ceiling = 20.0 * (0.45 + 0.55 * Math.min(1.0, rate));
        return Math.max(7.0, Math.min(20.0, ceiling));
    }

    /**
     * How the body affects learning this skill, 1.0 being unremarkable.
     *
     * <p>Taller players get a real edge on aerial work and a real cost on pace; heavier players are
     * stronger and slower. Bounded to ±8%, because a body should shape a player, not decide him —
     * and because height and weight have been seeded on every player in the database and used for
     * nothing until now, so the temptation to make them matter a lot is exactly the temptation to
     * break the game with a field nobody has ever looked at.
     */
    public static double physicalFactor(Player player, SkillName skill) {
        if (player == null || skill == null) return 1.0;
        double height = player.getHeight() <= 0 ? AVERAGE_HEIGHT_M : player.getHeight();
        double weight = player.getWeight() <= 0 ? AVERAGE_WEIGHT_KG : player.getWeight();

        double heightDelta = (height - AVERAGE_HEIGHT_M) / 0.20;
        double weightDelta = (weight - AVERAGE_WEIGHT_KG) / 15.0;

        return switch (skill) {
            case DEFENDER, GOALKEEPER -> clamp(1.0 + 0.08 * heightDelta + 0.05 * weightDelta);
            case PACE -> clamp(1.0 - 0.07 * heightDelta - 0.05 * weightDelta);
            case STAMINA -> clamp(1.0 + 0.04 * weightDelta);
            default -> 1.0;
        };
    }

    private static double clamp(double value) {
        return Math.max(0.92, Math.min(1.08, value));
    }
}
