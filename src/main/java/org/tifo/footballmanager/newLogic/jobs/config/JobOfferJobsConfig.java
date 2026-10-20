package org.tifo.footballmanager.newLogic.jobs.config;

import org.tifo.footballmanager.newLogic.jobs.DayJob;
import org.tifo.footballmanager.newLogic.jobs.impl.JobOfferJob;
import org.tifo.footballmanager.newLogic.service.JobOfferService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The job-offer job (owner, 2026-10-18).
 *
 * <p><b>One {@code @Bean} returning a {@code DayJob}, not a list.</b> This package has learned that lesson
 * twice now: Spring does not fold a {@code @Bean} returning {@code List<DayJob>} into the {@code List<DayJob>}
 * the runner reads, so the job compiles, the tests pass, and it never runs.
 */
@Configuration
public class JobOfferJobsConfig {

    private final JobOfferService jobOffers;

    public JobOfferJobsConfig(JobOfferService jobOffers) {
        this.jobOffers = jobOffers;
    }

    @Bean
    public DayJob jobOfferJob() {
        return new JobOfferJob(jobOffers);
    }
}
