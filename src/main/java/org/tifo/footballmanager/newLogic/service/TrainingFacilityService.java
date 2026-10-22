package org.tifo.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.tifo.footballmanager.newLogic.model.FinanceCategory;
import org.tifo.footballmanager.newLogic.model.Stadium;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.repository.StadiumRepository;
import org.tifo.footballmanager.newLogic.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.Map;

/**
 * Training facilities: what they cost and what they are worth (Sprint 4.4).
 *
 * <p>The effect side of facilities already exists — see {@link Stadium#trainingFactorFor(SkillName)}
 * and {@link Stadium#injuryProtection()}, which is what finally made the long-dormant
 * {@code trainingQuality} field mean something. This is the other half, and without it the fields
 * would be free: a club could set its gym to 20 and pay nothing, which is a balance hole rather than
 * a feature.
 *
 * <p><b>Why the cost curve is steeply progressive.</b> Getting from a bare field to a usable one is
 * cheap; getting from usable to elite is not, because that is the difference between resurfacing and
 * building. A linear cost would make the first ten levels a better buy than the last ten, and every
 * club would end up at 11 and never think about it again.
 *
 * <p><b>Why upkeep is linear and capital is not.</b> The weekly bill of a good gym is genuinely
 * higher and genuinely boring; the jump to an elite one is a one-off. Splitting them that way is
 * also the only way a manager can be asked a question they can answer: "can I afford this?" rather
 * than "should I want this?".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingFacilityService {

    /** The three facilities a manager can actually invest in, and what they are for. */
    public enum Facility {
        /** General grounds, pitches and equipment. */
        GROUND,
        /** Strength and conditioning. Also the injury facility. */
        GYM,
        /** Tactical teaching: the video room, the whiteboard, the extra pitch. */
        TACTICAL,
        /**
         * The youth setup: the academy pitches, the gym the juniors use, the scouting and coaching
         * facilities around them (Sprint 5.3).
         *
         * <p>Added because {@code Stadium.youthLevel} was written in Sprint 4.4 and had <b>no way to
         * be changed</b> — it was read by nothing and bought with nothing. The moment it became the
         * half of the academy quality model, leaving it unpurchasable would have meant a manager could
         * improve their academy only by hiring a better youth coach, and the facility half would have
         * been decoration.
         */
        YOUTH
    }

    /** Cheapest first level-to-level step, and the base the curve multiplies from. */
    private static final double BASE_STEP_COST = 2_500.0;
    private static final double COST_GROWTH = 1.45;
    private static final double MAX_LEVEL = 20;

    /** Weekly upkeep per level, per facility. Small on purpose: upkeep should sting, not bankrupt. */
    private static final double WEEKLY_UPKEEP_PER_LEVEL = 120.0;

    private final StadiumRepository stadiums;
    private final TeamRepository teams;

    /**
     * The capital cost of taking one facility up one level.
     *
     * <p>Null or absent stadium is 0 rather than an exception: a club with no recorded ground has
     * nothing to upgrade, and the caller decides what that means.
     */
    public double upgradeCost(Stadium stadium, Facility facility, int currentLevel) {
        if (stadium == null || facility == null) return 0.0;
        int level = clamp(currentLevel);
        if (level >= MAX_LEVEL) return 0.0;
        return BASE_STEP_COST * Math.pow(COST_GROWTH, level - 1);
    }

    /** The whole bill for taking a facility from where it is to {@code targetLevel}. */
    public double upgradeCostTo(Stadium stadium, Facility facility, int targetLevel) {
        if (stadium == null || facility == null) return 0.0;
        int target = clamp(targetLevel);
        double total = 0.0;
        for (int level = 1; level < target; level++) {
            total += upgradeCost(stadium, facility, level);
        }
        return total;
    }

    /**
     * The weekly upkeep for a club's training facilities, from what it has actually built.
     *
     * <p>Zero for a club with no stadium, which is the honest answer: it has no facilities and pays
     * nothing for them.
     */
    public double weeklyUpkeep(Team team) {
        Stadium s = team == null ? null : team.getStadium();
        if (s == null) return 0.0;
        return WEEKLY_UPKEEP_PER_LEVEL * ((level(s.getTrainingQuality()) - 1)
                + (level(s.getGymLevel()) - 1)
                + (level(s.getTacticalLevel()) - 1)
                + (level(s.getYouthLevel()) - 1));
    }

    /**
     * Raises one facility by one level and takes the capital cost out of the club's budget.
     *
     * <p>Refuses rather than allowing a negative balance. A club that cannot pay for a gym does not
     * get the gym and a red balance; it is told no, which is the difference between a budget and a
     * suggestion.
     *
     * @return what was spent, or empty if nothing changed
     */
    /**
     * How much better a club trains at this ground, from the GROUND facility (T-FAC-1).
     *
     * <p><b>Owner ruling, 2026-12-12: the ground trains faster, the tactical facility shapes better, and
     * a level buys less than the one before it.</b> "Ground trains faster" because it is the facility a
     * manager reads as *the training ground*, and "shapes better" for the tactical room because the effect
     * belongs where tactics are.
     *
     * <p><b>Diminishing returns</b> — the reasoning was the economy, not the maths: linear would let one
     * club's budget decide a league, since the cost already climbs 1.45× per level. So the benefit climbs
     * on a falling curve and level 20 is strong without being decisive. The square root is the cheap way to
     * get that shape, and it is written down rather than tuned, so the curve can be argued about.
     *
     * @return a multiplier on training progress; 1.0 at level 1, and never less than 1.0
     */
    @Transactional(readOnly = true)
    public double trainingMultiplier(Team team) {
        return multiplierFor(team, Facility.GROUND);
    }

    /**
     * The shared curve, so the two facilities cannot drift apart.
     *
     * <p>One method rather than two: a training multiplier at {@code sqrt} and a tactical multiplier at
     * {@code log} would be a bug nobody notices for a season.
     */
    private double multiplierFor(Team team, Facility facility) {
        Stadium ground = team == null ? null : team.getStadium();
        if (ground == null) {
            return 1.0;
        }
        int level = clamp(levelOf(ground, facility));
        if (level <= 1) {
            return 1.0;
        }
        // Level 1 is the floor, so the curve starts there rather than at zero: a club with no ground
        // trains at 1.0, not at 0.0, and a club at level 1 is not worse than a club with no record.
        //
        // **Normalised against the top level**, and that was not the first version. An un-normalised
        // `sqrt(steps) * MAX_GAIN` reached 5.36 at level 20 rather than the 2.0 its own javadoc promised,
        // because sqrt(19) is 4.36 and not 1. A 436% training bonus is exactly the thing the owner ruled
        // out when he asked for diminishing returns - it would let one club's budget decide a league. The
        // test for the ceiling is what found it; the arithmetic in a comment is not a check.
        double stepsAboveFloor = level - 1;
        double ceiling = Math.sqrt(MAX_LEVEL - 1);
        double gain = ceiling <= 0 ? 0 : Math.sqrt(stepsAboveFloor) / ceiling * MAX_GAIN;
        return 1.0 + gain;
    }

    /**
     * The most a facility at level 20 can add. A 100% better training week at the very top, which is a
     * club that trains every player twice as well as the ground it started with and still not enough on
     * its own to win a league.
     */
    public static final double MAX_GAIN = 1.0;

    /**
     * Gives every club a ground with facilities drawn from its reputation (T-FAC-1, owner ruling).
     *
     * <p><b>Owner ruling, 2026-12-12: bot clubs are seeded by reputation.</b> On a fresh world all 14,976
     * clubs sit at level 1, so the only club in the world with a training ground would be the manager's —
     * and the ranking tables would say so.
     *
     * <p><b>An action, not a boot write.</b> The standing rule is that boot writes nothing, and this is the
     * same kind of repair as "Seed other nations": idempotent, run from an admin button, safe twice.
     *
     * <p><b>Idempotent by not touching a ground that already has anything on it.</b> A manager who has
     * spent 18,000 on his gym does not get it flattened because someone pressed the button again.
     *
     * <p><b>Reputation is the 0-100 economy scale</b> — `Team.reputation` — not the Elo, which lives in
     * `eloRating` and is a different number on a different scale.
     *
     * @return how many clubs were seeded
     */
    @Transactional
    public int seedFacilitiesByReputation() {
        int seeded = 0;
        for (Team club : teams.findClubTeamsForOperations()) {
            if (club == null || club.getId() == null) continue;
            Stadium ground = club.getStadium();
            if (ground == null || ground.getId() == null) continue;
            if (hasAnythingBuilt(ground)) continue;
            int level = levelForReputation(club);
            ground.setTrainingQuality(level);
            ground.setGymLevel(level);
            ground.setTacticalLevel(level);
            ground.setYouthLevel(level);
            stadiums.save(ground);
            seeded++;
        }
        log.info("Seeded training facilities on {} club(s) from their reputation.", seeded);
        return seeded;
    }

    /** Whether anything at all is built, so a second run leaves a manager's spending alone. */
    private static boolean hasAnythingBuilt(Stadium ground) {
        return levelOf(ground, Facility.GROUND) > 1
                || levelOf(ground, Facility.GYM) > 1
                || levelOf(ground, Facility.TACTICAL) > 1
                || levelOf(ground, Facility.YOUTH) > 1;
    }

    /**
     * The facility level a club's reputation earns, 1 to {@link #MAX_LEVEL}.
     *
     * <p>The same square root as the benefit, deliberately: a club twice as well regarded is not twice as
     * well equipped. A straight line would leave the best clubs in the world at the ceiling and the rest
     * nowhere near it.
     */
    private static int levelForReputation(Team club) {
        // `getReputation()` is a Double - the 0-100 economy scale, not the Elo. Clamped here rather than
        // trusted, because a world that seeded a club above 100 would otherwise exceed the ceiling.
        Double raw = club.getReputation();
        double reputation = raw == null ? 50.0 : Math.max(0.0, Math.min(100.0, raw));
        double ceiling = Math.sqrt(MAX_LEVEL - 1);
        if (ceiling <= 0) {
            return 1;
        }
        long scaled = Math.round(1 + Math.sqrt(reputation / 100.0) * ceiling);
        return (int) Math.max(1L, Math.min((long) MAX_LEVEL, scaled));
    }

    private static int levelOf(Stadium ground, Facility facility) {
        Integer raw = switch (facility) {
            case GROUND -> ground.getTrainingQuality();
            case GYM -> ground.getGymLevel();
            case TACTICAL -> ground.getTacticalLevel();
            case YOUTH -> ground.getYouthLevel();
        };
        return raw == null ? 1 : raw;
    }

    @Transactional
    public java.util.OptionalDouble upgrade(Long teamId, Facility facility) {
        if (teamId == null || facility == null) return java.util.OptionalDouble.empty();
        Team team = teams.findById(teamId).orElse(null);
        Stadium s = team == null ? null : team.getStadium();
        if (s == null) return java.util.OptionalDouble.empty();

        Integer current = switch (facility) {
            case GROUND -> s.getTrainingQuality();
            case GYM -> s.getGymLevel();
            case TACTICAL -> s.getTacticalLevel();
            case YOUTH -> s.getYouthLevel();
        };
        int level = clamp(current);
        if (level >= MAX_LEVEL) return java.util.OptionalDouble.empty();

        double cost = upgradeCost(s, facility, level);
        double budget = team.getBudget() == null ? 0.0 : team.getBudget();
        if (budget < 0) {
            log.info("{} is in debt and cannot upgrade facilities.", team.getName());
            return java.util.OptionalDouble.empty();
        }
        if (cost > budget) {
            log.info("{} cannot afford a {} upgrade: needs {}, has {}", team.getName(), facility, cost, budget);
            return java.util.OptionalDouble.empty();
        }

        int next = level + 1;
        switch (facility) {
            case GROUND -> s.setTrainingQuality(next);
            case GYM -> s.setGymLevel(next);
            case TACTICAL -> s.setTacticalLevel(next);
            case YOUTH -> s.setYouthLevel(next);
        }
        team.setBudget(budget - cost);
        teams.save(team);
        stadiums.save(s);
        log.info("{} upgraded {} to level {} for {}", team.getName(), facility, next, cost);
        return java.util.OptionalDouble.of(cost);
    }

    /** The category a club's training facilities are billed under. */
    public FinanceCategory upkeepCategory() {
        return FinanceCategory.FACILITY_UPKEEP;
    }

    /** Every facility and its current level, for the club-management screen. */
    public Map<Facility, Integer> levels(Team team) {
        Map<Facility, Integer> out = new EnumMap<>(Facility.class);
        Stadium s = team == null ? null : team.getStadium();
        out.put(Facility.GROUND, s == null ? 1 : level(s.getTrainingQuality()));
        out.put(Facility.GYM, s == null ? 1 : level(s.getGymLevel()));
        out.put(Facility.TACTICAL, s == null ? 1 : level(s.getTacticalLevel()));
        out.put(Facility.YOUTH, s == null ? 1 : level(s.getYouthLevel()));
        return out;
    }

    private int level(Integer value) {
        return value == null ? 1 : clamp(value);
    }

    private int clamp(int value) {
        return Math.max(1, (int) Math.min(MAX_LEVEL, value));
    }
}
