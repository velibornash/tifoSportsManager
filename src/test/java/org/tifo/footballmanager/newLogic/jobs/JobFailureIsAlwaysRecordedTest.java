package org.tifo.footballmanager.newLogic.jobs;

import org.tifo.footballmanager.BaseTest;
import org.tifo.footballmanager.newLogic.model.JobRun;
import org.tifo.footballmanager.newLogic.repository.JobRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A job that dies must always leave a record that says so.
 *
 * <p><b>The defect this guards (found in the live database, 2026-10-19).</b> The day-1 matchday prepare row
 * read:
 *
 * <pre>
 *   job_key = matchday-prepare-1   status = PENDING
 *   message = CompletionException: java.lang.OutOfMemoryError: Java heap space
 * </pre>
 *
 * <p>A {@code PENDING} row is a <em>claim</em>: it means a scan took the slot and the body is running. The
 * entity's own javadoc says so — a PENDING row means "a scan has taken this slot and the body is running, or
 * the process died before it could say otherwise". A PENDING row that also carries a failure message is a
 * state the model does not allow, and reading it told the owner two contradictory things at once: that the
 * attempt was still running, and that it had already failed.
 *
 * <p>Two separate defects produced it, and both are fixed here:
 *
 * <ol>
 *   <li><b>The message was never cleared when a failed slot was taken over.</b> The takeover branch reset the
 *       status to {@code PENDING} and left the previous attempt's failure text on the row, so the row carried a
 *       reason that described an attempt which had already ended.</li>
 *   <li><b>An {@code Error} was not caught.</b> The runner caught {@code RuntimeException} only, so a body that
 *       died with an {@code OutOfMemoryError} or a {@code StackOverflowError} escaped the catch entirely: the
 *       row stayed {@code PENDING} for ever, the exception propagated out of the clock walk, and no
 *       {@code FAILED} row was ever written. The owner's rule — "if it does not execute successfully the
 *       status is not DONE and the scheduler picks it up next time" — could not apply to a row that was never
 *       told the attempt had failed.</li>
 * </ol>
 *
 * <p><b>Why it cannot pass by accident.</b> The first test asserts against the database, not a mock: the row
 * either says {@code FAILED} or it does not. Reverting the {@code Error} catch makes it red. The second test
 * has the job body read its own row from the database while it is running, so the stale message is observed
 * exactly where it was found — on a row that claims to be in flight. Reverting the message clear makes it red.
 */
class JobFailureIsAlwaysRecordedTest extends BaseTest {

    /** A season/week/day nothing else in the suite writes to. */
    private static final int SEASON = 77;
    private static final int WEEK = 5;
    private static final int DAY = 3;
    private static final int HOUR = 11;

    @Autowired JobRunRepository runs;
    @Autowired PlatformTransactionManager transactionManager;

    /**
     * A runner holding only the job under test.
     *
     * <p>The autowired runner runs every registered {@code DayJob}, so a test job registered as a bean would
     * fire in every other test in the suite.
     */
    private JobRunner runnerFor(DayJob job) {
        return new JobRunner(List.of(job), runs, transactionManager);
    }

    private JobRun rowFor(String key) {
        return runs.findBySeasonYearAndWeekNumberAndDayNumberAndJobKey(SEASON, WEEK, DAY, key)
                .orElse(null);
    }

