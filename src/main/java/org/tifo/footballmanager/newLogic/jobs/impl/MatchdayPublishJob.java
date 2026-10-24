package org.tifo.footballmanager.newLogic.jobs.impl;

import org.tifo.footballmanager.newLogic.jobs.DayJob;
import org.tifo.footballmanager.newLogic.jobs.JobContext;
import org.tifo.footballmanager.newLogic.model.MatchFixture;
import org.tifo.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.tifo.footballmanager.newLogic.service.MatchdayScheduler;
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
    private final java.time.LocalTime kickoff;
    private final Integer week;
    private final String phase;
    private final MatchFixtureRepository fixtures;
    private final MatchdayScheduler scheduler;

    /** The club calendar: the kickoff comes from {@link org.tifo.footballmanager.newLogic.model.GameDay}. */
    public MatchdayPublishJob(int day, MatchFixtureRepository fixtures, MatchdayScheduler scheduler) {
        this(day, org.tifo.footballmanager.newLogic.model.GameDay.of(day).kickoffTime(),
                DayJob.ANY_WEEK, fixtures, scheduler);
    }

    /** An explicit kickoff hour, for a day whose kickoff is not in the club calendar. */
    public MatchdayPublishJob(int day, Integer kickoffHour, MatchFixtureRepository fixtures,
                              MatchdayScheduler scheduler) {
        this(day, kickoffHour == null ? (java.time.LocalTime) null : java.time.LocalTime.of(kickoffHour, 0),
                DayJob.ANY_WEEK, null, fixtures, scheduler);
    }

    /**
     * The whole kickoff, hour and minute, for the club calendar.
     *
     * <p>The publish is the moment the match reaches the manager, and on day 1 that is 20:45 - the
     * kickoff itself, not the hour it falls in (owner, 2026-10-22: "onda matchday-publish-1 pomeri
     * mec na dashboardima iz next u recents"). An hour-only trigger publishes at 20:00, three quarters
     * of an hour before the whistle, and moves a match that has not started into the results.
     */
    public MatchdayPublishJob(int day, java.time.LocalTime kickoff, MatchFixtureRepository fixtures,
                              MatchdayScheduler scheduler) {
        this(day, kickoff, DayJob.ANY_WEEK, null, fixtures, scheduler);
    }

    /**
     * A week as well as a kickoff, for the national calendar.
     */
    public MatchdayPublishJob(int day, java.time.LocalTime kickoff, Integer week,
                              MatchFixtureRepository fixtures, MatchdayScheduler scheduler) {
        this(day, kickoff, week, null, fixtures, scheduler);
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
        this(day, kickoffHour == null ? (java.time.LocalTime) null : java.time.LocalTime.of(kickoffHour, 0), week, null, fixtures,
                scheduler);
    }

    /** With an explicit phase word in the key, so a second pair on the same day does not collide. */
    public MatchdayPublishJob(int day, Integer kickoffHour, Integer week, String phase, MatchFixtureRepository fixtures,
                              MatchdayScheduler scheduler) {
        this(day, kickoffHour == null ? (java.time.LocalTime) null : java.time.LocalTime.of(kickoffHour, 0), week, phase, fixtures,
                scheduler);
    }

    /** The whole kickoff and the week. */
    public MatchdayPublishJob(int day, java.time.LocalTime kickoff, Integer week, String phase,
                              MatchFixtureRepository fixtures, MatchdayScheduler scheduler) {
        this.day = day;
        this.kickoff = kickoff;
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

    /**
     * The day's fixtures as ids, not entities — see {@link MatchdayPrepareJob}.
     */
    private static java.util.List<Long> idsOf(java.util.List<MatchFixture> fixtures) {
        return fixtures.stream().map(MatchFixture::getId).filter(java.util.Objects::nonNull).toList();
    }

    /**
     * The kickoff itself, from the template. Day 1 is 20:45 - the hour is 20 and the minute is 45,
     * and the publish waits for both.
     */
    @Override
    public int hour() {
        return kickoff == null ? 0 : kickoff.getHour();
    }

    /** The minute of the kickoff: 45 on day 1, 0 on the days that kick off on the hour. */
    @Override
    public int minute() {
        return kickoff == null ? 0 : kickoff.getMinute();
    }

    @Override
    public int order() {
        // After everything that could still be preparing, and the thing the manager is waiting for.
        return 60;
    }

    @Override
    public String dependsOn() {
        // A publish job must never run before its prepare job has finished computing the fixtures.
        return "matchday-prepare-" + day;
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
        MatchdayScheduler.MatchdayOutcome outcome = scheduler.publishUnplayedOn(
                context.seasonYear(), context.weekNumber(), day,
                idsOf(fixtures.findUnpublishedOnDay(context.seasonYear(), context.weekNumber(), day)));
        lastRunPublished = outcome.prepared();

        // Same rule as the prepare job: a result a manager was promised and did not get must not be
        // recorded as a matchday that happened.
        if (!outcome.complete()) {
            throw new IllegalStateException("Matchday publish day " + day + " for week "
                    + context.weekNumber() + ": " + outcome.prepared() + " result(s) released and "
                    + outcome.failed() + " failed");
        }
        if (outcome.prepared() > 0) {
            log.info("Matchday publish day {}: {} result(s) released for week {}.",
                    day, outcome.prepared(), context.weekNumber());
        }
    }

    /** How many results the run just now released. Read on the same thread, right after the body. */
    private int lastRunPublished = 0;

    /**
     * An empty matchday is not a finished matchday.
     *
     * <p>Same defect as {@link MatchdayPrepareJob#countsAsDone} and the same owner report: a tie drawn after
     * this job had already recorded the day DONE was never released, so the manager waited for a result
     * that was never going to arrive.
     */
    @Override
    public boolean countsAsDone(JobContext context) {
        return lastRunPublished > 0;
    }
}
