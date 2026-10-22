package org.tifo.footballmanager.newLogic.jobs.impl;

import org.tifo.footballmanager.newLogic.jobs.DayJob;
import org.tifo.footballmanager.newLogic.jobs.JobContext;
import org.tifo.footballmanager.newLogic.model.MatchFixture;
import org.tifo.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.tifo.footballmanager.newLogic.service.MatchdayScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Computes a matchday an hour before kickoff, and locks it (owner, 2026-10-11).
 *
 * <p>The owner's rule: *"jedan job koji 59 minuta pre kickoffa uradi compute svih mečeva a drugi job
 * u vreme meča otvori linkove. Napomena: sat vremena pre početka utakmice igrač ne sme više podešavati
 * taktiku za taj meč."*
 *
 * <p>So this job does the football and {@link MatchdayPublishJob} does the telling. The split is not
 * ceremony: it is what makes the deadline mean anything. If the match were computed at kickoff, a
 * manager could change his eleven at 19:59 and the change would count; locking an hour earlier while
 * still computing at kickoff would be a lie, because the tactics would be frozen against something
 * that had not been decided yet.
 *
 * <p><b>It computes the whole matchday, not the manager's fixtures.</b> Every tie of the day, so that
 * the league table, the ratings and the round are all settled together when the clock reaches kickoff
 * and nothing lands on a table mid-round.
 *
 * <p>What it must not do is publish. The fixtures stay {@code played = false} with the computed match
 * id on {@code preparedMatchId}; that state is what every read path in the game already treats as
 * "not happened".
 */
public class MatchdayPrepareJob implements DayJob {

    private static final Logger log = LoggerFactory.getLogger(MatchdayPrepareJob.class);

    private final int day;
    private final java.time.LocalTime kickoff;
    private final Integer week;
    private final String phase;
    private final MatchFixtureRepository fixtures;
    private final MatchdayScheduler scheduler;

    /**
     * The club calendar: the kickoff comes from {@link org.tifo.footballmanager.newLogic.model.GameDay}.
     */
    public MatchdayPrepareJob(int day, MatchFixtureRepository fixtures, MatchdayScheduler scheduler) {
        this(day, org.tifo.footballmanager.newLogic.model.GameDay.of(day).kickoffTime(),
                DayJob.ANY_WEEK, fixtures, scheduler);
    }

    /**
     * An explicit kickoff hour, for a day whose kickoff is not in the club calendar.
     *
     * <p>National tournaments play on days 2, 4 and 6, which {@code GameDay} has no kickoff for, and
     * they fall back to {@code NationalTournamentSchedule}'s own default. Passing the hour in is what
     * lets one class serve both calendars instead of reading a calendar it does not belong to.
     */
    public MatchdayPrepareJob(int day, Integer kickoffHour, MatchFixtureRepository fixtures,
                              MatchdayScheduler scheduler) {
        this(day, kickoffHour == null ? (java.time.LocalTime) null : java.time.LocalTime.of(kickoffHour, 0),
                DayJob.ANY_WEEK, fixtures, scheduler);
    }

    /**
     * The whole kickoff, hour and minute, for the club calendar.
     *
     * <p>The minute is not a detail here: day 1 kicks off at 20:45, so the lock has to land at 19:45
     * (owner, 2026-10-22) and an hour that knows only "19" lands it at 19:00 - three quarters of an
     * hour of extra time for a manager to change his eleven after the matchday was computed.
     */
    public MatchdayPrepareJob(int day, java.time.LocalTime kickoff, MatchFixtureRepository fixtures,
                              MatchdayScheduler scheduler) {
        this(day, kickoff, DayJob.ANY_WEEK, null, fixtures, scheduler);
    }

