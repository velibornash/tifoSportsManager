package org.example.footballmanager.newLogic.jobs.config;

import org.example.footballmanager.newLogic.jobs.DayJob;
import org.example.footballmanager.newLogic.jobs.impl.MatchdayPrepareJob;
import org.example.footballmanager.newLogic.jobs.impl.MatchdayPublishJob;
import org.example.footballmanager.newLogic.model.GameDay;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.service.MatchdayScheduler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The pre-match and kickoff pair, per match day (owner, 2026-10-11).
 *
 * <p>Every club match day now runs two jobs instead of one:
 *
 * <ul>
 *   <li><b>an hour before kickoff</b> — the whole matchday is computed and every tie of that day is
 *       locked, so the manager can no longer change his tactics, his eleven or his orders;</li>
 *   <li><b>at kickoff</b> — the results are released and the watch links open.</li>
 * </ul>
 *
 * <p>Both hours are read from the seven-day template rather than restated, so the pair cannot drift
 * apart: day 1 is a 20:45 slot and so prepares at 19 and publishes at 20; the league days are 19:00 and
 * 16:00 and so prepare at 18 and 15.
 *
 * <p><b>Eight separate {@code @Bean} methods, on purpose.</b> This was first written as two beans
 * returning {@code List<DayJob>} and then as two arrays, and the whole pair was invisible to the
 * framework that schedules it: Spring does not fold either into the {@code List<DayJob>} that
 * {@code JobRunner} and {@code EveryGameDayHasAJobTest} read, so both versions compiled, both were
 * green, and neither job had ever run. Eight beans are dull and they are the shape the rest of this
 * package already uses.
 */
@Configuration
public class MatchdayPrepareJobsConfig {

    private final MatchFixtureRepository fixtures;
    private final MatchdayScheduler scheduler;

    public MatchdayPrepareJobsConfig(MatchFixtureRepository fixtures,
                                     MatchdayScheduler scheduler) {
        this.fixtures = fixtures;
        this.scheduler = scheduler;
    }

    @Bean
    public DayJob matchdayPrepareInternationals() {
        return new MatchdayPrepareJob(GameDay.INTERNATIONAL_DAY, fixtures, scheduler);
    }

    @Bean
    public DayJob matchdayPublishInternationals() {
        return new MatchdayPublishJob(GameDay.INTERNATIONAL_DAY, fixtures, scheduler);
    }

    @Bean
    public DayJob matchdayPrepareLeagueFirst() {
        return new MatchdayPrepareJob(GameDay.LEAGUE_FIRST_DAY, fixtures, scheduler);
    }

    @Bean
    public DayJob matchdayPublishLeagueFirst() {
        return new MatchdayPublishJob(GameDay.LEAGUE_FIRST_DAY, fixtures, scheduler);
    }

    @Bean
    public DayJob matchdayPrepareCup() {
        return new MatchdayPrepareJob(GameDay.CUP_DAY, fixtures, scheduler);
    }

    @Bean
    public DayJob matchdayPublishCup() {
        return new MatchdayPublishJob(GameDay.CUP_DAY, fixtures, scheduler);
    }

    @Bean
    public DayJob matchdayPrepareLeagueSecond() {
        return new MatchdayPrepareJob(GameDay.LEAGUE_SECOND_DAY, fixtures, scheduler);
    }

    @Bean
    public DayJob matchdayPublishLeagueSecond() {
        return new MatchdayPublishJob(GameDay.LEAGUE_SECOND_DAY, fixtures, scheduler);
    }
}