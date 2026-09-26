package org.example.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.model.GameClock;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.PlayerRole;
import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.model.Skills;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.GameClockRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The training every squad gets whether anyone is watching (owner, 2026-09-26).
 *
 * <p>The owner's ruling: AI clubs do nothing — no negotiation, no renewal, no tactics — but their
 * players still improve, because a league where 300 clubs never train is a league where a manager's
 * own training means nothing. Everyone gets a default week; the default is <b>pace</b>, because every
 * player needs it and it is the one attribute that helps at every position.
 *
 * <p>This is not the detailed training system — that is {@link TrainingProgressionService}, which a
 * manager runs deliberately with a chosen skill and a chosen group. This is the floor underneath it:
 * small, automatic, role-aware, and impossible to forget.
 *
 * <h2>Roles, not positions</h2>
 * What a player improves is decided by his {@link PlayerRole}, not his broad position. A centre back
 * and a left back are both DEF, but only one of them should be working on crossing, and treating them
 * as the same thing is how a club ends up with a squad of players who are all the same footballer.
 *
 * <h2>Freebies cost training</h2>
 * A club that agreed friendlies has fewer training sessions that week, and this honours it — the
 * whole point of a friendly being optional. See
 * {@link FriendlyRequestService#trainingSessionsAvailable}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SquadTrainingService {

    /**
     * How much a week of default training is worth, in skill points.
     *
     * <p>Small on purpose. A season is twelve weeks, so the largest a full season of nothing but
     * default training can add is about a point and a half — enough to matter, nowhere near enough
     * to turn a squad of journeymen into a title-winning side.
     */
    public static final double DEFAULT_WEEKLY_GROWTH = 0.12;

    /** The skill everyone works on when nothing has been chosen for them. */
    public static final String DEFAULT_SKILL = "PACE";

    /** Nobody improves after this; experience stops, and decline is a separate matter. */
    private static final int DEVELOPMENT_AGE_LIMIT = 34;

    private final TeamRepository teams;
    private final PlayerRepository players;
    private final FriendlyRequestService friendlies;

    /**
     * Trains every club in the game for one week.
     *
     * <p>Every club, not just the AI ones: a manager's squad should improve on the same clock
     * everyone else's does. A club that has a training plan gets it from
     * {@link TrainingProgressionService} and this is the additional floor.
     *
     * @return how many players were trained
     */
    @Transactional
    public int trainEveryClub(int season, int week) {
        List<Team> clubs = teams.findClubTeamsForOperations();
        int trained = 0;
        for (Team club : clubs) {
            if (club == null || club.getId() == null) continue;
            trained += trainSquad(club.getId(), season, week);
        }
        if (trained > 0) {
            log.debug("Week {} season {}: default training applied to {} players across {} clubs",
                    week, season, trained, clubs.size());
        }
        return trained;
    }

    /** Trains one club's squad for one week. */
    @Transactional
    public int trainSquad(Long teamId, int season, int week) {
        List<Player> squad = players.findByTeamId(teamId);
        if (squad.isEmpty()) return 0;

        int sessions = friendlies.trainingSessionsAvailable(teamId, season, week);
        if (sessions <= 0) {
            // He played friendlies instead of training. That is the trade the owner asked for.
            return 0;
        }
        // One session of the three is the floor, so a club that filled both friendly slots in the
        // break still improves, just less.
        double sessionScale = sessions / (double) FriendlyRequestService.BASE_TRAINING_SESSIONS_PER_WEEK;

        for (Player player : squad) {
            trainPlayer(player, season, week, sessionScale);
        }
        return squad.size();
    }

    /**
     * One week of development for one player.
     *
     * <p>Growth is decided by his {@link PlayerRole} and scaled by age: a young player improves
     * fastest, an old one not at all. His most important attribute gets most of it, and pace gets a
     * share of everything because everyone needs it.
     */
    @Transactional
    public void trainPlayer(Player player, int season, int week, double sessionScale) {
        Skills skills = player.getSkills();
        if (skills == null) return;
        skills.initializeExactFromVisibleIfNeeded();

        double ageFactor = ageFactor(player.getAge());
        if (ageFactor <= 0) return;

        PlayerRole role = player.effectiveRole();
        String primary = primarySkillFor(role);
        String secondary = secondarySkillFor(role);

        double week1 = DEFAULT_WEEKLY_GROWTH * ageFactor * sessionScale;

        addSkill(skills, primary, week1);
        if (secondary != null && !secondary.equals(primary)) {
            addSkill(skills, secondary, week1 * 0.5);
        }
        // The shared floor. Small, because it is not a specialism.
        addSkill(skills, DEFAULT_SKILL, week1 * 0.5);

        // Stamina comes with the work rather than from a separate programme.
        addSkill(skills, "STAMINA", week1 * 0.3);
        skills.syncVisibleFromExact();
        player.setSkills(skills);
    }

    /**
     * How fast a player of this age improves.
     *
     * <p>Peaks in the early twenties and falls away to nothing by the mid-thirties. A club that
     * hoards its veterans will find them stop getting better on their own.
     */
    static double ageFactor(int age) {
        if (age <= 0) return 0.4;               // unknown age: assume something ordinary
        if (age >= DEVELOPMENT_AGE_LIMIT) return 0;
        if (age <= 21) return 1.0;
        if (age <= 24) return 0.9;
        if (age <= 27) return 0.7;
        if (age <= 30) return 0.45;
        return 0.2;
    }

    /** The skill a player in this role improves most. */
    static String primarySkillFor(PlayerRole role) {
        if (role == null) return DEFAULT_SKILL;
        return switch (role) {
            case GOALKEEPER -> "GOALKEEPER";
            case LEFT_BACK, RIGHT_BACK, LEFT_WING_BACK, RIGHT_WING_BACK -> "DEFENDER";
            case CENTRE_BACK, RIGHT_CENTRE_BACK -> "DEFENDER";
            case DEFENSIVE_MIDFIELDER -> "DEFENDER";
            case CENTRE_MIDFIELDER, DEEP_PLAYMAKER -> "PASSING";
            case ATTACKING_MIDFIELDER -> "PLAYMAKER";
            case WINGER, INSIDE_FORWARD, WIDE_MIDFIELDER -> "TECHNIQUE";
            case STRIKER, SECOND_STRIKER, FALSE_NINE -> "STRIKER";
        };
    }

    /** The skill a player in this role also works on, or null if there is no sensible second. */
    static String secondarySkillFor(PlayerRole role) {
        if (role == null) return null;
        return switch (role) {
            case GOALKEEPER -> null;                 // one job, done properly
            case LEFT_BACK, RIGHT_BACK, LEFT_WING_BACK, RIGHT_WING_BACK -> "STAMINA";
            case CENTRE_BACK, RIGHT_CENTRE_BACK, DEFENSIVE_MIDFIELDER -> "PASSING";
            case CENTRE_MIDFIELDER, DEEP_PLAYMAKER -> "PLAYMAKER";
            case ATTACKING_MIDFIELDER -> "TECHNIQUE";
            case WINGER, INSIDE_FORWARD, WIDE_MIDFIELDER -> "PASSING";
            case STRIKER, SECOND_STRIKER, FALSE_NINE -> "TECHNIQUE";
        };
    }

    /**
     * Adds to one skill by name.
     *
     * <p>Goes through {@link SkillName} rather than a hand-written setter per attribute, so an
     * unknown key fails loudly instead of silently growing nothing — which is what a switch with a
     * default branch does.
     */
    private void addSkill(Skills skills, String key, double amount) {
        if (amount == 0) return;
        SkillName name = SkillName.valueOf(key);
        skills.setExact(name, skills.getExact(name) + amount);
    }

}
