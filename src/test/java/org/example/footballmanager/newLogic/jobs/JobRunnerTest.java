package org.example.footballmanager.newLogic.jobs;

import org.example.footballmanager.newLogic.model.JobRun;
import org.example.footballmanager.newLogic.repository.JobRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The guarantees the job framework exists to provide.
 *
 * <p>These are not incidental properties. Each test here corresponds to a way the design can be
 * wrong in a way that only shows up once money or a season is involved: a job running twice, one
 * broken job freezing the rest, a trigger firing before its hour, or ordering depending on
 * classpath order rather than on a declared dependency.
 */
class JobRunnerTest {

    private final JobRunRepository runs = mock(JobRunRepository.class);
    private final List<JobRun> stored = new ArrayList<>();

    private JobRunner runner(DayJob... jobs) {
        when(runs.findBySeasonYearAndWeekNumberAndDayNumberAndJobKey(any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Integer season = invocation.getArgument(0);
                    Integer week = invocation.getArgument(1);
                    Integer day = invocation.getArgument(2);
                    String key = invocation.getArgument(3);
                    return stored.stream()
                            .filter(r -> r.getSeasonYear().equals(season)
                                    && r.getWeekNumber().equals(week)
                                    && r.getDayNumber().equals(day)
                                    && r.getJobKey().equals(key))
                            .findFirst();
                });
        when(runs.save(any(JobRun.class))).thenAnswer(invocation -> {
            JobRun saved = invocation.getArgument(0);
            stored.add(saved);
            return saved;
        });
        return new JobRunner(List.of(jobs), runs, new NoopTransactionManager());
    }

    /** Minimal manager so the runner's TransactionTemplate works without a database. */
    private static final class NoopTransactionManager implements org.springframework.transaction.PlatformTransactionManager {
        @Override
        public org.springframework.transaction.TransactionStatus getTransaction(
                org.springframework.transaction.TransactionDefinition definition) {
            return new org.springframework.transaction.support.SimpleTransactionStatus();
        }

        @Override
        public void commit(org.springframework.transaction.TransactionStatus status) {
        }

        @Override
        public void rollback(org.springframework.transaction.TransactionStatus status) {
        }
    }

    private record CountingJob(String name, int week, int day, int hour, int order, AtomicInteger runs)
            implements DayJob {
        @Override
        public String key() {
            return name;
        }

        @Override
        public int week() {
            return week;
        }

        @Override
        public int day() {
            return day;
        }

        @Override
        public int hour() {
            return hour;
        }

        @Override
        public int order() {
            return order;
        }

        @Override
        public void run(JobContext context) {
            runs.incrementAndGet();
        }
    }

    private CountingJob job(String name, int week, int day, int hour, int order) {
        return new CountingJob(name, week, day, hour, order, new AtomicInteger());
    }

    @Test
    @DisplayName("a job at 14:00 does not fire at 09:00 - the reason this framework exists")
    void triggerIsNotDueBeforeItsHour() {
        CountingJob afternoon = job("afternoon", DayJob.ANY_WEEK, 2, 14, 10);
        CountingJob later = job("later", DayJob.ANY_WEEK, 2, 20, 10);

        runner(afternoon, later).runDue(2025, 1, 2, 9);

        assertEquals(0, afternoon.runs().get(), "must not fire before its hour");
        assertEquals(0, later.runs().get(), "must not fire before its hour");
    }

    @Test
    @DisplayName("advancing through the same hour repeatedly runs a job exactly once")
    void isIdempotentAcrossRepeatedScans() {
        CountingJob finance = job("finance", DayJob.ANY_WEEK, DayJob.ANY_DAY, 9, 10);
        JobRunner runner = runner(finance);

        // What advancing 24 hours looks like: the same trigger scanned over and over.
        for (int scan = 0; scan < 24; scan++) {
            runner.runDue(2025, 1, 2, 9);
        }

        assertEquals(1, finance.runs().get(),
                "finance applied once, not 24 times - this is the whole point of the done-flag");
    }

    @Test
    @DisplayName("a new day re-runs the job: the flag is per day, not per season")
    void flagIsPerDay() {
        CountingJob daily = job("daily", DayJob.ANY_WEEK, DayJob.ANY_DAY, 9, 10);
        JobRunner runner = runner(daily);

        runner.runDue(2025, 1, 1, 9);
        runner.runDue(2025, 1, 2, 9);

        assertEquals(2, daily.runs().get(), "a new day is a new job run");
    }

