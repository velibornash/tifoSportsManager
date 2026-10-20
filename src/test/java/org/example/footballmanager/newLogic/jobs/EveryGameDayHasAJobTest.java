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

import static org.junit.jupiter.api.Assertions.assertFalse;
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
    @DisplayName("day 6 is a club rest day and a national match day, and nothing else")
    void daySixIsANationalDayOnly() {
        assertTrue(GameDay.DAY_6.kind() == WeekTemplate.DayKind.MORALE,
                "day 6 is no longer the form-and-morale day, so this finding has to be re-stated against "
                        + "whatever it is now");

        // **This assertion used to be the opposite, and it was red.** B9 asked whether a day the
        // calendar names can have nothing on it, and the answer was yes - until the national tournament
        // schedule put qualifiers and tournament ties on days 2, 4 and 6, which this test did not know
        // about. It was written to be updated when that happened, not deleted, so here it is.
        //
        // Day 6 is still not a *club* match day, and that half of the finding still stands: the jobs on
        // it are national-tournament ones, pinned to the qualifying and tournament weeks, and a club
        // manager sees an evening off.
        Set<String> keys = jobsOnDay(6);
        assertFalse(keys.isEmpty(),
                "day 6 has " + keys + " on it now, which this test says it should not. If a club matchday "
                        + "has been added to day 6, the calendar changed and this finding is re-stated, "
                        + "not deleted.");

        for (String key : keys) {
            assertTrue(key.startsWith("matchday-qualifier") || key.startsWith("matchday-tournament")
                            || key.startsWith("matchday-prepare-qualifier")
                            || key.startsWith("matchday-publish-qualifier")
                            || key.startsWith("matchday-prepare-tournament")
                            || key.startsWith("matchday-publish-tournament"),
                    "day 6 carries " + key + ", which is not a national-tournament job. A club matchday "
                            + "on day 6 changes the week the owner described, and needs saying out loud.");
        }
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
