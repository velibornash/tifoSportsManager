package org.tifo.footballmanager.newLogic.jobs;

import org.tifo.footballmanager.newLogic.jobs.impl.MatchdayPrepareJob;
import org.tifo.footballmanager.newLogic.jobs.impl.MatchdayPublishJob;
import org.tifo.footballmanager.newLogic.model.GameDay;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The club week, and the two jobs that run in it (owner, 2026-10-22).
 *
 * <p>The owner's table, as he gave it:
 *
 * <pre>
 *   day 1  20:45  International   prepare 19:45   publish 20:45
 *   day 2         Finance
 *   day 3  19:00  League          prepare 18:00   publish 19:00
 *   day 4         Training
 *   day 5  18:00  Cup              prepare 17:00   publish 18:00
 *   day 6         Form and morale
 *   day 7  16:00  League          prepare 15:00   publish 16:00
 * </pre>
 *
 * <p><b>The prepare locks the matchday an hour before the kickoff</b> - the manager's changes stop
 * counting then, and the fixtures are computed - and <b>the publish releases the results at the
 * kickoff</b>, which is what moves the match from "next" to "recents" on the dashboards.
 *
 * <p><b>Why this test exists.</b> The trigger used to carry the hour and nothing else, so the day-1
 * pair fired at 19:00 and 20:00: the lock landed three quarters of an hour early, and the publish
 * released a match that had not started. The calendar was right the whole time - the kickoff was
 * 20:45 - and the jobs were reading it as 20. A test that pins the whole table, minute and all, is
 * what keeps the two in step.
 */
class MatchdayScheduleIsTheOwnersTableTest {

    @Test
    @DisplayName("the four match days kick off when the owner says they do")
    void theKickoffs() {
        assertEquals(LocalTime.of(20, 45), GameDay.DAY_1.kickoffTime(), "day 1 is the internationals");
        assertEquals(LocalTime.of(19, 0), GameDay.DAY_3.kickoffTime(), "day 3 is the first league day");
        assertEquals(LocalTime.of(18, 0), GameDay.DAY_5.kickoffTime(), "day 5 is the cup day");
        assertEquals(LocalTime.of(16, 0), GameDay.DAY_7.kickoffTime(), "day 7 is the second league day");

        // The days with no fixture have no kickoff, and saying so is what keeps a job from inventing
        // one: finance, training and form and morale are not matchdays.
        assertNull(GameDay.DAY_2.kickoffTime(), "day 2 is the finance day");
        assertNull(GameDay.DAY_4.kickoffTime(), "day 4 is the training day");
        assertNull(GameDay.DAY_6.kickoffTime(), "day 6 is the form and morale day");
    }

    @Test
    @DisplayName("the prepare is an hour before the kickoff, and the publish is the kickoff")
    void theTwoJobs() {
        assertPair(1, LocalTime.of(19, 45), LocalTime.of(20, 45));
        assertPair(3, LocalTime.of(18, 0), LocalTime.of(19, 0));
        assertPair(5, LocalTime.of(17, 0), LocalTime.of(18, 0));
        assertPair(7, LocalTime.of(15, 0), LocalTime.of(16, 0));
    }

    @Test
    @DisplayName("the minute is part of the trigger, not a detail of it")
    void theMinuteIsPartOfTheTrigger() {
        // The pair that was wrong: a 20:45 kickoff prepared at 19:00 and published at 20:00.
        MatchdayPrepareJob prepare = new MatchdayPrepareJob(1, null, null);
        MatchdayPublishJob publish = new MatchdayPublishJob(1, null, null);

        assertEquals(19, prepare.hour(), "the day-1 prepare is in the 19th hour");
        assertEquals(45, prepare.minute(), "the day-1 prepare is at 19:45, not at 19:00");
        assertEquals(20, publish.hour(), "the day-1 publish is in the 20th hour");
        assertEquals(45, publish.minute(), "the day-1 publish is at 20:45, not at 20:00");
    }

    @Test
    @DisplayName("a job that does not care about the minute says zero")
    void theMinuteDefaultsToZero() {
        DayJob plain = new DayJob() {
            @Override
            public String key() {
                return "plain";
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
                // Nothing to do: this job exists to be asked what its minute is.
            }
        };
        assertEquals(0, plain.minute(), "a job that names no minute fires on the hour");
    }

    /**
     * One match day: the prepare and the publish, as the owner's table has them.
     *
     * @param day the day of the week
     * @param prepare when the matchday is computed and locked
     * @param publish when the results are released
     */
    private void assertPair(int day, LocalTime prepare, LocalTime publish) {
        MatchdayPrepareJob prepareJob = new MatchdayPrepareJob(day, null, null);
        MatchdayPublishJob publishJob = new MatchdayPublishJob(day, null, null);

        assertEquals(prepare.getHour(), prepareJob.hour(), "day " + day + " prepares in the wrong hour");
        assertEquals(prepare.getMinute(), prepareJob.minute(), "day " + day + " prepares in the wrong minute");
        assertEquals(publish.getHour(), publishJob.hour(), "day " + day + " publishes in the wrong hour");
        assertEquals(publish.getMinute(), publishJob.minute(), "day " + day + " publishes in the wrong minute");

        assertEquals("matchday-prepare-" + day, prepareJob.key(), "the prepare's key moved");
        assertEquals("matchday-publish-" + day, publishJob.key(), "the publish's key moved");
    }
}
