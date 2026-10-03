package org.example.footballmanager.newLogic.jobs;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.GameDay;
import org.example.footballmanager.newLogic.model.WeekTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every day the calendar names has something scheduled on it.
 *
 * <p>{@code GameDay} is the game's own statement of what each day is for, and it is what the dashboard
 * and the calendar render. Six of its seven entries have a job registered on them. <b>Day 6 is labelled
 * "Form and morale" and has no job on it at all</b> — so the calendar names a day the scheduler never
 * delivers, and nothing anywhere reports it.
 *
 * <p>This is the board's B9 residue, and the second half of it: the first half was the 20:45 kickoff
 * display, which is fixed. What is left is <b>not a bug with an obvious correct answer — what day 6
 * should compute is a product decision, and this file deliberately does not guess at it.</b> What it does
 * is make the gap impossible to forget, and pin the rest of the week so a fix cannot be made by removing
 * the promise instead of keeping it.
 *
 * <p>Structural, over the injected {@code List<DayJob>}, in the shape of
 * {@code JobTriggerUniquenessTest}: it asks what the application <em>is</em>, not what a document says.
 */
class EveryGameDayHasAJobTest extends BaseTest {

    @Autowired
    List<DayJob> jobs;

    @Test
    @DisplayName("day 6 is named by the calendar and has nothing on it")
    void daySixIsNamedAndUnscheduled() {
        assertTrue(GameDay.DAY_6.kind() == WeekTemplate.DayKind.MORALE,
                "day 6 is no longer the form-and-morale day, so this finding has to be re-stated against "
                        + "whatever it is now");

        // A named day with nothing on it. The two ANY_DAY jobs (day-opened at 00:00, recovery at 06:00)
        // do fire on day 6, and are excluded deliberately: a squad recovering is not the day 6 the
        // calendar promises, and counting them would make this test pass against the broken week.
        assertTrue(jobsOnDay(6).isEmpty(),
                "day 6 now has " + jobsOnDay(6) + " scheduled on it. That is the fix — so update this test "
                        + "and close B9's second half on the board rather than deleting the assertion.");
    }

    @Test
    @DisplayName("every other day the calendar names has a job on it")
    void everyOtherNamedDayHasAJob() {
        Set<Integer> uncovered = new TreeSet<>();
        for (GameDay day : GameDay.values()) {
            if (day.number() != 6 && jobsOnDay(day.number()).isEmpty()) {
                uncovered.add(day.number());
            }
        }
        assertTrue(uncovered.isEmpty(),
                "these days are named by GameDay and have no job registered on them: " + uncovered
                        + ". A day the manager is shown and then nothing happens is worse than a day that "
                        + "is not in the calendar at all.");
    }

    @Test
    @DisplayName("no job is scheduled on a day the calendar does not have")
    void noJobIsOnADayThatDoesNotExist() {
        for (DayJob job : jobs) {
            int day = job.day();
            if (day == DayJob.ANY_DAY) {
                continue;
            }
            assertTrue(day >= 1 && day <= GameDay.LAST,
                    job.key() + " is scheduled on day " + day + ", and the calendar only has days 1 to "
                            + GameDay.LAST + ". It would never fire, and nothing would say so.");
        }
    }

    // --- helpers ---

    /** Keys of the jobs pinned to a specific day. ANY_DAY jobs are excluded — see above. */
    private Set<String> jobsOnDay(int dayNumber) {
        Set<String> keys = new TreeSet<>();
        for (DayJob job : jobs) {
            if (job.day() == dayNumber) {
                keys.add(job.key());
            }
        }
        return keys;
    }
}
