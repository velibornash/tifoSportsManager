package org.example.footballmanager.newLogic.jobs;

import org.example.footballmanager.newLogic.model.JobRun;
import org.example.footballmanager.newLogic.repository.JobRunRepository;
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
import java.util.List;
import java.util.Map;

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
 */
@Service
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final List<DayJob> jobs;
    private final JobRunRepository runs;
    private final TransactionTemplate requiresNew;

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
     * @return one entry per job considered, so the caller can see what ran, what was already done
     *         and what failed. Returning the detail rather than a count is what makes an advancing
     *         clock diagnosable instead of opaque.
     */
    public Map<String, Object> runDue(int seasonYear, int weekNumber, int dayNumber, int hour) {
        List<Map<String, Object>> outcomes = new ArrayList<>();
        for (DayJob job : jobs) {
            if (!isDue(job, weekNumber, dayNumber, hour)) {
                continue;
            }
            outcomes.add(runOnce(job, seasonYear, weekNumber, dayNumber, hour));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("season", seasonYear);
        result.put("week", weekNumber);
        result.put("day", dayNumber);
        result.put("hour", hour);
        result.put("jobs", outcomes);
        result.put("ran", outcomes.stream().filter(o -> "DONE".equals(o.get("status"))).count());
        result.put("skipped", outcomes.stream().filter(o -> "ALREADY_DONE".equals(o.get("status"))).count());
        result.put("failed", outcomes.stream().filter(o -> "FAILED".equals(o.get("status"))).count());
        return result;
    }

    /**
     * Whether a job's trigger has been reached.
     *
     * <p>The hour comparison is not "greater or equal" in general: a job at 14:00 is not due at
     * 09:00, which is the case that made this whole framework necessary. A job at 00:00 is due from
     * the first hour of the day.
     */
    private boolean isDue(DayJob job, int weekNumber, int dayNumber, int hour) {
        if (job.week() != DayJob.ANY_WEEK && job.week() != weekNumber) {
            return false;
        }
        if (job.day() != DayJob.ANY_DAY && job.day() != dayNumber) {
            return false;
        }
        return hour >= job.hour();
    }

    private Map<String, Object> runOnce(DayJob job, int seasonYear, int weekNumber, int dayNumber, int hour) {
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("key", job.key());
        outcome.put("trigger", "w" + (job.week() == DayJob.ANY_WEEK ? "*" : job.week())
                + " d" + (job.day() == DayJob.ANY_DAY ? "*" : job.day())
                + " " + job.hour() + ":00");

        var existing = runs.findBySeasonYearAndWeekNumberAndDayNumberAndJobKey(
                seasonYear, weekNumber, dayNumber, job.key());

        if (existing.isPresent() && existing.get().getStatus() == JobRun.Status.DONE) {
            // The whole reason this class exists.
            outcome.put("status", "ALREADY_DONE");
            return outcome;
        }
        if (existing.isPresent() && existing.get().getStatus() == JobRun.Status.FAILED) {
            // Not retried automatically: a job that already threw once may have applied half its
            // work, and re-running it blindly is how a double-apply happens.
            outcome.put("status", "FAILED");
            outcome.put("message", existing.get().getMessage());
            return outcome;
        }

        JobRun record = existing.orElseGet(JobRun::new);
        record.setJobKey(job.key());
        record.setSeasonYear(seasonYear);
        record.setWeekNumber(weekNumber);
        record.setDayNumber(dayNumber);
        record.setRanAtHour(hour);

        try {
            requiresNew.executeWithoutResult(status ->
                    job.run(new JobContext(seasonYear, weekNumber, dayNumber, hour)));
            record.setStatus(JobRun.Status.DONE);
            record.setRanAt(Instant.now());
            record.setMessage(null);
            requiresNew.executeWithoutResult(status -> runs.save(record));
            outcome.put("status", "DONE");
            log.info("Job {} ran for season {} week {} day {} at {}:00.", job.key(), seasonYear, weekNumber, dayNumber, hour);
        } catch (RuntimeException e) {
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
            row.put("order", job.order());
            return row;
        }).toList();
    }
}
