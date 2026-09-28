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
 * <p>Day 1 is internationals at 20:45, from the owner's schedule. It is registered now that
 * {@code CompetitionType} has {@code INTERNATIONAL} and {@code InternationalFixtureSeeder} draws the
 * pairings. With one seeded country holding a squad there is nothing for it to select yet, and it will
 * find nothing until more countries are seeded - which is an honest "the world is not built" rather
 * than a job that cannot work at all.
 */
@Configuration
public class MatchdayJobsConfig {

    @Bean
    public MatchdayJob internationalMatchday(CompetitionRepository competitions,
                                             MatchFixtureRepository fixtures, AsyncSimulationRunner runner) {
        return new MatchdayJob("matchday-international", CompetitionType.INTERNATIONAL,
                1, 20, 40, competitions, fixtures, runner);
    }

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
