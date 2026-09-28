package org.example.footballmanager.newLogic.jobs.config;

import org.example.footballmanager.newLogic.jobs.impl.MatchdayJob;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.service.AsyncSimulationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the matchday jobs (owner, 2026-09-28).
 *
 * <p>Three matchdays are registered: day 3 league 19:00, day 5 cup 18:00, day 7 league 16:00. Days 3
 * and 7 are separate instances of the same job with different keys, because the done-flag is keyed on
 * (season, week, day, key) and a shared key would let the day-3 job suppress the day-7 round.
 *
 * <p><b>Day 1 internationals is deliberately absent.</b> {@code CompetitionType} is LEAGUE and CUP
 * only, so there is nothing for an international matchday to select and the job would have found no
 * competition and done nothing, silently, forever - which is worse than not having it. National-team
 * fixtures need an INTERNATIONAL competition type and a draw before that job can exist. Recorded in
 * the backlog.
 */
@Configuration
public class MatchdayJobsConfig {

    @Bean
    public MatchdayJob leagueMatchdayFirst(CompetitionRepository competitions,
                                           MatchFixtureRepository fixtures, AsyncSimulationRunner runner) {
        return new MatchdayJob("matchday-league-a", CompetitionType.LEAGUE, 3, 19, 40,
                competitions, fixtures, runner);
    }

    @Bean
    public MatchdayJob cupMatchday(CompetitionRepository competitions,
                                   MatchFixtureRepository fixtures, AsyncSimulationRunner runner) {
        return new MatchdayJob("matchday-cup", CompetitionType.CUP, 5, 18, 40,
                competitions, fixtures, runner);
    }

    @Bean
    public MatchdayJob leagueMatchdaySecond(CompetitionRepository competitions,
                                            MatchFixtureRepository fixtures, AsyncSimulationRunner runner) {
        return new MatchdayJob("matchday-league-b", CompetitionType.LEAGUE, 7, 16, 40,
                competitions, fixtures, runner);
    }
}
