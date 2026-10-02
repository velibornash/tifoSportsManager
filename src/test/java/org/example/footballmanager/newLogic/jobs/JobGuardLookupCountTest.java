package org.example.footballmanager.newLogic.jobs;

import jakarta.persistence.EntityManager;
import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.repository.JobRunRepository;
import org.example.footballmanager.newLogic.repository.GameClockRepository;
import org.example.footballmanager.newLogic.service.SeasonService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A job that has already run is not asked about again.
 *
 * <p><b>D4.</b> {@code isDue} is {@code hour >= job.hour()}, so a job at 09:00 counts as due for the next
 * fifteen hours. {@code runDue} runs every hour, so the guard lookup inside {@code runOnce} was issued
 * **11 jobs × 24 hours = 264 times a game day** — two hundred and sixty-four database round trips to
 * re-learn something that had not changed.
 *
 * <p><b>The assertion counts statements, not intentions.</b> Hibernate statistics are enabled for the test
 * profile precisely because a count taken from a counter that was never recording compares zeroes and passes
 * for ever — which has happened here more than once.
 */
class JobGuardLookupCountTest extends BaseTest {

    @Autowired private JobRunRepository runs;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired private GameClockRepository clocks;
    @Autowired private SeasonService seasons;
    @Autowired private EntityManager entityManager;
    @Autowired private org.hibernate.SessionFactory sessionFactory;

    @Transactional
    @Test
    @DisplayName("a day of hourly scans does not re-ask the database for a job that already ran")
    void aDayOfScansDoesNotReAsk() {
        CountingJob job = new CountingJob();
        JobRunner runner = new JobRunner(java.util.List.of(job), runs, transactionManager);

        long before = statementsFor(() -> runner.runDue(2026, 1, 1, 9));

        long after = 0;
        for (int hour = 10; hour <= 23; hour++) {
            final int scanned = hour;
            after += statementsFor(() -> runner.runDue(2026, 1, 1, scanned));
        }

        // The first scan of the day asks; the next fifteen do not. And the counter must be live, or both
        // numbers are meaningless and this test passes for the wrong reason.
        assertTrue(before > 0,
                "the statement counter recorded nothing even for a scan that queries the database, so it "
                        + "is not enabled and both numbers below prove nothing.");
        assertTrue(before > 0 && before < 10,
                "the first scan should ask the database a couple of times, and issued " + before
                        + " statements. Not exactly one: a job that has never run is claimed with a PENDING "
                        + "insert and then updated to DONE, so two statements is right and one is not.");
        assertEquals(0L, after,
                "fifteen further hourly scans issued " + after + " statements about a job that already "
                        + "ran. The guard is answered from the day's memo, and this is the whole of D4.");
    }

    @Transactional
    @Test
    @DisplayName("a job that fails is still asked on the next hour - the retry must not be memoised")
    void aFailedJobIsNotMemoised() {
        AtomicInteger attempts = new AtomicInteger();
        DayJob flaky = new DayJob() {
            @Override
            public String key() {
                return "memo-flaky";
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
                if (attempts.incrementAndGet() == 1) {
                    throw new IllegalStateException("transient");
                }
            }
        };
        JobRunner runner = new JobRunner(java.util.List.of(flaky), runs, transactionManager);

        runner.runDue(2027, 1, 1, 9);   // fails
        runner.runDue(2027, 1, 1, 10);  // must be retried, not served from the memo

        assertEquals(2, attempts.get(),
                "a failed job was memoised as done and never retried. A2 promises the next scan picks it "
                        + "up, and only DONE may be cached.");
    }

    @Transactional
    @Test
    @DisplayName("the memo is per game day, so tomorrow is asked again")
    void theMemoIsPerGameDay() {
        CountingJob job = new CountingJob();
        JobRunner runner = new JobRunner(java.util.List.of(job), runs, transactionManager);

        runner.runDue(2028, 1, 1, 9);
        long statementsOnDayTwo = statementsFor(() -> runner.runDue(2028, 1, 2, 9));

        assertTrue(statementsOnDayTwo > 0,
                "the next game day asked nothing. A DONE record is keyed on (season, week, day, key), so "
                        + "day two is a different slot and must be checked properly.");
    }

    /**
     * Statements issued while running the given work.
     *
     * <p>Statistics are enabled explicitly rather than assumed, and the first test asserts the counter
     * moves. An earlier version of this read zero for <i>every</i> scan - including the first, which does
     * query - and reported 'the memo eliminated all the queries', which was the counter being off rather
     * than the code being fast.
     */
    private long statementsFor(Runnable work) {
        sessionFactory.getStatistics().setStatisticsEnabled(true);
        sessionFactory.getStatistics().clear();
        work.run();
        return sessionFactory.getStatistics().getPrepareStatementCount();
    }

    /** A job that succeeds and counts how often it ran. */
    private static final class CountingJob implements DayJob {
        private final AtomicInteger runs = new AtomicInteger();

        @Override
        public String key() {
            return "memo-counting";
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
        public int order() {
            return 50;
        }

        @Override
        public void run(JobContext context) {
            runs.incrementAndGet();
        }

        int runs() {
            return runs.get();
        }
    }
}
