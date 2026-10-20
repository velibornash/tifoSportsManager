package org.example.footballmanager.newLogic.jobs.impl;

import org.example.footballmanager.newLogic.jobs.DayJob;
import org.example.footballmanager.newLogic.jobs.JobContext;
import org.example.footballmanager.newLogic.service.ZoneLoadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Daily recovery, every day (owner, 2026-09-29).
 *
 * <p>The owner: "morale / form should be a zone compute, recovery is happening each day." So it runs on
 * every game day rather than once a week, and at 06:00 so it lands before the matchdays - a squad that
 * played on day 3 has recovered by day 5 rather than turning up for a cup tie still tired from the
 * league.
 *
 * <p>This job was written once and deleted, because there was no zone model to compute from and it
 * would have had to fake one. {@link ZoneLoadService} is the reason it can exist now: recovery is
 * derived from where a player actually worked, and players who did not play are not touched.
 */
@Component
public class RecoveryJob implements DayJob {

    private static final Logger log = LoggerFactory.getLogger(RecoveryJob.class);

    public static final String KEY = "recovery";

    /** Early, so it runs before the day-3 matchday at 19:00. */
    private static final int RECOVERY_HOUR = 6;

    private final ZoneLoadService zoneLoad;

    public RecoveryJob(ZoneLoadService zoneLoad) {
        this.zoneLoad = zoneLoad;
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
        return ANY_DAY;
    }

    @Override
    public int hour() {
        return RECOVERY_HOUR;
    }

    @Override
    public int order() {
        // Before the matchdays, which are 40.
        return 10;
    }

    @Override
    public void run(JobContext context) {
        int touched = zoneLoad.applyDailyRecovery();
        log.info("Recovery: {} player(s) recovered on season {} week {} day {}.",
                touched, context.seasonYear(), context.weekNumber(), context.dayNumber());
    }
}
