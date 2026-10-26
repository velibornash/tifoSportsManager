package org.tifo.footballmanager.newLogic.jobs.impl;

import org.tifo.footballmanager.newLogic.jobs.DayJob;
import org.tifo.footballmanager.newLogic.jobs.JobContext;
import org.tifo.footballmanager.newLogic.service.JobOfferService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Offers a few jobs, once a week (owner, 2026-10-18).
 *
 * <p>The owner: *"u neodredjenim vremenskim intervalima, random ai klubovi iz visih tierova … ponude random
 * human manageru"*. A weekly pass that offers to a small random number of eligible managers is the
 * "indefinite interval" made runnable: it is not tied to a day anybody can predict from the outside, the
 * managers are shuffled so it is not always the same ones, and it fires once per game week rather than on a
 * timer nobody can test.
 *
 * <p><b>Day 1, early.</b> Day 1 is the international day and the first day of a week, so an offer arrives
 * before that week's football rather than in the middle of it — a manager deciding whether to leave has the
 * whole week in front of him.
 *
 * <p>Registered as a single {@code @Bean} returning a {@code DayJob}, because this package has already
 * learned that a {@code @Bean} returning {@code List<DayJob>} is invisible to the runner and both versions
 * compiled green while neither job ever ran.
 */
@Component
public class JobOfferJob implements DayJob {

    private static final Logger log = LoggerFactory.getLogger(JobOfferJob.class);

    public static final String KEY = "job-offers";

    /** Before the day-1 matchday at 19:00, and before the recovery job at 06:00 is irrelevant — either is
     *  fine, and 07 keeps it after recovery so a manager wakes up to it. */
    private static final int OFFER_HOUR = 7;

    private final JobOfferService jobOffers;

    public JobOfferJob(JobOfferService jobOffers) {
        this.jobOffers = jobOffers;
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
        return OFFER_HOUR;
    }

    @Override
    public int order() {
        // After recovery (10), so an offer does not arrive while a squad is still recovering.
        return 20;
    }

    @Override
    public String dependsOn() {
        // The order above is the same scan's promise; this is the runner's. An offer must not be
        // written from a squad whose recovery has not been recorded yet.
        return RecoveryJob.KEY;
    }

    @Override
    public void run(JobContext context) {
        int made = jobOffers.runOffers(JobOfferService.OFFERS_PER_PASS);
        log.info("Job offers: {} made in season {} week {}.", made, context.seasonYear(), context.weekNumber());
    }
}
