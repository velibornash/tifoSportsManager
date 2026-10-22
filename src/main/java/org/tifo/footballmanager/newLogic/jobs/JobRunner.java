package org.tifo.footballmanager.newLogic.jobs;

import org.tifo.footballmanager.newLogic.model.JobRun;
import org.tifo.footballmanager.newLogic.repository.JobRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs the jobs that are due, and only those, exactly once (owner, 2026-09-28).
 *
 * <h2>The rule this exists to enforce</h2>
 *
 * <p>A job runs when its trigger hour has been reached <b>and</b> no {@link JobRun} row exists for
 * (season, week, day, key). That second half is the whole point: advancing an hour can cross several
 * triggers, advancing through a whole day can cross the same one repeatedly, and nothing else stops
 * finance being applied twenty-four times.
 *
 * <h2>Failure is contained</h2>
 *
 * <p>A job that throws is recorded as FAILED and the runner continues to the next one. One broken
 * job must not freeze the season, and a FAILED row is not re-run by a later scan - retrying
 * automatically is how a partially-applied job gets applied twice. Re-queueing is explicit.
 *
 * <p>Each job runs in its own transaction, so a throw rolls back only that job. The runner's own
 * bookkeeping is a separate transaction for the same reason: a job's failure must still be recorded.
 *
 * <h2>Where the rows go</h2>
 *
 * <p>A {@code job_run} row is written before the body runs (the claim), and updated after. The states mean
 * three different things, and only one of them is a result: {@code PENDING} is a <b>claim</b> — a scan took
 * the slot and the body is running; {@code FAILED} is a <b>recorded failure</b>; {@code DONE} is the only
 * terminal one, and a {@code FAILED} or stale {@code PENDING} row is picked up again by the next scan.
 *
 * <p>Exactly two things remove a row. The idle branch deletes it, because a job that found nothing has not
 * happened and must stay eligible. And <b>{@code Reset DB} truncates the table</b>: the reset enumerates the
 * live schema and clears everything not in its keep-list, and {@code job_run} is not in it. That is
 * <b>correct</b> — a reset has to clear the "this job already ran for this day" state, or the rebuilt world
 * would inherit the old world's. An empty {@code job_run} table after a reset is expected, not a symptom.
 * (It was investigated on 2026-10-19 as "the rows went missing"; the answer was the reset, and the
 * investigation also turned up two real defects in the failure path, both now fixed.)
 */
