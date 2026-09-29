package org.example.footballmanager.newLogic.jobs.impl;

import org.example.footballmanager.newLogic.jobs.DayJob;
import org.example.footballmanager.newLogic.jobs.JobContext;
import org.example.footballmanager.newLogic.util.CupFixtureSeeder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Draws the cup round for the current week (owner, 2026-09-29).
 *
 * <p>From the owner, watching a reset leave the cup empty on the Oracle server: "the draw for every
 * round should exist as a job and fire at a set time, and if something misses it the next ping should
 * pick it up because it is not marked done."
 *
 * <p>That is what the done-flag is for, and it is why this is a job rather than something the boot
 * listener does once. A draw that happens at boot is only correct if boot happens to be the right
 * moment. A draw that is a scheduled job is correct whenever it runs, and a round that was missed -
 * app down, deploy over the kickoff - is caught by the next hourly check, because there is no DONE row
 * for it.
 *
 * <p><b>Day 2, three days before the tie is played on day 5.</b> The owner's reason: clubs need time to
 * scout the opponent the draw hands them. The gap also means the draw and the matchday are days apart,
 * so there is no ordering between them to get wrong - they used to be hours apart on the same day,
 * which meant the matchday found the round only if the draw happened to run first.
 *
 * <p><b>Later rounds only draw once the round before has been played.</b> The entrants for round 4 are
 * the winners of round 3, and until round 3 has a result they are not knowable. The job therefore
 * draws nothing rather than drawing against teams that have not qualified.
 */
@Component
public class CupDrawJob implements DayJob {

    private static final Logger log = LoggerFactory.getLogger(CupDrawJob.class);

    public static final String KEY = "cup-draw";

    /** 08:00 on day 2 - the earliest sensible hour on the first non-match day. */
    private static final int DRAW_HOUR = 8;

    private final CupFixtureSeeder seeder;

    public CupDrawJob(CupFixtureSeeder seeder) {
        this.seeder = seeder;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public int week() {
        return ANY_WEEK;
    }

    @Override
    public int day() {
        // Day 2, not day 5 (owner, 2026-09-29): the draw happens early in the week so clubs have
        // three days to scout the opponent they have been given. Drawing on cup day itself would
        // hand a manager his opposition the morning of the tie, which is not a draw anyone would
        // call fair. The tie is still played on day 5 - only the draw moved.
        return 2;
    }

    @Override
    public int hour() {
        return DRAW_HOUR;
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public void run(JobContext context) {
        int drawn = seeder.drawRoundForWeek(context.weekNumber());
        if (drawn > 0) {
            log.info("Cup draw job: {} tie(s) drawn for week {}.", drawn, context.weekNumber());
        } else {
            log.debug("Cup draw job: nothing to draw for week {} yet.", context.weekNumber());
        }
    }
}
