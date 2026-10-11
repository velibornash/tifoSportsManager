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
    private final MatchFixtureRepository fixtures;
    private final MatchdayScheduler scheduler;

    public MatchdayPublishJob(int day, MatchFixtureRepository fixtures, MatchdayScheduler scheduler) {
        this.day = day;
        this.fixtures = fixtures;
        this.scheduler = scheduler;
    }

    @Override
    public String key() {
        return "matchday-publish-" + day;
    }

    @Override
    public int week() {
        return ANY_WEEK;
    }

    @Override
    public int day() {
        return day;
    }

    /** The kickoff hour itself, from the template. Day 1 is 20:45 and lands on hour 20. */
    @Override
    public int hour() {
        Integer kickoff = org.example.footballmanager.newLogic.model.GameDay.of(day).kickoffHour();
        return kickoff == null ? 0 : kickoff;
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
