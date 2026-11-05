package org.tifo.footballmanager.newLogic.jobs.config;

import org.tifo.footballmanager.newLogic.jobs.DayJob;
import org.tifo.footballmanager.newLogic.jobs.impl.RecoveryJob;
import org.tifo.footballmanager.newLogic.service.ZoneLoadService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The daily recovery job (owner, 2026-11-05).
 *
 * <p>RecoveryJob existed but was never registered as a bean, so it never ran.
 * This config registers it.
 */
@Configuration
public class RecoveryJobsConfig {

    private final ZoneLoadService zoneLoad;

    public RecoveryJobsConfig(ZoneLoadService zoneLoad) {
        this.zoneLoad = zoneLoad;
    }

    @Bean
    public DayJob recoveryJob() {
        return new RecoveryJob(zoneLoad);
    }
}