package org.tifo.footballmanager.newLogic.jobs.impl;

import org.tifo.footballmanager.newLogic.jobs.DayJob;
import org.tifo.footballmanager.newLogic.jobs.JobContext;
import org.tifo.footballmanager.newLogic.service.StaffRecruitingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Puts new candidates on the staff market, once a week, starting in week 1 (owner, 2026-10-18).
 *
 * <p>The owner: *"dolaze svake nedelje pocevsi od prve sa random skilovima"*. Day 1 is the first day of a
 * week, so a manager deciding who to hire has the whole week in front of him — the same reason the
 * job-offer pass runs on day 1, and the two are deliberately apart so a manager is not reading a job
 * offer and a list of coaches at the same moment.
 *
 * <p>It also lets a few AI clubs sign. Without that half the market is only ever read by two people and
 * the other 7,314 clubs stay unstaffed forever; see {@link StaffRecruitingService} for why the number
 * is small rather than "every club with a vacancy".
 *
 * <p>Registered as a single {@code @Bean} returning a {@code DayJob}, because this package has already
 * learned that a {@code @Bean} returning {@code List<DayJob>} is invisible to the runner and both versions
 * compiled green while neither job ever ran.
 */
@Component
public class StaffRecruitingJob implements DayJob {

    private static final Logger log = LoggerFactory.getLogger(StaffRecruitingJob.class);

    public static final String KEY = "staff-market";

    /** After the job-offer pass (07:00), so the two weekly arrivals do not land together. */
    private static final int MARKET_HOUR = 8;

    private final StaffRecruitingService recruiting;

    public StaffRecruitingJob(StaffRecruitingService recruiting) {
        this.recruiting = recruiting;
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
        return 1;
    }

    @Override
    public int hour() {
        return MARKET_HOUR;
    }

    @Override
    public int order() {
        // After the job offers (20), so a manager's week opens with offers in a stable order.
        return 30;
    }

    @Override
    public String dependsOn() {
        // The market reacts to the week's offers, so it waits for them to have been written.
        return JobOfferJob.KEY;
    }

    @Override
    public void run(JobContext context) {
        int listed = recruiting.weeklyBatch();
        int signed = recruiting.aiRecruit();
        log.info("Staff market: {} listed, {} AI appointment(s) in season {} week {}.",
                listed, signed, context.seasonYear(), context.weekNumber());
    }
}