    @Test
    @DisplayName("a job that dies with an Error is recorded FAILED, not left claimed for ever")
    void anErrorIsRecordedLikeAnyOtherFailure() {
        DayJob broken = new DayJob() {
            @Override public String key() { return "test-error-job"; }
            @Override public int week() { return ANY_WEEK; }
            @Override public int day() { return DAY; }
            @Override public int hour() { return HOUR; }
            @Override public void run(JobContext context) {
                // An Error, not a RuntimeException. The runner used to catch only RuntimeException, so this
                // escaped the catch, left the row PENDING and propagated out of the clock walk.
                throw new StackOverflowError("simulated");
            }
        };

        runnerFor(broken).runDue(SEASON, WEEK, DAY, HOUR);

        JobRun row = rowFor("test-error-job");
        assertThat(row)
                .as("The slot must still have a row: the claim is what stops a second scan running the job.")
                .isNotNull();
        assertThat(row.getStatus())
                .as("A job that died with an Error must be recorded FAILED. Left PENDING, the row claims the "
                        + "slot is still running, the exception escapes the clock walk, and the next scan "
                        + "waits out the staleness window before retrying a job that already failed.")
                .isEqualTo(JobRun.Status.FAILED);
        assertThat(row.getMessage())
                .as("The reason must be diagnosable without a debugger.")
                .contains("StackOverflowError");
    }

    @Test
    @DisplayName("a retried job does not carry the previous attempt's failure message while it runs")
    void aRetriedJobDoesNotInheritTheOldMessage() {
        AtomicReference<JobRun> seenWhileRunning = new AtomicReference<>();

        // Fails on the first attempt, succeeds on the second. The second attempt is the one that reads its
        // own row, because the row is PENDING for exactly as long as the body runs.
        DayJob flaky = new DayJob() {
            private int attempts = 0;

            @Override public String key() { return "test-flaky-job"; }
            @Override public int week() { return ANY_WEEK; }
            @Override public int day() { return DAY; }
            @Override public int hour() { return HOUR; }
            @Override public void run(JobContext context) {
                if (attempts++ == 0) {
                    throw new IllegalStateException("first attempt fails");
                }
                seenWhileRunning.set(rowFor("test-flaky-job"));
            }
        };

        JobRunner runner = runnerFor(flaky);
        runner.runDue(SEASON, WEEK, DAY, HOUR);
        // The retry waits out the cool-down: a failure is never terminal, but it is not retried on
        // every scan either (JobRunner.FAILED_BACKOFF_HOURS).
        runner.runDue(SEASON, WEEK, DAY, HOUR + JobRunner.FAILED_BACKOFF_HOURS);

        JobRun duringSecondAttempt = seenWhileRunning.get();
        assertThat(duringSecondAttempt)
                .as("The job body must have been able to read its own row while it was running.")
                .isNotNull();
        assertThat(duringSecondAttempt.getStatus())
                .as("While the body runs, the row is a claim.")
                .isEqualTo(JobRun.Status.PENDING);
        assertThat(duringSecondAttempt.getMessage())
                .as("The message belongs to the attempt that is being replaced. Left on the row, a PENDING row "
                        + "carries a failure reason that describes an attempt which already ended — which is "
                        + "the exact row found in the live database on 2026-10-19.")
                .isNull();
    }

    @Test
    @DisplayName("a job that recovers is DONE and carries no message")
    void aRecoveredJobIsDoneAndClean() {
        DayJob flaky = new DayJob() {
            private int attempts = 0;

            @Override public String key() { return "test-recovered-job"; }
            @Override public int week() { return ANY_WEEK; }
            @Override public int day() { return DAY; }
            @Override public int hour() { return HOUR; }
            @Override public void run(JobContext context) {
                if (attempts++ == 0) {
                    throw new IllegalStateException("first attempt fails");
                }
            }
        };

        JobRunner runner = runnerFor(flaky);
        runner.runDue(SEASON, WEEK, DAY, HOUR);
        // The retry waits out the cool-down: a failure is never terminal, but it is not retried on
        // every scan either (JobRunner.FAILED_BACKOFF_HOURS).
        runner.runDue(SEASON, WEEK, DAY, HOUR + JobRunner.FAILED_BACKOFF_HOURS);

        JobRun row = rowFor("test-recovered-job");
        assertThat(row).isNotNull();
        assertThat(row.getStatus())
                .as("A job that succeeded on the retry is DONE for the day.")
                .isEqualTo(JobRun.Status.DONE);
        assertThat(row.getMessage())
                .as("DONE means it worked. The earlier failure must not still be on the row.")
                .isNull();
    }
}
