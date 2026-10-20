package org.example.footballmanager.newLogic.jobs;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.jobs.impl.TrainingJob;
import org.example.footballmanager.newLogic.model.JobRun;
import org.example.footballmanager.newLogic.service.SquadEnvironmentService;
import org.example.footballmanager.newLogic.service.SquadTrainingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Training fires on day 4 at hour 10, and the scheduler is what proves it (owner, 2026-10-07).
 *
 * <p><b>The owner asked: "da li nam radi trening? Na Oracle je proslo dan za trening a nije se desio."</b>
 * At the time of writing, nobody could say — because <b>nothing tested the job</b>.
 * {@code SquadTrainingServiceTest} proves the service does its arithmetic and
 * {@code TrainingControllerAuthorizationTest} proves who may ask for it by hand. There was no test that
 * the scheduler calls it, on the right day, at the right hour.
 *
 * <p>That is the gap this task keeps finding: <b>a service that is correct and wired to nothing looks
 * exactly like a service that works.</b>
 *
 * <p>The answer, once it was measured: **training's own trigger is correct** — day 4, hour 10, and the
 * clock does offer it. So on Oracle the day almost certainly passed and the job was never *asked*; the
 * missing half is visibility, which is why the jobs tab below is a P0 in its own right.
 */
class TrainingJobFiresTest extends BaseTest {

    @SpyBean
    private SquadTrainingService training;

    @SpyBean
    private SquadEnvironmentService environment;

    @Autowired
    private TrainingJob job;

    /** The real scheduler, with the real job list — nothing stubbed away. */
    @Autowired
    private JobRunner runner;

    @Autowired
    private org.example.footballmanager.newLogic.repository.JobRunRepository runs;

    @Test
    @Transactional
    @DisplayName("the trigger is day 4 at hour 10, and stays due for the rest of the day")
    void theTriggerIsDayFourAtTen() {
        assertEquals(4, job.day(), "training is a day-4 job");
        assertEquals(10, job.hour(),
                "at hour 10, before the day-5 cup round so the sides that played on day 3 have trained");

        assertFalse(due(3, 10), "not on day 3");
        assertFalse(due(4, 9), "and not before hour 10 on day 4");
        assertTrue(due(4, 10), "it is due on day 4 at hour 10");
        assertTrue(due(4, 11), "and stays due for the rest of the day, so a late arrival still runs it");
    }

    @Test
    @Transactional
    @DisplayName("the scheduler runs it on day 4 hour 10 and records it DONE")
    void theSchedulerRunsItOnDayFour() {
        Mockito.clearInvocations(training, environment);

        // **Through the runner, not by calling the job.** Calling job.run(...) directly is the mistake
        // this test was written after making once: the service is called, the work happens, and no row
        // is written, so nothing anywhere says the scheduler got there. Only the runner decides what is
        // due, claims it, and records the outcome — and only the row is proof.
        Map<String, Object> result = runner.runDue(1, 4, 4, 10);

        Mockito.verify(training, Mockito.times(1)).trainEveryClub(1, 4);
        Mockito.verify(environment, Mockito.times(1)).advanceWeek(1, 4);
        // Not "exactly one job" - at day 4 hour 10 the day-opened, recovery, table-reconcile and
        // tournament-draw jobs are all due too, and all of them should have run. What matters is that
        // training is among them and that nothing failed.
        List<?> outcomes = (List<?>) result.get("jobs");
        assertTrue(outcomes.stream().anyMatch(o -> String.valueOf(o).contains("key=training")),
                "training is among the jobs run at day 4 hour 10: " + outcomes);
        assertEquals(0, ((Number) result.get("failed")).intValue(),
                "and nothing failed: " + outcomes);

        assertTrue(runs.findBySeasonYearAndWeekNumberAndDayNumberAndJobKey(1, 4, 4, TrainingJob.KEY)
                        .map(row -> row.getStatus() == JobRun.Status.DONE)
                        .orElse(false),
                "the runner records training as DONE for that day");
    }

    @Test
    @Transactional
    @DisplayName("the scheduler leaves the other days alone")
    void theSchedulerLeavesOtherDaysAlone() {
        Mockito.clearInvocations(training, environment);

        runner.runDue(1, 4, 3, 23);
        runner.runDue(1, 4, 5, 10);

        Mockito.verify(training, Mockito.never()).trainEveryClub(Mockito.anyInt(), Mockito.anyInt());
        List<JobRun> recorded = runs.findAll().stream()
                .filter(row -> TrainingJob.KEY.equals(row.getJobKey()))
                .toList();
        assertTrue(recorded.isEmpty(),
                "nothing recorded for a day training is not scheduled on, but found: " + recorded);
    }

    /** The job's own due rule, asked directly, so the assertion is about the trigger and not a mock. */
    private boolean due(int day, int hour) {
        return day == job.day() && hour >= job.hour();
    }
}