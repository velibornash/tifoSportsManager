package org.example.footballmanager.newLogic.jobs;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.JobRun;
import org.example.footballmanager.newLogic.repository.JobRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two scanners, one body run.
 *
 * <p><b>This is the test A1 said could not be written.</b> The guard used to be read unlocked and written
 * <i>after</i> the job body had run and committed. Two concurrent scans both saw "no row", both ran the body,
 * and the unique constraint rejected only the second <b>save</b> — after both had done the work. The duplicate
 * row was prevented and the duplicate work was not, which is invisible to any single-threaded test.
 *
 * <p>So this runs against the real repository and a real database, with the body held open on a latch so the
 * two scans genuinely overlap. A mock repository cannot express the collision: it has no unique constraint to
 * lose.
 *
 * <p>The claim is now inserted before the body, so the loser of the race is turned away at the insert.
 */
class JobClaimConcurrencyTest extends BaseTest {

    @Autowired
    JobRunRepository runs;

    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("two concurrent scans run the body once")
    void twoConcurrentScansRunTheBodyOnce() throws Exception {
        AtomicInteger bodyRuns = new AtomicInteger();
        CountDownLatch bodyEntered = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);

        DayJob slowJob = new DayJob() {
            @Override
            public String key() {
                return "slow-claim";
            }

            @Override
            public int week() {
                return ANY_WEEK;
            }

            @Override
            public int day() {
                return ANY_DAY;
            }

            @Override
            public int hour() {
                return 9;
            }

            @Override
            public void run(JobContext context) {
                bodyRuns.incrementAndGet();
                // Hold the body open so the second scanner arrives while the first is still inside it. Without
                // this the two scans are merely fast, and the test proves nothing about a race.
                bodyEntered.countDown();
                try {
                    releaseBody.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };

        JobRunner runner = new JobRunner(List.of(slowJob), runs, transactionManager);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> runner.runDue(2025, 1, 1, 9));
            assertTrue(bodyEntered.await(5, TimeUnit.SECONDS), "the first scan never reached the job body");

            var second = pool.submit(() -> runner.runDue(2025, 1, 1, 9));

            // Give the second scanner time to read the guard and try to claim, while the first is still
            // inside the body.
            Thread.sleep(400);
            releaseBody.countDown();

            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            releaseBody.countDown();
            pool.shutdownNow();
        }

        assertEquals(1, bodyRuns.get(),
                "the job body ran " + bodyRuns.get() + " times for one slot. The claim is written before the "
                        + "body, so the second scanner should be turned away at the insert - the unique "
                        + "constraint saves the duplicate row only if nothing runs before it.");
    }

    /**
     * And the claim itself is visible: a slot that has been taken reads PENDING, which is what "in flight"
     * looks like in the database.
     */
    @Test
    @Transactional
    @DisplayName("a finished job is DONE and stays DONE")
    void aFinishedJobIsDone() {
        DayJob quick = new DayJob() {
            @Override
            public String key() {
                return "quick-claim";
            }

            @Override
            public int week() {
                return ANY_WEEK;
            }

            @Override
            public int day() {
                return ANY_DAY;
            }

            @Override
            public int hour() {
                return 9;
            }

            @Override
            public void run(JobContext context) {
            }
        };

        JobRunner runner = new JobRunner(List.of(quick), runs, transactionManager);

        runner.runDue(2026, 2, 1, 9);
        runner.runDue(2026, 2, 1, 10);

        List<JobRun> stored = runs.findBySeasonYearAndWeekNumberOrderByDayNumberAscRanAtHourAsc(2026, 2)
                .stream()
                .filter(r -> "quick-claim".equals(r.getJobKey()))
                .toList();
        assertEquals(1, stored.size(), "one slot should exist, not " + stored.size());
        assertEquals(JobRun.Status.DONE, stored.get(0).getStatus());
    }
}