    @Test
    @DisplayName("a job that throws is recorded FAILED and does not stop the ones after it")
    void failureIsContained() {
        DayJob broken = new DayJob() {
            @Override
            public String key() {
                return "broken";
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
                throw new IllegalStateException("boom");
            }
        };
        CountingJob after = job("after", DayJob.ANY_WEEK, DayJob.ANY_DAY, 9, 50);

        var result = runner(broken, after).runDue(2025, 1, 1, 9);

        assertEquals(1, after.runs().get(), "a broken job must not freeze the season");
        assertEquals(1L, result.get("failed"));
        JobRun failed = stored.stream().filter(r -> r.getJobKey().equals("broken")).findFirst().orElseThrow();
        assertEquals(JobRun.Status.FAILED, failed.getStatus());
        assertTrue(failed.getMessage().contains("boom"), "the reason must be diagnosable");
    }

    @Test
    @DisplayName("a FAILED job is not retried automatically - a half-applied job must not run twice")
    void failureIsNotRetriedBlindly() {
        AtomicInteger attempts = new AtomicInteger();
        DayJob broken = new DayJob() {
            @Override
            public String key() {
                return "flaky";
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
                attempts.incrementAndGet();
                throw new IllegalStateException("boom");
            }
        };
        JobRunner runner = runner(broken);

        runner.runDue(2025, 1, 1, 9);
        runner.runDue(2025, 1, 1, 10);

        assertEquals(1, attempts.get(), "must not re-run a job that already failed");
    }

    @Test
    @DisplayName("a job at 00:00 is due on the first scan of the day")
    void midnightJobIsDueImmediately() {
        CountingJob opening = job("opening", DayJob.ANY_WEEK, DayJob.ANY_DAY, 0, 0);
        runner(opening).runDue(2025, 1, 1, 0);
        assertEquals(1, opening.runs().get());
    }

    @Test
    @DisplayName("ordering comes from order(), not from injection order")
    void orderingIsExplicit() {
        StringBuilder sequence = new StringBuilder();
        DayJob late = named("late", 50, sequence);
        DayJob early = named("early", 10, sequence);

        // Declared in the wrong order on purpose: the runner must still run early first.
        runner(late, early).runDue(2025, 1, 1, 9);

        assertEquals("early,late", sequence.toString(),
                "classpath order must not decide the sequence of financial effects");
    }

    @Test
    @DisplayName("week and day wildcards match every week and day")
    void wildcardsMatch() {
        // Any week, but only day 3. And week 7, but any day.
        CountingJob weekly = job("weekly", DayJob.ANY_WEEK, 3, 9, 10);
        CountingJob anyDay = job("any-day", 7, DayJob.ANY_DAY, 9, 10);

        runner(weekly, anyDay).runDue(2025, 7, 3, 9);

        assertEquals(1, weekly.runs().get(), "ANY_WEEK must match week 7");
        assertEquals(1, anyDay.runs().get(), "ANY_DAY must match day 3");

        CountingJob wrongDay = job("wrong-day", DayJob.ANY_WEEK, 4, 9, 10);
        runner(wrongDay).runDue(2025, 7, 3, 9);
        assertEquals(0, wrongDay.runs().get(), "day 4 must not fire on day 3");

        CountingJob otherWeek = job("other-week", 6, 3, 9, 10);
        runner(otherWeek).runDue(2025, 7, 3, 9);
        assertEquals(0, otherWeek.runs().get(), "week 6 must not fire during week 7");
    }

    private DayJob named(String name, int order, StringBuilder sequence) {
        return new DayJob() {
            @Override
            public String key() {
                return name;
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
                return order;
            }

            @Override
            public void run(JobContext context) {
                sequence.append(sequence.isEmpty() ? "" : ",").append(name);
            }
        };
    }

    @Test
    @DisplayName("a job key that changes would re-fire; the key is part of the uniqueness contract")
    void keyIsPartOfTheIdentity() {
        CountingJob original = job("finance", DayJob.ANY_WEEK, DayJob.ANY_DAY, 9, 10);
        JobRunner runner = runner(original);
        runner.runDue(2025, 1, 1, 9);

        // A second job under a different key is a different job, and runs. This is why renaming a
        // job key mid-season is dangerous and is called out on DayJob.key().
        CountingJob renamed = job("finance-v2", DayJob.ANY_WEEK, DayJob.ANY_DAY, 9, 10);
        runner(renamed).runDue(2025, 1, 1, 9);
        assertEquals(1, renamed.runs().get());
        assertNotEquals(original.key(), renamed.key());
    }
}
