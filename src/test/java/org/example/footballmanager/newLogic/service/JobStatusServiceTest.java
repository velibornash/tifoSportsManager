package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.GameClock;
import org.example.footballmanager.newLogic.model.JobRun;
import org.example.footballmanager.newLogic.repository.GameClockRepository;
import org.example.footballmanager.newLogic.repository.JobRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The jobs panel says what each job is, when it ran, when it runs next, and whether anything failed
 * (owner, 2026-10-07).
 *
 * <p>It exists because of the question it makes answerable: <b>"does training work?"</b> could not be
 * answered from anywhere in the application. Training did work — it was recorded DONE — but nothing
 * showed a job's status, its last run or its next trigger, so a job that ran and a job that did not were
 * indistinguishable. The rows were in {@code job_run} the whole time with no reader.
 *
 * <p><b>The next trigger is walked forward, not compared.</b> Whether a trigger is reached depends on
 * which hours the clock actually offers, so subtracting two numbers and calling it a time is exactly the
 * shortcut that goes wrong at a day or week boundary. The walk is asserted against known positions.
 */
class JobStatusServiceTest extends BaseTest {

    @Autowired
    private JobStatusService status;

    @Autowired
    private GameClockRepository clocks;

    @Autowired
    private JobRunRepository runs;

    @Test
    @Transactional
    @DisplayName("every registered job is listed, with a trigger and a next trigger")
    void everyJobIsListed() {
        clockAt(1, 1, 1, 0);

        List<JobStatusService.Status> rows = rows(status.report());

        assertFalse(rows.isEmpty(), "the panel lists the jobs the application actually has");
        for (JobStatusService.Status row : rows) {
            assertNotNull(row.key(), "every row names its job");
            assertNotNull(row.trigger(), "and says what it is triggered on: " + row);
            assertNotNull(row.nextTrigger(), "and when it next runs: " + row);
        }
    }

    @Test
    @Transactional
    @DisplayName("a job later today reads as today, and one three days out reads as that day")
    void nextTriggerDoesNotRollOverTooEarly() {
        // Week 1, day 1, hour 6. Training is day 4 hour 10 — three days away. Day-opened is every day from
        // 00:00 and is already due, so its next fire is the next hour of this same day.
        clockAt(1, 1, 1, 6);
        Map<String, JobStatusService.Status> byKey = keyed();

        JobStatusService.Status everyDay = job(byKey, "day-opened");
        assertNotNull(everyDay, "the every-day job is listed");
        assertTrue(String.valueOf(everyDay.nextTrigger()).contains("day 1"),
                "a job due every hour from 00:00 is next within day 1, not rolled into day 2: "
                        + everyDay.nextTrigger());

        JobStatusService.Status training = job(byKey, "training");
        assertNotNull(training, "the day-4 job is listed");
        assertTrue(String.valueOf(training.nextTrigger()).contains("day 4"),
                "training is next on day 4: " + training.nextTrigger());
        assertEquals(3 * 24 - 6 + 10, ((Number) training.nextInHours()).intValue(),
                "and the hour count agrees with the clock: hour 6 on day 1 to hour 10 on day 4 is "
                        + (3 * 24 - 6 + 10) + " hours");
    }

    @Test
    @Transactional
    @DisplayName("a day-7 hour-23 job reports this week, not next week")
    void weekRolloverPointsInsideThisWeek() {
        // Week 3, day 7, hour 22. Hour 23 is the **last hour of the week**, so a day-7 hour-23 job is one
        // hour away and still in week 3. Reporting week 4 here is the boundary mistake this test exists
        // for: the walk has to compare the day before it rolls the week.
        clockAt(3, 7, 22);
        Map<String, JobStatusService.Status> byKey = keyed();

        JobStatusService.Status rollover = job(byKey, "week-rollover");
        assertNotNull(rollover, "the rollover job is listed. Listed: " + byKey.keySet());
        assertTrue(String.valueOf(rollover.nextTrigger()).contains("week 3"),
                "**week 3, not week 4** — day 7 hour 23 is still inside this week: "
                        + rollover.nextTrigger());
        assertEquals(1, ((Number) rollover.nextInHours()).intValue(), "and it is one hour away");
    }

    @Test
    @Transactional
    @DisplayName("a failed run is visible, and a job that later succeeds reads as DONE")
    void failureIsVisibleAndRecoveryIsVisible() {
        clockAt(1, 1, 1, 0);
        runs.deleteAll();
        aRun("training", JobRun.Status.FAILED, "simulated failure");

        assertTrue((Boolean) status.report().get("failing"),
                "the panel says something failed. This is what stops a broken job looking healthy — a "
                        + "failed job is retried quietly on the next hour, so without this it is invisible.");

        // What a retry produces: the same job, same day, now DONE.
        aRun("training", JobRun.Status.DONE, null);

        assertEquals("DONE", job(keyed(), "training").lastStatus(),
                "the last run is what is shown, so a retry that works reads as DONE");
    }

    @Test
    @Transactional
    @DisplayName("a healthy world gets no warning banner")
    void aHealthyWorldIsNotAlarmed() {
        clockAt(1, 1, 1, 0);
        runs.deleteAll();
        aRun("training", JobRun.Status.DONE, null);

        assertFalse((Boolean) status.report().get("failing"),
                "a world where every job succeeded must not be given a warning");
    }

    // ---------- reading ----------

    @SuppressWarnings("unchecked")
    private List<JobStatusService.Status> rows(Map<String, Object> report) {
        return (List<JobStatusService.Status>) report.get("jobs");
    }

    /** The report keyed by job key, so a test names the job it is talking about. */
    private Map<String, JobStatusService.Status> keyed() {
        Map<String, JobStatusService.Status> keyed = new HashMap<>();
        for (JobStatusService.Status row : rows(status.report())) {
            keyed.put(row.key(), row);
        }
        return keyed;
    }

    /** One job's row, or a failure that says which keys exist. */
    private JobStatusService.Status job(Map<String, JobStatusService.Status> byKey, String key) {
        JobStatusService.Status row = byKey.get(key);
        assertNotNull(row, "the '" + key + "' job is listed. Listed: " + byKey.keySet());
        return row;
    }

    /**
     * One run for the training slot.
     *
     * <p>Upsert rather than insert, because {@code job_run} has a unique key on
     * (season, week, day, job_key) — which is what stops the same job running twice for one slot, and is
     * also why a retry updates the row instead of adding a second one.
     */
    private void aRun(String key, JobRun.Status jobStatus, String message) {
        JobRun row = runs
                .findBySeasonYearAndWeekNumberAndDayNumberAndJobKey(1, 1, 4, key)
                .orElseGet(JobRun::new);
        row.setJobKey(key);
        row.setSeasonYear(1);
        row.setWeekNumber(1);
        row.setDayNumber(4);
        row.setRanAtHour(10);
        row.setStatus(jobStatus);
        row.setMessage(message);
        row.setRanAt(java.time.Instant.now());
        runs.save(row);
    }

    private void clockAt(int week, int day, int hour) {
        clockAt(1, week, day, hour);
    }

    private void clockAt(int season, int week, int day, int hour) {
        GameClock clock = clocks.findById(1L).orElseGet(GameClock::new);
        clock.setCurrentSeason(season);
        clock.setCurrentWeek(week);
        clock.setCurrentDay(day);
        clock.setCurrentHour(hour);
        clock.setAdvanceOffsetSeconds(0L);
        clock.setCurrentDate(LocalDateTime.of(2026, 3, 1, hour, 0));
        clocks.save(clock);
    }
}