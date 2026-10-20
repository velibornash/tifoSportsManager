package org.example.footballmanager.newLogic.jobs.impl;

import org.example.footballmanager.newLogic.jobs.DayJob;
import org.example.footballmanager.newLogic.jobs.JobContext;
import org.example.footballmanager.newLogic.service.SeasonService;
import org.springframework.stereotype.Component;

/**
 * Day 2: the finance update (owner, 2026-09-28).
 *
 * <p>Owner's schedule: day 1 international 20:45, day 2 finance, day 3 league 19:00, day 4 training,
 * day 5 cup 18:00, day 6 form and morale, day 7 league 16:00.
 *
 * <p>Fires at 10:00, before the day-3 league round, so wages and income for the week that has just
 * been played are settled before the next fixtures are created. Finance after the round would settle
 * against a table the manager has not seen yet.
 *
 * <p>This is the job the done-flag exists for. Before the framework, finances were settled inside
 * advance-week, and advancing the clock by a day instead of a week applied them far more often than
 * they were meant to be applied.
 */
@Component
public class FinanceJob implements DayJob {

    public static final String KEY = "finance";

    private final SeasonService seasons;

    public FinanceJob(SeasonService seasons) {
        this.seasons = seasons;
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
        return 2;
    }

    @Override
    public int hour() {
        return 10;
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public void run(JobContext context) {
        int settled = seasons.settleWeeklyFinancesForAllClubs();
    }
}
