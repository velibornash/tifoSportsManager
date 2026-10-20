package org.tifo.footballmanager.newLogic.jobs;

import org.tifo.footballmanager.BaseTest;
import org.tifo.footballmanager.newLogic.repository.JobRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A matchday that found nothing is not a matchday that happened.
 *
 * <p><b>The defect this guards (owner, 2026-10-19: <i>"job ili nije opalio ili nije pokupio mec"</i>).</b>
 * An international fixture sat in the schedule and never played. The log gave the whole story:
 *
 * <pre>
 *   12:00:00.166  Job matchday-prepare-1  ran for season 1 week 1 day 1 at 19:00
 *   12:00:00.215  Job matchday-publish-1  ran for season 1 week 1 day 1 at 20:00
 *   12:01:05.867  Cup draw: 54 tie(s) drawn for round 1        &lt;-- 65 seconds LATER
 * </pre>
 *
 * <p>The matchday ran first, found nothing, and was recorded DONE. The cup draw then wrote a tie into that
 * same day. DONE is terminal for a day, so nothing ever picked it up, and every subsequent advance reported
 * "No jobs were due" — which is what the owner saw.
 *
 * <p><b>Why a hand-built job rather than a real matchday.</b> This test is about the memo, not about
 * football. What has to be proved is that a job which declares itself not-done is left eligible, and that a
 * job which does its work is still recorded and is not re-run all day. A real matchday would make the
 * second half of that depend on 155 fixtures existing.
 *
 * <p><b>Why it cannot pass by accident.</b> Both halves are asserted against the database, not against a
 * mock: the DONE row either exists or it does not. Deleting the {@code countsAsDone} override from the
 * fixture job turns the first assertion red and leaves the second green, which is exactly the shape of the
 * regression.
 */
class JobThatFoundNothingStaysEligibleTest extends BaseTest {

    private static final int SEASON = 41;
    private static final int WEEK = 3;
    private static final int DAY = 2;
    private static final int HOUR = 19;

    @Autowired JobRunRepository runs;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    /**
     * A runner holding only the job under test.
     *
     * <p>The autowired runner runs every registered {@code DayJob}, so a test job registered as a bean would
     * fire in every other test in the suite. Building the runner directly keeps the test to one job and lets
     * the other half of the file — the job that must still be memoised — be proved on the same machinery.
     */
    private JobRunner runnerFor(DayJob job) {
        return new JobRunner(List.of(job), runs, transactionManager);
    }

    /** A job that finds nothing, which is the whole case. */
    private static final class IdleJob implements DayJob {
        private int runs = 0;

        @Override public String key() { return "test-idle-job"; }
        @Override public int week() { return ANY_WEEK; }
        @Override public int day() { return DAY; }
        @Override public int hour() { return HOUR; }
        @Override public void run(JobContext context) { runs++; }

        @Override
        public boolean countsAsDone(JobContext context) {
            return false;
        }
    }

    /** A job that does its work, which is every other job and must not be disturbed. */
    private static final class BusyJob implements DayJob {
        private int runs = 0;

        @Override public String key() { return "test-busy-job"; }
        @Override public int week() { return ANY_WEEK; }
        @Override public int day() { return DAY; }
        @Override public int hour() { return HOUR; }
        @Override public void run(JobContext context) { runs++; }
    }

    @Test
    @DisplayName("a job that found nothing is not recorded DONE, so it runs again")
    void anIdleJobStaysEligible() {
        IdleJob job = new IdleJob();

        // Two separate scans, as two clock ticks would be. The second is the one the owner was waiting for:
        // the fixture exists by then.
        runnerFor(job).runDue(SEASON, WEEK, DAY, HOUR);
        runnerFor(job).runDue(SEASON, WEEK, DAY, HOUR);

        assertThat(runs.findBySeasonYearAndWeekNumberAndDayNumberAndJobKey(SEASON, WEEK, DAY, "test-idle-job"))
                .as("A job that found nothing must not leave a DONE row, or the day is closed for good.")
                .isEmpty();
        assertThat(job.runs)
                .as("The job was marked done after finding nothing, so the second scan skipped it.")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a job that did its work is still recorded and is not re-run all day")
    void aBusyJobIsStillMemoised() {
        BusyJob job = new BusyJob();

        runnerFor(job).runDue(SEASON, WEEK, DAY, HOUR);
        runnerFor(job).runDue(SEASON, WEEK, DAY, HOUR);
        runnerFor(job).runDue(SEASON, WEEK, DAY, HOUR);

        assertThat(runs.findBySeasonYearAndWeekNumberAndDayNumberAndJobKey(SEASON, WEEK, DAY, "test-busy-job"))
                .as("The default must stay 'a job that ran, ran' — the memo is what stops the scheduler "
                        + "re-issuing a guard query 264 times a game day.")
                .isPresent();
        assertThat(job.runs)
                .as("The day memo was dropped for jobs that do real work, which is the whole cost the "
                        + "memo exists to avoid.")
                .isEqualTo(1);
    }
}