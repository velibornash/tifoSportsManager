package org.example.footballmanager.newLogic.jobs.impl;

import org.example.footballmanager.newLogic.jobs.DayJob;
import org.example.footballmanager.newLogic.jobs.JobContext;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.service.MatchdayScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes a prepared matchday at kickoff (owner, 2026-10-11).
 *
 * <p>The second half of the owner's rule: *"jedan job koji 59 minuta pre kickoffa uradi compute svih
 * mečeva a drugi job u vreme meča otvori linkove"* — one job computes the matches an hour before
 * kickoff, and this one is what opens the links.
 *
 * <p>Publishing is one thing and it is deliberately small: the fixture becomes {@code played} and
 * points at the match that was computed an hour earlier. The football was done then. Nothing is
 * re-simulated here, because re-simulating would mean the result changed between the moment the manager
 * was locked out and the moment he was shown it — which would make the deadline a decoration.
 *
 * <p>A fixture that was <em>not</em> prepared — because the app was down when the hour passed, or the
 * preparation failed — is computed here instead, at kickoff, and published immediately. A matchday must
 * not be lost to an outage in the hour before it; it falls back to what the game did before this split
 * existed, which is correct and merely late.
 */
public class MatchdayPublishJob implements DayJob {

    private static final Logger log = LoggerFactory.getLogger(MatchdayPublishJob.class);

    private final int day;
    private final Integer kickoffHour;
    private final Integer week;
    private final String phase;
    private final MatchFixtureRepository fixtures;
    private final MatchdayScheduler scheduler;

    /** The club calendar: the kickoff hour comes from {@link org.example.footballmanager.newLogic.model.GameDay}. */
    public MatchdayPublishJob(int day, MatchFixtureRepository fixtures, MatchdayScheduler scheduler) {
        this(day, org.example.footballmanager.newLogic.model.GameDay.of(day).kickoffHour(),
                DayJob.ANY_WEEK, fixtures, scheduler);
    }

    /** An explicit kickoff hour, for a day whose kickoff is not in the club calendar. */
    public MatchdayPublishJob(int day, Integer kickoffHour, MatchFixtureRepository fixtures,
                              MatchdayScheduler scheduler) {
        this(day, kickoffHour, DayJob.ANY_WEEK, null, fixtures, scheduler);
    }

    /**
     * A week as well as a kickoff hour, for the national calendar.
     *
     * <p>National football happens in two weeks of the season, and a job that fired in every one of
     * them would be recorded as a successful national matchday that played nothing, seventy-odd times a
     * season. The same reason {@code NationalMatchdayJob} pins its week.
     */
    public MatchdayPublishJob(int day, Integer kickoffHour, Integer week, MatchFixtureRepository fixtures,
                              MatchdayScheduler scheduler) {
        this(day, kickoffHour, week, null, fixtures, scheduler);
    }

    /** With an explicit phase word in the key, so a second pair on the same day does not collide. */
    public MatchdayPublishJob(int day, Integer kickoffHour, Integer week, String phase, MatchFixtureRepository fixtures,
                              MatchdayScheduler scheduler) {
        this.day = day;
        this.kickoffHour = kickoffHour;
        this.week = week == null ? DayJob.ANY_WEEK : week;
        this.phase = phase;
        this.fixtures = fixtures;
        this.scheduler = scheduler;
    }

    /**
     * The done-flag is keyed on (season, week, day, key), so the week alone would be enough to keep two
     * national jobs apart. The key is still made distinct, because {@code JobTriggerUniquenessTest}
     * forbids two jobs sharing a day and a key and the rest of this package follows that rule - the
     * national matchday jobs are named {@code matchday-qualifier-2} and {@code matchday-tournament-2}
     * for the same reason.
     */
    @Override
    public String key() {
        return phase == null ? "matchday-publish-" + day
                : "matchday-publish-" + phase + "-" + day;
    }

    @Override
    public int week() {
        return week;
    }

    @Override
    public int day() {
        return day;
    }

    /** The kickoff hour itself, from the template. Day 1 is 20:45 and lands on hour 20. */
    @Override
    public int hour() {
        return kickoffHour == null ? 0 : kickoffHour;
    }

    @Override
    public int order() {
        // After everything that could still be preparing, and the thing the manager is waiting for.
        return 60;
    }

    /**
     * Runs outside the framework's transaction, committing a fixture at a time.
     *
     * <p>A matchday is 155 matches, not one unit of work. See {@link DayJob#transactionalRun()}.
     */
    @Override
    public boolean transactionalRun() {
        return false;
    }

    @Override
    public void run(JobContext context) {
        int published = scheduler.publishUnplayedOn(context.seasonYear(), context.weekNumber(), day,
                fixtures.findUnpublishedOnDay(context.seasonYear(), context.weekNumber(), day));
        if (published > 0) {
            log.info("Matchday publish day {}: {} result(s) released for week {}.",
                    day, published, context.weekNumber());
        }
    }
}