    /**
     * A week as well as a kickoff, for the national calendar.
     */
    public MatchdayPrepareJob(int day, java.time.LocalTime kickoff, Integer week,
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
    public MatchdayPrepareJob(int day, Integer kickoffHour, Integer week, MatchFixtureRepository fixtures,
                              MatchdayScheduler scheduler) {
        this(day, kickoffHour == null ? (java.time.LocalTime) null : java.time.LocalTime.of(kickoffHour, 0), week, null, fixtures,
                scheduler);
    }

    /** With an explicit phase word in the key, so a second pair on the same day does not collide. */
    public MatchdayPrepareJob(int day, Integer kickoffHour, Integer week, String phase, MatchFixtureRepository fixtures,
                              MatchdayScheduler scheduler) {
        this(day, kickoffHour == null ? (java.time.LocalTime) null : java.time.LocalTime.of(kickoffHour, 0), week, phase, fixtures,
                scheduler);
    }

    /** The whole kickoff and the week, for the club calendar. */
    public MatchdayPrepareJob(int day, java.time.LocalTime kickoff, Integer week, String phase,
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
        return phase == null ? "matchday-prepare-" + day
                : "matchday-prepare-" + phase + "-" + day;
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
     * The hour before kickoff, read from the template rather than restated.
     *
     * <p>Day 1 is a 20:45 slot, so "an hour before" is 19:45; the league days are 19:00 and 16:00,
     * so theirs are 18:00 and 15:00. A literal per day would be four more numbers to drift, and the
     * drift would be exactly the kind nobody notices: the lock landing at the wrong hour.
     */
    @Override
    public int hour() {
        return prepareTime() == null ? 0 : prepareTime().getHour();
    }

    /**
     * The minute of that hour, which is 45 on day 1 and 0 on the days that kick off on the hour.
     */
    @Override
    public int minute() {
        return prepareTime() == null ? 0 : prepareTime().getMinute();
    }

    /** The moment the matchday is computed: exactly an hour before the kickoff. */
    private java.time.LocalTime prepareTime() {
        return kickoff == null ? null : kickoff.minusHours(1);
    }

    @Override
    public int order() {
        // Ahead of the matchday job on the same day, and well ahead of the deadline it exists to
        // create. The order only matters within one hour; the two jobs sit an hour apart by construction.
        return 5;
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
        MatchdayScheduler.MatchdayOutcome outcome = scheduler.prepareUnplayedOn(
                context.seasonYear(), context.weekNumber(), day,
                idsOf(fixtures.findUnplayedOnDay(context.seasonYear(), context.weekNumber(), day)));
        lastRunPrepared = outcome.prepared();

        // Thrown so JobRunner records FAILED and does not mark the slot DONE. A matchday that lost
        // fixtures to an exception is not a matchday that happened, and recording it as one is how a
        // run that prepared 0 of 155 came to sit in the table looking like a quiet evening.
        if (!outcome.complete()) {
            throw new IllegalStateException("Matchday prepare day " + day + " for week "
                    + context.weekNumber() + ": " + outcome.prepared() + " fixture(s) computed and "
                    + outcome.failed() + " failed");
        }
        if (outcome.prepared() > 0) {
            log.info("Matchday prepare day {}: {} fixture(s) computed and locked for week {}.",
                    day, outcome.prepared(), context.weekNumber());
        }
    }

    /**
     * How many fixtures the run just now computed and locked.
     *
     * <p>Read by {@link #countsAsDone} on the same thread immediately after the body, so it is not shared
     * state in any way that matters — the claim guarantees one body per slot.
     */
    private int lastRunPrepared = 0;

    /**
     * An empty matchday is not a finished matchday.
     *
     * <p>Owner, 2026-10-19: an international fixture sat in the schedule and never played. The day-1
     * matchday had already run, found nothing, and been recorded DONE; the cup draw wrote the tie into that
     * same day a minute later, and DONE is terminal for a day. See {@link DayJob#countsAsDone}.
     */
    @Override
    public boolean countsAsDone(JobContext context) {
        return lastRunPrepared > 0;
    }

    /**
     * The day's fixtures as ids, not entities.
     *
     * <p>The scheduler re-reads each one inside its own transaction. Handing it the entities instead
     * hands the simulation lazy proxies owned by a persistence context that closed when the query
     * returned, and every fixture then fails with "no Session".
     */
    private static java.util.List<Long> idsOf(java.util.List<MatchFixture> fixtures) {
        return fixtures.stream().map(MatchFixture::getId).filter(java.util.Objects::nonNull).toList();
    }

    /** Kept so the day is visible on the job list, which is how a manager reads what is scheduled. */
    int preparedDay() {
        return day;
    }

    /** Whether this job's day has any fixtures at all. A quiet guard for the admin jobs view. */
    boolean hasFixtures(int season, int week, MatchFixtureRepository repository) {
        return repository.findUnplayedOnDay(season, week, day).stream()
                .map(MatchFixture::getId)
                .anyMatch(Objects::nonNull);
    }
}
