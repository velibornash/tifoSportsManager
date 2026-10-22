package org.tifo.footballmanager.newLogic.jobs;

import org.tifo.footballmanager.BaseTest;
import org.tifo.footballmanager.newLogic.model.GameDay;
import org.tifo.footballmanager.newLogic.model.WeekTemplate;
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
 * and the calendar render. <b>Day 6 is labelled "Form and morale"</b> — and until 2026-10-22 it had no
 * job on it at all, so the calendar named a day the scheduler never delivered, and nothing anywhere
 * reported it.
 *
 * <p>This is the board's B9 residue, and the second half of it: the first half was the 20:45 kickoff
 * display, which is fixed. What is left is <b>not a bug with an obvious correct answer — what day 6
 * should compute is a product decision, and this file deliberately does not guess at it.</b> What it does
 * is make the gap impossible to forget, and pin the rest of the week so a fix cannot be made by removing
 * the promise instead of keeping it.
 *
 * <p><b>2026-10-22: the decision was taken, and the gap is closed.</b> The owner's week gives day 6 to
 * form and morale, and the work already existed with no caller: {@code MoraleService.applyStanding}
 * ("is he being paid fairly, is he in the side, is he being listed") had never been called by
 * anything, and the weekly settle of the form did not exist at all. There is now a
 * {@code form-and-morale} job on day 6, and this file says so — while keeping the half of the
 * finding that still stands: <b>day 6 is not a club match day.</b> A club matchday there would change
 * the week the owner described, and that still needs saying out loud.
 *
 * <p>Structural, over the injected {@code List<DayJob>}, in the shape of
 * {@code JobTriggerUniquenessTest}: it asks what the application <em>is</em>, not what a document says.
 */
class EveryGameDayHasAJobTest extends BaseTest {

    /** The key the form-and-morale sweep runs under. */
    private static final String FORM_AND_MORALE = "form-and-morale";

    @Autowired
    List<DayJob> jobs;

    @Test
    @DisplayName("day 6 carries form and morale, and no club matchday")
    void daySixCarriesFormAndMoraleAndNoClubMatchday() {
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

        assertTrue(keys.contains(FORM_AND_MORALE),
                "the calendar names day 6 'Form and morale', so the week the owner described has to "
                        + "compute something for it. The job exists — the form settles and the standing "
                        + "pressures apply — and without it the day is a promise nobody keeps. Day 6 "
                        + "carries: " + keys);

        for (String key : keys) {
            assertTrue(key.startsWith("matchday-qualifier") || key.startsWith("matchday-tournament")
                            || key.startsWith("matchday-prepare-qualifier")
                            || key.startsWith("matchday-publish-qualifier")
                            || key.startsWith("matchday-prepare-tournament")
                            || key.startsWith("matchday-publish-tournament")
                            || FORM_AND_MORALE.equals(key),
                    "day 6 carries " + key + ", which is not a national-tournament job and not the "
                            + "form-and-morale job. A club matchday on day 6 changes the week the owner "
                            + "described, and needs saying out loud.");
        }
    }

    @Test
    @DisplayName("every day the calendar names has a job on it")
    void everyNamedDayHasAJob() {
        Set<Integer> uncovered = new TreeSet<>();
        for (GameDay day : GameDay.values()) {
            if (jobsOnDay(day.number()).isEmpty()) {
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
