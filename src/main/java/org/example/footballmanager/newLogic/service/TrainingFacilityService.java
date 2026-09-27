package org.example.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.model.FinanceCategory;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.StadiumRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
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
