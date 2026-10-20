package org.example.footballmanager.newLogic.jobs.config;

import org.example.footballmanager.newLogic.fantasy.FantasyScoringService;
import org.example.footballmanager.newLogic.jobs.DayJob;
import org.example.footballmanager.newLogic.jobs.impl.FantasyScoringJob;
import org.example.footballmanager.newLogic.repository.FantasyTeamRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The fantasy scoring job (owner, 2026-10-17).
 *
 * <p><b>One {@code @Bean} returning a {@code DayJob}, not a list.</b> This package has already learned
 * that lesson once, the hard way: the matchday pair was first written as two beans returning
 * {@code List<DayJob>}, and Spring does not fold either into the {@code List<DayJob>} the runner reads —
 * so both versions compiled, both were green, and neither job had ever run.
 */
@Configuration
public class FantasyJobsConfig {

    private final FantasyScoringService scoring;
    private final FantasyTeamRepository fantasyTeams;

    public FantasyJobsConfig(FantasyScoringService scoring, FantasyTeamRepository fantasyTeams) {
        this.scoring = scoring;
        this.fantasyTeams = fantasyTeams;
    }

    @Bean
    public DayJob fantasyScoringJob() {
        return new FantasyScoringJob(scoring, fantasyTeams);
    }
}
