package org.example.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.PlayerRole;
import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.model.Skills;
import org.example.footballmanager.newLogic.model.StaffMember;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The training every squad gets whether or not anyone is watching (owner, 2026-09-27).
 *
 * <p>The owner's ruling: AI clubs do nothing — no negotiation, no renewal, no tactics — but their
 * players still improve, because a league where three hundred clubs never train is a league where a
 * manager's own training means nothing. Everyone gets a default week; the default is <b>pace</b>,
 * because every player needs it and it is the one attribute that helps at any position.
 *
 * <h2>One growth function, not two</h2>
 * An earlier version of this had its own age curve and its own constants, which meant the same
 * player grew differently under a manager's programme than under the default one — talent, coach and
 * minutes ignored entirely. That is the duplication this codebase keeps paying for, and it is
 * exactly what went wrong with the transfer market. So the default pass now calls the <b>same</b>
 * {@link TrainingPercent} the manager's pass does. The only difference is the input: a default skill
 * and no advanced bonus.
 *
 * <h2>Freebies cost training</h2>
 * Friendlies do not reduce a club's training sessions, and match minutes still feed the training
 * percentage. See
 * {@link FriendlyRequestService#trainingSessionsAvailable}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SquadTrainingService {

    /**
     * How much a week of default training is worth, before the percentage.
     *
     * <p>Small on purpose. A season is twelve weeks, so the largest a full season of nothing but
     * default training can add is about a point and a half — enough to matter, nowhere near enough
     * to turn a squad of journeymen into a title-winning side.
     */
    public static final double DEFAULT_BASE = 0.12;

    /** The skill everyone works on when nothing has been chosen for them. */
    public static final String DEFAULT_SKILL = "PACE";

    private final TeamRepository teams;
    private final PlayerRepository players;
    private final TrainingPercentService percents;
    private final FriendlyRequestService friendlies;
    private final EntityManager entityManager;

    /**
     * Self-reference, so {@link #trainSquad} actually runs in its own transaction. Called through
     * {@code this.} the {@code @Transactional} annotation is bypassed and the whole world trains in
     * one transaction, which is the thing that made the job take five minutes and roll back all of
     * it on a single failure.
     */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private SquadTrainingService self;

    /**
     * Trains every club in the game for one week.
     *
     * <p>Every club, not only the AI ones: a manager's squad improves on the same clock everyone
     * else's does. A club with a training plan additionally gets it from
     * {@link TrainingProgressionService} — this is the floor underneath that, not a replacement.
     *
     * @return how many players were trained
     */
    public int trainEveryClub(int season, int week) {
        List<Team> clubs = teams.findClubTeamsForOperations();

        // **A manager's club is skipped here and trained properly instead.**
        //
        // Owner report, 2026-10-13: he moved the clock from day 4 to day 5 and Training Reports was empty.
        // The job had run — `job_run` held `training`, day 4, hour 10, DONE — because this is the method
        // the job calls. But **this is the other training implementation.** It grows attributes and writes
        // nothing, while `TrainingProgressionService.runWeeklyTraining` is the one that writes the week
        // report, and it was only ever reachable from the manual "run training" endpoint.
        //
        // So the weekly job trained every player in the world and produced no report for any of them. Two
        // implementations, the scheduled one silent, and every test green because each was tested alone.
        //
        // Human clubs are therefore skipped here and passed to {@link #trainManagedClubs}, which runs the
        // full weekly training. Running both would train them twice in a week.
        List<Long> clubIds = clubs.stream()
                .filter(c -> c != null && c.getId() != null)
                .filter(c -> !c.isHumanControlled())
                .map(Team::getId)
                .toList();

        int trained = 0;
        int failedClubs = 0;

        for (int from = 0; from < clubIds.size(); from += CLUB_CHUNK) {
            List<Long> chunk = clubIds.subList(from, Math.min(from + CLUB_CHUNK, clubIds.size()));
            for (Long clubId : chunk) {
                try {
                    trained += self.trainSquad(clubId, season, week);
                } catch (RuntimeException e) {
                    // **A club that cannot be trained is logged and skipped, not fatal.**
                    //
                    // Training is a growth operation, not an idempotent one: re-running the whole pass
                    // would train the already-trained clubs a second time. So a club that throws here is
                    // logged and left for the next week rather than failing the whole job and forcing a
                    // re-run that double-trains everyone before it.
                    failedClubs++;
                    log.warn("Training: club {} failed ({}). Continuing with the rest.", clubId,
                            e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
            log.info("Training: chunk {}/{} done ({} players so far, {} club(s) failed).",
                    from / CLUB_CHUNK + 1,
                    (clubIds.size() + CLUB_CHUNK - 1) / CLUB_CHUNK, trained, failedClubs);
            // **Let go of what has been trained.**
            //
            // Measured 2026-10-11: one pass over the owner's world - 14,880 clubs, ~34,000 players -
            // burned over six minutes of pure CPU with the database idle, 388 seconds of it in
            // Hibernate's cascade-on-flush. Not a slow query: no query was running.
            //
            // `trainSquad` is reached through `this.`, so its `@Transactional` never applies and the
            // whole pass is one session. Every club's `findByTeamId` then auto-flushes, and a flush
            // cascades over every entity dirtied so far - so the set grows to the whole world and the
            // work is quadratic. Clearing between chunks bounds it.
            if (entityManager.isOpen()) {
                entityManager.flush();
                entityManager.clear();
            }
        }
        if (failedClubs > 0) {
            log.warn("Training: {} club(s) failed this week. They are logged above and will be retried "
                    + "next week; clubs that succeeded were not re-trained.", failedClubs);
        }
        if (trained > 0) {
            log.info("Week {} season {}: default training applied to {} players across {} clubs ({} failed).",
                    week, season, trained, clubs.size() - failedClubs, failedClubs);
        }
        return trained;
    }

    /** How many clubs are trained between flushes. See {@link #trainEveryClub}. */
    private static final int CLUB_CHUNK = 400;

    /** Trains one club's squad for one week. */
    @Transactional
    public int trainSquad(Long teamId, int season, int week) {
        List<Player> squad = players.findByTeamId(teamId);
        if (squad.isEmpty()) {
            return 0;
        }

        int sessions = friendlies.trainingSessionsAvailable(teamId, season, week);
        if (sessions <= 0) {
            // Keep the guard for a future training contract. The current friendly contract always
            // returns the full baseline because a friendly costs zero sessions.
            return 0;
        }

        StaffMember coach = percents.coachFor(squad.get(0));
        for (Player player : squad) {
            trainPlayer(player, season, week, sessions, coach);
        }
        return squad.size();
    }

    /**
     * One week of development for one player, through the same maths as a manager's programme.
     *
     * <p>The percentage decides how much of the week's work happens; the age factor decides how much
     * a point is worth. Neither the default pass nor the manager's pass has its own talent factor.
     */
    @Transactional
    public void trainPlayer(Player player, int season, int week, int sessions, StaffMember coach) {
        Skills skills = player.getSkills();
        if (skills == null) return;
        skills.initializeExactFromVisibleIfNeeded();

        int minutes = percents.minutesPlayed(player.getId(),
                player.getTeam() == null ? -1L : player.getTeam().getId(), season, week);

        // The default programme works on the role's primary skill, and shares pace out to everyone -
        // it is not a specialism, it is the floor of being fit.
        PlayerRole role = player.effectiveRole();
        SkillName primary = TrainingPercent.primarySkillFor(role);
        SkillName secondary = TrainingPercent.secondarySkillFor(role);

        double percent = TrainingPercent.percentFor(player, coach, primary, minutes);
        double share = percent / 100.0
                * (sessions / (double) FriendlyRequestService.BASE_TRAINING_SESSIONS_PER_WEEK);

        double growth = DEFAULT_BASE * share * ageFactor(player.getAge());
        if (growth <= 0) {
            return;
        }

        addSkill(skills, primary, growth);
        if (secondary != null && secondary != primary) {
            addSkill(skills, secondary, growth * 0.5);
        }
        addSkill(skills, SkillName.PACE, growth * 0.5);
        // Stamina comes with the work rather than from a separate programme.
        addSkill(skills, SkillName.STAMINA, growth * 0.3);

        skills.syncVisibleFromExact();
        player.setSkills(skills);
    }

    /**
     * How fast a player of this age improves.
     *
     * <p>Peaks in the early twenties and falls away to nothing by the mid-thirties. A club that
     * hoards its veterans will find them stop getting better on their own — which is the point of
     * aging being a cost rather than a decoration.
     */
    static double ageFactor(int age) {
        if (age <= 0) return 0.4;               // unknown age: assume something ordinary
        if (age >= 34) return 0;
        if (age <= 21) return 1.0;
        if (age <= 24) return 0.9;
        if (age <= 27) return 0.7;
        if (age <= 30) return 0.45;
        return 0.2;
    }

    /**
     * Adds to one skill by name, through {@link SkillName} so an unknown key fails loudly rather
     * than silently growing nothing.
     */
    private void addSkill(Skills skills, SkillName name, double amount) {
        if (amount == 0 || name == null) return;
        skills.setExact(name, skills.getExact(name) + amount);
    }

}
