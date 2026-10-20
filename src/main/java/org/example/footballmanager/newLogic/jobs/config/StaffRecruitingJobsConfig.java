package org.example.footballmanager.newLogic.jobs.config;

import org.example.footballmanager.newLogic.jobs.DayJob;
import org.example.footballmanager.newLogic.jobs.impl.StaffRecruitingJob;
import org.example.footballmanager.newLogic.service.StaffRecruitingService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The staff-market job (owner, 2026-10-18).
 *
 * <p><b>One {@code @Bean} returning a {@code DayJob}, not a list.</b> Spring does not fold a
 * {@code @Bean} returning {@code List<DayJob>} into the {@code List<DayJob>} the runner reads, so the job
 * compiles, the tests pass, and it never runs. This package has now learned that twice.
 */
@Configuration
public class StaffRecruitingJobsConfig {

    private final StaffRecruitingService recruiting;

    public StaffRecruitingJobsConfig(StaffRecruitingService recruiting) {
        this.recruiting = recruiting;
    }

    @Bean
    public DayJob staffRecruitingJob() {
        return new StaffRecruitingJob(recruiting);
    }
}
