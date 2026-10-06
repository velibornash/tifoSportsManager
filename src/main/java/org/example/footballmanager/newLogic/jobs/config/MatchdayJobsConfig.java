package org.example.footballmanager.newLogic.jobs.config;

import org.example.footballmanager.newLogic.jobs.impl.FriendlyMatchdayJob;
import org.example.footballmanager.newLogic.jobs.impl.MatchdayJob;
import org.example.footballmanager.newLogic.jobs.impl.NationalMatchdayJob;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.NationalTournamentSchedule;
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

    /**
     * The five qualifying matchdays: week 6, days 2 to 6, one a day.
     *
     * <p>Declared as five beans rather than one job looping over the days, because the done-flag is
     * keyed on (season, week, day, key) and a single key would let the first matchday mark the rest as
     * done. Each day's kickoff comes from the week template where it has one, and from the owner's
     * 20:00 where the template has none — days 2, 4 and 6 are finance, training and morale, and carry
     * no time of their own.
     */
    /**
     * The friendlies of a day.
     *
     * <p><b>All four days that can hold one</b> — the season is four slots wide (day 1, 3, 5, 7) and a
     * friendly can be arranged in any slot the week marks friendly-capable, which in midseason is all
     * four. Registering only some of them leaves a friendly agreed and never played, which is the exact
     * defect this job was added to end, so the list is derived from the calendar rather than written.
     *
     * <p>Safe on a league day: the selection is by match <b>type</b>, so a league fixture in the same
     * day is not touched.
     */
    @Bean
    public FriendlyMatchdayJob friendlyMatchdayOne(MatchFixtureRepository fixtures,
                                                  AsyncSimulationRunner runner) {
        return new FriendlyMatchdayJob("matchday-friendly-1", 1, fixtures, runner);
    }

    @Bean
    public FriendlyMatchdayJob friendlyMatchdayThree(MatchFixtureRepository fixtures,
                                                    AsyncSimulationRunner runner) {
        return new FriendlyMatchdayJob("matchday-friendly-3", 3, fixtures, runner);
    }

    @Bean
    public FriendlyMatchdayJob friendlyMatchdayFive(MatchFixtureRepository fixtures,
                                                   AsyncSimulationRunner runner) {
        return new FriendlyMatchdayJob("matchday-friendly-5", 5, fixtures, runner);
    }

    @Bean
    public FriendlyMatchdayJob friendlyMatchdaySeven(MatchFixtureRepository fixtures,
                                                     AsyncSimulationRunner runner) {
        return new FriendlyMatchdayJob("matchday-friendly-7", 7, fixtures, runner);
    }

    @Bean
    public MatchdayJob qualifierMatchdayTwo(CompetitionRepository competitions,
                                            MatchFixtureRepository fixtures, AsyncSimulationRunner runner) {
        return new NationalMatchdayJob("matchday-qualifier-2", NationalTournamentSchedule.QUALIFYING_WEEK,
                2, competitions, fixtures, runner);
    }

    @Bean
    public MatchdayJob qualifierMatchdayThree(CompetitionRepository competitions,
                                              MatchFixtureRepository fixtures, AsyncSimulationRunner runner) {
        return new NationalMatchdayJob("matchday-qualifier-3", NationalTournamentSchedule.QUALIFYING_WEEK,
                3, competitions, fixtures, runner);
    }

    @Bean
    public MatchdayJob qualifierMatchdayFour(CompetitionRepository competitions,
                                             MatchFixtureRepository fixtures, AsyncSimulationRunner runner) {
        return new NationalMatchdayJob("matchday-qualifier-4", NationalTournamentSchedule.QUALIFYING_WEEK,
                4, competitions, fixtures, runner);
    }

    @Bean
    public MatchdayJob qualifierMatchdayFive(CompetitionRepository competitions,
                                             MatchFixtureRepository fixtures, AsyncSimulationRunner runner) {
        return new NationalMatchdayJob("matchday-qualifier-5", NationalTournamentSchedule.QUALIFYING_WEEK,
                5, competitions, fixtures, runner);
    }

    @Bean
    public MatchdayJob qualifierMatchdaySix(CompetitionRepository competitions,
                                            MatchFixtureRepository fixtures, AsyncSimulationRunner runner) {
        return new NationalMatchdayJob("matchday-qualifier-6", NationalTournamentSchedule.QUALIFYING_WEEK,
                6, competitions, fixtures, runner);
    }

    /**
     * The four tournament days of week 12: round of 16 on day 1, quarter-finals day 2, semi-finals
     * day 4, third place and final on day 6.
     *
     * <p>Day 3 is absent because the owner skips it — the quarter-finals are on day 2 and the
     * semi-finals on day 4.
     */
    @Bean
    public MatchdayJob tournamentMatchdayOne(CompetitionRepository competitions,
                                             MatchFixtureRepository fixtures, AsyncSimulationRunner runner) {
        return new NationalMatchdayJob("matchday-tournament-1", NationalTournamentSchedule.TOURNAMENT_WEEK,
                1, competitions, fixtures, runner);
    }

    @Bean
    public MatchdayJob tournamentMatchdayTwo(CompetitionRepository competitions,
                                             MatchFixtureRepository fixtures, AsyncSimulationRunner runner) {
        return new NationalMatchdayJob("matchday-tournament-2", NationalTournamentSchedule.TOURNAMENT_WEEK,
                2, competitions, fixtures, runner);
    }

    @Bean
    public MatchdayJob tournamentMatchdayFour(CompetitionRepository competitions,
                                              MatchFixtureRepository fixtures, AsyncSimulationRunner runner) {
        return new NationalMatchdayJob("matchday-tournament-4", NationalTournamentSchedule.TOURNAMENT_WEEK,
                4, competitions, fixtures, runner);
    }

    @Bean
    public MatchdayJob tournamentMatchdaySix(CompetitionRepository competitions,
                                             MatchFixtureRepository fixtures, AsyncSimulationRunner runner) {
        return new NationalMatchdayJob("matchday-tournament-6", NationalTournamentSchedule.TOURNAMENT_WEEK,
                6, competitions, fixtures, runner);
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