@Service
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final List<DayJob> jobs;
    private final JobRunRepository runs;
    private final TransactionTemplate requiresNew;

    /**
     * Job keys known to have run, per game day.
     *
     * <p>Bounded by the days a session sees, which is the clock advancing, and it is cleared with the
     * runner. A world advanced for years in one process would accumulate one small set per day, and that
     * is the memory this saves - the alternative is a database round trip per job per hour.
     */
    private final Map<String, Set<String>> doneJobs = new ConcurrentHashMap<>();

    public JobRunner(List<DayJob> jobs, JobRunRepository runs,
                     PlatformTransactionManager transactionManager) {
        // Sorted by order(), not left in injection order. Spring does not guarantee the order of a
        // List<Interface> injection, so the sequence of financial effects would otherwise depend on
        // classpath scanning.
        this.jobs = jobs.stream()
                .sorted(Comparator.comparingInt(DayJob::order).thenComparing(DayJob::key))
                .toList();
        this.runs = runs;
        // Programmatic, not @Transactional. execute() and save() are called on `this`, and Spring's
        // proxy is bypassed on a self-invocation - so @Transactional(REQUIRES_NEW) on them would
        // silently do nothing and a throwing job would roll back the record of every job that had
        // already succeeded, which would make them all run again. A TransactionTemplate cannot be
        // bypassed, so the boundary is real.
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Runs everything due for the given clock position.
     *
     * @param minute the minute inside the hour, so a job that fires at 19:45 is not due at 19:30
     * @return one entry per job considered, so the caller can see what ran, what was already done
     *         and what failed. Returning the detail rather than a count is what makes an advancing
     *         clock diagnosable instead of opaque.
     */
    public Map<String, Object> runDue(int seasonYear, int weekNumber, int dayNumber, int hour, int minute) {
        List<Map<String, Object>> outcomes = new ArrayList<>();
        String dayKey = seasonYear + "/" + weekNumber + "/" + dayNumber;
        Set<String> doneToday = doneJobs.computeIfAbsent(dayKey, ignored -> new HashSet<>());

        for (DayJob job : jobs) {
            if (!isDue(job, weekNumber, dayNumber, hour, minute)) {
                continue;
            }
            // **D4: a job that has already run today does not need asking again.**
            //
            // `isDue` is `hour >= job.hour()`, so a job at 09:00 is considered due for the next fifteen
            // hours, and `runDue` runs every hour — so the guard lookup in `runOnce` was issued 11 jobs x
            // 24 hours = **264 times a game day** to re-learn something that had not changed.
            //
            // The day-scoped memo is a cache of DONE keys and nothing else. **A FAILED job is never
            // cached**, so the retry that A2 introduced still reaches the database on the next hour; and an
            // empty cache after a restart simply means the first hour of the day asks, which is where the
            // database is the source of truth.
            if (doneToday.contains(job.key())) {
                Map<String, Object> already = new LinkedHashMap<>();
                already.put("key", job.key());
                already.put("status", "ALREADY_DONE");
                already.put("memo", true);
                outcomes.add(already);
                continue;
            }
            Map<String, Object> outcome = runOnce(job, seasonYear, weekNumber, dayNumber, hour);
            if ("DONE".equals(outcome.get("status"))) {
                doneToday.add(job.key());
            }
            outcomes.add(outcome);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("season", seasonYear);
        result.put("week", weekNumber);
        result.put("day", dayNumber);
        result.put("hour", hour);
        result.put("jobs", outcomes);
        result.put("ran", outcomes.stream().filter(o -> "DONE".equals(o.get("status"))).count());
        result.put("skipped", outcomes.stream()
                .filter(o -> "ALREADY_DONE".equals(o.get("status")) || "BACKING_OFF".equals(o.get("status")))
                .count());
        result.put("failed", outcomes.stream().filter(o -> "FAILED".equals(o.get("status"))).count());
        return result;
    }

    /**
     * Whether a failed job is still inside its cool-down.
     *
     * <p>Measured in <b>clock hours</b>, because the clock is what the jobs are scoped to: the same
     * hour that decides whether a job is due decides how long it waits. A job that failed at 20:45
     * is tried again at 21:45, not at 21:15.
     *
     * <p><b>A stale PENDING row is not a failure and is not backed off.</b> It is a scan that died,
     * and it must be picked up at once — otherwise a killed job is a missed matchday.
     */
    private static boolean stillBackingOff(JobRun previous, int hour) {
        Integer failedAt = previous.getRanAtHour();
        if (failedAt == null) {
            // No hour recorded, so it is not known to be recent. The owner's ruling is that a
            // failure is never terminal, and that ruling wins over the cool-down.
            return false;
        }
        return hour - failedAt < FAILED_BACKOFF_HOURS;
    }

    /**
     * Runs everything due in a whole hour, minute ignored.
     *
     * <p>Used for an hour that has already gone by: the catch-up walks the hours it missed, and a
     * trigger inside one of them (the 19:45 prepare) still has to run - so an hour that is over is
     * walked at its end ({@code 59}), and everything due in it is due.
     */
    public Map<String, Object> runDue(int seasonYear, int weekNumber, int dayNumber, int hour) {
        return runDue(seasonYear, weekNumber, dayNumber, hour, 59);
    }

    /**
     * Whether a job's trigger has been reached.
     *
     * <p>The hour comparison is not "greater or equal" in general: a job at 14:00 is not due at
     * 09:00, which is the case that made this whole framework necessary. A job at 00:00 is due from
     * the first hour of the day.
     *
     * <p><b>The minute is part of the trigger, and day 1 is why.</b> The internationals kick off at
     * 20:45, so the prepare fires at 19:45 and the publish at 20:45 (owner, 2026-10-22). An
     * hour-only trigger fires the lock three quarters of an hour early and the publish before the
     * whistle - and a job that fires early is worse than one that fires late, because a manager can
     * still see a late result and cannot un-see an early one.
     *
     * <p>Past hours are walked at the end of the hour, so a trigger inside an hour that has already
     * gone by is still picked up by the catch-up.
     */
    private boolean isDue(DayJob job, int weekNumber, int dayNumber, int hour, int minute) {
        if (job.week() != DayJob.ANY_WEEK && job.week() != weekNumber) {
            return false;
        }
        if (job.day() != DayJob.ANY_DAY && job.day() != dayNumber) {
            return false;
        }
        if (hour > job.hour()) {
            return true;
        }
        if (hour < job.hour()) {
            return false;
        }
        return minute >= job.minute();
    }

    /**
     * How long a claim is honoured before it is assumed abandoned.
     *
     * <p>Ten minutes against a job body that runs in seconds. It is not tuned for performance; it exists so
     * that a process killed mid-job cannot leave that job unplayed for ever. A job that legitimately runs
     * longer than this must be re-entrant, because it will be started again.
     */
    private static final long CLAIM_LIVE_FOR_SECONDS = 600;

    /**
     * How long a failed job waits, in clock hours, before the next scan tries it again.
     *
     * <p>The scan is the game clock's and it comes round every half hour, so a FAILED job was
     * retried on <b>every one of them</b> — forty-eight times a day for a failure that will fail
     * again, each attempt burning the work. A matchday job's attempt is the whole matchday.
     *
     * <p>Two hours is four scans. A transient failure is still picked up long before the next
     * matchday, and a deterministic one is not hammered.
     *
     * <p><b>The number is a decision, not a measurement</b>, which is why it is a constant here and
     * not buried in the retry loop.
     */
    public static final int FAILED_BACKOFF_HOURS = 2;

    /** Is this PENDING row a claim a live scan still holds, or one a dead scan left behind? */
    private static boolean claimIsLive(JobRun claim) {
        if (claim.getRanAt() == null) {
            // A claim with no timestamp cannot be shown to be live, so it is treated as abandoned.
            return false;
        }
        return claim.getRanAt().isAfter(Instant.now().minusSeconds(CLAIM_LIVE_FOR_SECONDS));
    }

    private Map<String, Object> runOnce(DayJob job, int seasonYear, int weekNumber, int dayNumber, int hour) {
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("key", job.key());
        outcome.put("trigger", "w" + (job.week() == DayJob.ANY_WEEK ? "*" : job.week())
                + " d" + (job.day() == DayJob.ANY_DAY ? "*" : job.day())
                + " " + String.format("%02d:%02d", job.hour(), job.minute()));

        var existing = runs.findBySeasonYearAndWeekNumberAndDayNumberAndJobKey(
                seasonYear, weekNumber, dayNumber, job.key());

        if (existing.isPresent() && existing.get().getStatus() == JobRun.Status.DONE) {
            // The whole reason this class exists, and now the **only** thing that stops a re-run.
            //
            // It used to also stop on FAILED — "not retried automatically, a job that already threw once may
            // have applied half its work". That was defensible on its own and wrong in practice: a FAILED
            // guard is **terminal**, so one transient throw disabled that job for that (season, week, day) for
            // good. A MatchdayJob that failed meant **a whole matchday was never played and never retried**.
            // The owner, 2026-10-02: a job fires and must execute immediately; if it does not complete, the
            // status is not DONE and the scheduler picks it up next time.
            //
            // The risk is real and is stated rather than hidden: a job that half-applied before throwing will
            // be re-run, and half of it will be applied twice. That is now the owner's accepted trade — a
            // permanently missed matchday is worse than a duplicate application, and a job that can
            // half-apply should be written to be idempotent.
            outcome.put("status", "ALREADY_DONE");
            return outcome;
        }
        // Not DONE means not done: this row is a previous attempt that did not complete, so the job runs
        // again now. The message from the last attempt is kept for diagnosis and overwritten on success.
        if (existing.isPresent()) {
            JobRun previous = existing.get();
            if (previous.getStatus() == JobRun.Status.FAILED && stillBackingOff(previous, hour)) {
                // It failed, and it is still inside its cool-down. A deterministic failure is not
                // hammered, and a matchday job's attempt is the whole matchday - so this is where a
                // broken matchday used to burn the world once a half hour, for ever.
                log.info("Leaving {} for season {} week {} day {} alone: it failed at hour {} and the "
                                + "next attempt waits {} hour(s).",
                        job.key(), seasonYear, weekNumber, dayNumber,
                        previous.getRanAtHour(), FAILED_BACKOFF_HOURS);
                outcome.put("status", "BACKING_OFF");
                return outcome;
            }
            log.info("Re-running {} for season {} week {} day {} after {}: {}",
                    job.key(), seasonYear, weekNumber, dayNumber,
                    previous.getStatus(), previous.getMessage());
        }

        JobRun record = existing.orElseGet(JobRun::new);
        record.setJobKey(job.key());
        record.setSeasonYear(seasonYear);
        record.setWeekNumber(weekNumber);
        record.setDayNumber(dayNumber);
        record.setRanAtHour(hour);

        // **The claim, written before the body runs (A1).**
        //
        // The guard used to be read unlocked and written *after* the body had already run and committed. Two
        // concurrent scans therefore both saw "no row", both ran the job, and the unique constraint only
        // rejected the second **save** — after both had done the work. The duplicate row was prevented and the
        // duplicate work was not.
        //
        // Writing the claim first in its own transaction means the second scanner collides on the unique
        // constraint *before* it runs anything, and is turned away.
        //
        // A PENDING row left behind means the run died, and under the owner's rule it is not DONE, so the
        // next scan re-claims it. There is no staleness timeout, which is what the earlier design of this
        // needed and did not have.
        if (existing.isEmpty()) {
            record.setStatus(JobRun.Status.PENDING);
            // Stamped at claim time, not at completion: this is how another scan tells "someone is in the
            // body right now" from "someone took this slot and died".
            record.setRanAt(Instant.now());
            try {
                requiresNew.executeWithoutResult(status -> runs.save(record));
            } catch (RuntimeException claimedTwice) {
                // Another scanner claimed this slot between our read and our write. That is the collision
                // this insert exists to cause, so it is a success for the guard and not an error.
                log.debug("Job {} for season {} week {} day {} was claimed by another scan; skipping.",
                        job.key(), seasonYear, weekNumber, dayNumber);
                outcome.put("status", "ALREADY_DONE");
                return outcome;
            }
        } else if (existing.get().getStatus() == JobRun.Status.PENDING && claimIsLive(existing.get())) {
            // **A PENDING stamped moments ago belongs to a scan that is inside the body right now.**
            //
            // This is the hole in claim-before-run: the winner of the insert race runs the body, but the
            // loser can arrive by *reading* the row rather than by attempting the insert — and under the
            // owner's rule "not DONE" means retry, so a live PENDING would be re-run by the very second
            // scanner the claim was added to stop.
            //
            // So the claim is only honoured while it is fresh. Past that window the scan that took it is
            // gone — crashed, killed, or the machine rebooted — and the slot is genuinely abandoned and is
            // picked up again. A ten-minute window against a job that runs in seconds is generous; it
            // exists so a killed process cannot leave a matchday unplayed for ever.
            log.debug("Job {} for season {} week {} day {} is already claimed by a live scan; skipping.",
                    job.key(), seasonYear, weekNumber, dayNumber);
            outcome.put("status", "ALREADY_RUNNING");
            return outcome;
        } else {
            // PENDING but stale, or FAILED: nobody is working on this slot, so take it over. The owner's rule
            // is "if it does not execute successfully the status is not DONE and the scheduler picks it up
            // next time", and a stale claim is what that looks like after a crash.
            //
            // The message is cleared here, and that is not cosmetic. It belongs to the attempt being
            // replaced: leaving it on the row produced a state the model does not allow — a PENDING row that
            // carries a failure reason — and it read as though the *current* attempt had already failed.
            // Found in the live database on 2026-10-19, where the day-1 matchday prepare row read PENDING
            // with "CompletionException: java.lang.OutOfMemoryError: Java heap space": a message from an
            // attempt that had already ended, on a row claiming the slot was still running.
            record.setStatus(JobRun.Status.PENDING);
            record.setRanAt(Instant.now());
            record.setMessage(null);
            requiresNew.executeWithoutResult(status -> runs.save(record));
        }

        try {
            // A job that manages its own transaction boundaries runs outside this one. See
            // DayJob.transactionalRun(): wrapping it again would deadlock its per-fixture commits.
            JobContext context = new JobContext(seasonYear, weekNumber, dayNumber, hour);
            if (job.transactionalRun()) {
                requiresNew.executeWithoutResult(status -> job.run(context));
            } else {
                job.run(context);
            }
            if (!job.countsAsDone(context)) {
                // The body ran but had nothing to do, and a fixture for this day may still be drawn — see
                // DayJob.countsAsDone. The claim is released rather than left PENDING so the very next scan
                // can take it; a live PENDING would hold the slot for the staleness window and hide the
                // very thing this exists to catch.
                requiresNew.executeWithoutResult(status -> runs.delete(record));
                outcome.put("status", "IDLE");
                outcome.put("message", "nothing to do; left eligible for a fixture drawn later");
                log.info("Job {} found nothing for season {} week {} day {}; slot left open.",
                        job.key(), seasonYear, weekNumber, dayNumber);
                return outcome;
            }
            record.setStatus(JobRun.Status.DONE);
            record.setRanAt(Instant.now());
            record.setMessage(null);
            requiresNew.executeWithoutResult(status -> runs.save(record));
            outcome.put("status", "DONE");
            log.info("Job {} ran for season {} week {} day {} at {}:00.", job.key(), seasonYear, weekNumber, dayNumber, hour);
        } catch (RuntimeException | Error e) {
            // **An `Error` is caught here too, and that is deliberate.**
            //
            // The catch used to be `RuntimeException` only, so a body that died with an `OutOfMemoryError`
            // or a `StackOverflowError` escaped it completely: the row stayed PENDING for ever, the
            // exception propagated out of `runDue` and out of the clock walk, and no FAILED row was ever
            // written. The owner's rule — "if it does not execute successfully the status is not DONE and
            // the scheduler picks it up next time" — could not apply to a row that was never told the
            // attempt had failed. The day-1 matchday prepare row was found in exactly that state.
            //
            // It is caught rather than rethrown because of what this class promises: "A job that throws is
            // recorded as FAILED and the runner continues to the next one. One broken job must not freeze
            // the season." A job that exhausts the heap is still one broken job, and the honest record of it
            // is a FAILED row the next scan can pick up — not a stack trace out of the clock advance.
            //
            // The write itself can still fail if the heap is genuinely exhausted, and then the exception
            // escapes. That is unavoidable and correct: there is nothing left to record it with.
            record.setStatus(JobRun.Status.FAILED);
            record.setRanAt(Instant.now());
            record.setMessage(truncate(e.getClass().getSimpleName() + ": " + e.getMessage()));
            requiresNew.executeWithoutResult(status -> runs.save(record));
            outcome.put("status", "FAILED");
            outcome.put("message", record.getMessage());
            log.error("Job {} FAILED for season {} week {} day {} at {}:00. Carrying on with the rest.",
                    job.key(), seasonYear, weekNumber, dayNumber, hour, e);
        }
        return outcome;
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 500 ? message : message.substring(0, 497) + "...";
    }

    /** Every job this runner knows about, with its trigger, for the admin screen. */
    public List<Map<String, Object>> describeJobs() {
        return jobs.stream().map(job -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", job.key());
            row.put("week", job.week() == DayJob.ANY_WEEK ? "every" : job.week());
            row.put("day", job.day() == DayJob.ANY_DAY ? "every" : job.day());
            row.put("hour", job.hour());
            row.put("minute", job.minute());
            row.put("trigger", String.format("%02d:%02d", job.hour(), job.minute()));
            row.put("order", job.order());
            return row;
        }).toList();
    }
}
