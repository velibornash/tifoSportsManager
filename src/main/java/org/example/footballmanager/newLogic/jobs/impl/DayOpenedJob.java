package org.example.footballmanager.newLogic.jobs.impl;

import org.example.footballmanager.newLogic.jobs.DayJob;
import org.example.footballmanager.newLogic.jobs.JobContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Announces that a game day opened (owner, 2026-09-28).
 *
 * <p>Exists to prove the framework end to end before any money moves. It has no side effect on game
 * data, so a mistake in the runner shows up as a wrong log line rather than as a mangled squad.
 *
 * <p>Fires at 00:00, which is the boundary case: a job whose trigger is the first hour of the day
 * must be due immediately, not an hour late.
 */
@Component
public class DayOpenedJob implements DayJob {

    private static final Logger log = LoggerFactory.getLogger(DayOpenedJob.class);

    public static final String KEY = "day-opened";

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
        return 0;
    }

    @Override
    public int order() {
        return 0;
    }

    @Override
    public void run(JobContext context) {
        log.info("Game day {} opened - season {} week {}.", context.dayNumber(),
                context.seasonYear(), context.weekNumber());
    }
}
