package org.example.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.PlayerTrainingIntensity;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Skills;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.TrainingIntensity;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.PlayerTrainingIntensityRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The cost of training (Sprint 4.2).
 *
 * <p>Training used to be free, which means pushing every player as hard as possible every week was
 * the only sensible strategy, and the only strategy is not a game. This adds the other half: work
 * costs fatigue, fatigue makes injury likely, and injury costs weeks.
 *
 * <p>The design intent is that {@code VERY_HARD} is a <b>decision</b> rather than an upgrade. It
 * beats {@code NORMAL} by a third on a rested player, which is a real gain over a twelve-week season.
 * On a tired player it is most of a certainty, which is a lost player for weeks. Both halves have to
 * be true or the setting is not a decision.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingIntensityService {

    /**
     * The fatigue an injury adds on top of the week's work.
     *
     * <p>Named because it is a rule and not an accident: a player who is hurt in training does not
     * return the following week as though nothing happened. Previously a bare {@code + 8} with the
     * reasoning in a comment, which no test could name.
     */
    static final int INJURY_FATIGUE_KNOCK = 8;

    /** How many days a training injury costs at minimum. */
    private static final int TRAINING_INJURY_MIN_DAYS = 7;
    private static final int TRAINING_INJURY_MAX_DAYS = 28;

    private final PlayerTrainingIntensityRepository overrides;
    private final PlayerRepository players;
    private final TeamRepository teams;
    /**
     * The injury dice.
     *
     * <p>Not final, and replaceable from tests, because {@code apply} is a genuinely random method:
     * {@code VERY_HARD} hurts a rested player 4.5% of the time, and the injury branch then adds a
     * further 8 fatigue on top of the week's work. A test asserting an exact fatigue figure on that
     * path is flaky at 1 run in 22, which is how a red build becomes something people learn to
     * re-run. Tests that care which branch they are on now say so by handing in a fixed dice.
     */
    private Random random = new Random();

    /**
     * Replaces the injury dice. Package-private on purpose: this is a test seam, not a product
     * feature, and a manager must never be able to choose whether his squad gets hurt.
     */
    void useRandom(Random random) {
        this.random = random == null ? new Random() : random;
    }

    /**
     * The intensity a player actually trains at: his own override, or the club's for the week.
     *
     * <p>Fails to {@link TrainingIntensity#NORMAL} rather than throwing. A missing setting must not
     * take out a week's training for three hundred clubs.
     */
    @Transactional(readOnly = true)
    public TrainingIntensity intensityFor(Player player, TrainingIntensity teamDefault,
                                         Integer season, Integer week) {
        if (player != null && player.getId() != null && season != null && week != null) {
            var own = overrides.findByPlayerIdAndSeasonAndWeek(player.getId(), season, week);
            if (own.isPresent() && own.get().getIntensity() != null) {
                return own.get().getIntensity();
            }
        }
        return teamDefault != null ? teamDefault : TrainingIntensity.NORMAL;
    }

    /** Sets or clears a player's override for a week. */
    @Transactional
    public boolean setOverride(Long teamId, Long playerId, Integer season, Integer week,
                               TrainingIntensity intensity) {
        if (teamId == null || playerId == null) {
            return false;
        }
        Player player = players.findById(playerId).orElse(null);
        if (player == null || player.getTeam() == null
                || !Objects.equals(player.getTeam().getId(), teamId)) {
            return false;
        }
        if (intensity == null) {
            var existing = overrides.findByPlayerIdAndSeasonAndWeek(playerId, season, week);
            existing.ifPresent(overrides::delete);
            return true;
        }
        if (overrides.findByPlayerIdAndSeasonAndWeek(playerId, season, week).isPresent()) {
            overrides.delete(
                    overrides.findByPlayerIdAndSeasonAndWeek(playerId, season, week).orElseThrow());
        }
        overrides.save(PlayerTrainingIntensity.of(teamId, playerId, season, week, intensity));
        return true;
    }

    /** Every override set for a squad this week. */
    @Transactional(readOnly = true)
    public Map<Long, TrainingIntensity> overridesForSquad(Long teamId, Integer season, Integer week) {
        return overrides.findByTeamIdAndSeasonAndWeek(teamId, season, week).stream()
                .filter(o -> o != null && o.getPlayerId() != null && o.getIntensity() != null)
                .collect(Collectors.toMap(PlayerTrainingIntensity::getPlayerId,
                        PlayerTrainingIntensity::getIntensity, (a, b) -> a));
    }

    /**
     * What a week at this intensity does to a player: the growth multiplier, the fatigue, and
     * whether he got hurt.
     *
     * <p>Growth is scaled by intensity; fatigue is added; the injury roll is made. The three together
     * are the trade, so they are returned as one result rather than three calls a caller has to
     * remember to make in the right order.
     */
    @Transactional
    public Outcome apply(Player player, TrainingIntensity intensity, Integer season, Integer week) {
        if (player == null || player.getSkills() == null || intensity == null) {
            return Outcome.NONE;
        }
        Skills skills = player.getSkills();
        int before = skills.getFatigue();

        // Fatigue from the work itself. A LIGHT week still costs something, so switching a squad off
        // is a decision with a price rather than a free action.
        skills.setFatigue(Math.min(100, before + intensity.weeklyFatigue()));

        boolean hurt = rollInjury(player, intensity);
        if (hurt) {
            int span = TRAINING_INJURY_MAX_DAYS - TRAINING_INJURY_MIN_DAYS + 1;
            int days = TRAINING_INJURY_MIN_DAYS + (span <= 0 ? 0 : random.nextInt(span));
            // The JPA player has no injury-type or separate fatigue field of its own - condition
            // lives on its Skills, which is also what the sim engine's own InjuryService writes to.
            // Writing anywhere else would leave a player injured in one system and fit in the other.
            player.setInjured(true);
            player.setInjuryDaysRemaining(days);
            player.setInjurySeasonNumber(season);
            player.setInjuryWeekNumber(week);
            // A soft knock on top of the lay-off, so he is not fresh the moment he returns.
            skills.setFatigue(Math.min(100, skills.getFatigue() + INJURY_FATIGUE_KNOCK));
            log.info("{} hurt in training at {} intensity in week {} ({} days), fatigue {}",
                    player.getName(), intensity, week, days, before);
        }

        return new Outcome(intensity, before, skills.getFatigue(), hurt, player.getInjuryDaysRemaining());
    }

    /**
     * The injury roll.
     *
     * <p>Three things make it zero, and each is a real rule rather than a guard:
     * <ul>
     *   <li>a player <b>already injured</b> does not pick up a second one — he is not training, and
     *       rolling for him would extend a lay-off for no reason;</li>
     *   <li>{@code LIGHT} never injures anyone, which is what makes it the rehabilitation option
     *       rather than a slightly worse {@code NORMAL};</li>
     *   <li>a chance of zero is not rolled against.</li>
     * </ul>
     *
     * <p>Everything else is {@link TrainingIntensity#injuryChance}, so how tired is too tired is
     * defined in exactly one place.
     */
    private boolean rollInjury(Player player, TrainingIntensity intensity) {
        if (player.isInjured() || intensity == TrainingIntensity.LIGHT) {
            return false;
        }
        int fatigue = player.getSkills() == null ? 0 : player.getSkills().getFatigue();
        double chance = intensity.injuryChance(fatigue) * (1.0 - gymProtection(player));
        return chance > 0 && random.nextDouble() < chance;
    }

    /**
     * How much this player's gym takes off the risk, 0.0 to 0.4.
     *
     * <p>Part of the roll rather than a subtraction, so a club with a good gym cannot end up with a
     * negative risk and a club with a bad one cannot be pushed above the tier's own number.
     */
    private double gymProtection(Player player) {
        if (player == null || player.getTeam() == null) return 0.0;
        Stadium ground = player.getTeam().getStadium();
        return ground == null ? 0.0 : ground.injuryProtection();
    }

    /** What one week of work did. */
    public record Outcome(TrainingIntensity intensity, int fatigueBefore, int fatigueAfter,
                          boolean injured, Integer injuryDays) {

        static final Outcome NONE = new Outcome(TrainingIntensity.LIGHT, 0, 0, false, null);

        public boolean costFatigue() {
            return fatigueAfter > fatigueBefore;
        }
    }
}
