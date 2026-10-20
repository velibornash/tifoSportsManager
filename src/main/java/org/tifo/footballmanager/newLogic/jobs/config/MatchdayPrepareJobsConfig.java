package org.tifo.footballmanager.newLogic.jobs.config;

import org.tifo.footballmanager.newLogic.jobs.DayJob;
import org.tifo.footballmanager.newLogic.jobs.impl.MatchdayPrepareJob;
import org.tifo.footballmanager.newLogic.jobs.impl.MatchdayPublishJob;
import org.tifo.footballmanager.newLogic.model.GameDay;
import org.tifo.footballmanager.newLogic.model.NationalTournamentSchedule;
import org.tifo.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.tifo.footballmanager.newLogic.service.MatchdayScheduler;
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

    /**
     * The national tournament pair, for the three days the club calendar has nothing on.
     *
     * <p><b>Days 1, 3, 5 and 7 need nothing here.</b> The club pair above is {@code ANY_WEEK} and
     * prepares the whole matchday, so a qualifier or tournament tie that falls on a league day is
     * already computed an hour early and released at kickoff by those. Registering a second pair for
     * them would not add a lock, only a race between two jobs over the same fixtures.
     *
     * <p>Days 2, 4 and 6 are the ones that would otherwise have nothing: no club match, no job, and a
     * national tie that started and finished with nobody watching. Both national weeks get a pair,
     * because without a week the job would be recorded as a successful matchday that played nothing in
     * every week of the season.
     *
     * <p>The kickoff hours come from {@code NationalTournamentSchedule}, not {@code GameDay}: these
     * days carry no kickoff in the club calendar and fall back to the national default.
     */
    private DayJob nationalPrepare(int day, int week) {
        return new MatchdayPrepareJob(day, NationalTournamentSchedule.kickoffHour(day), week,
                week == NationalTournamentSchedule.QUALIFYING_WEEK ? "qualifier" : "tournament",
                fixtures, scheduler);
    }

    private DayJob nationalPublish(int day, int week) {
        return new MatchdayPublishJob(day, NationalTournamentSchedule.kickoffHour(day), week,
                week == NationalTournamentSchedule.QUALIFYING_WEEK ? "qualifier" : "tournament",
                fixtures, scheduler);
    }

    @Bean
    public DayJob nationalPrepareQualifierDayTwo() {
        return nationalPrepare(2, NationalTournamentSchedule.QUALIFYING_WEEK);
    }

    @Bean
    public DayJob nationalPublishQualifierDayTwo() {
        return nationalPublish(2, NationalTournamentSchedule.QUALIFYING_WEEK);
    }

    @Bean
    public DayJob nationalPrepareQualifierDayFour() {
        return nationalPrepare(4, NationalTournamentSchedule.QUALIFYING_WEEK);
    }

    @Bean
    public DayJob nationalPublishQualifierDayFour() {
        return nationalPublish(4, NationalTournamentSchedule.QUALIFYING_WEEK);
    }

    @Bean
    public DayJob nationalPrepareQualifierDaySix() {
        return nationalPrepare(6, NationalTournamentSchedule.QUALIFYING_WEEK);
    }

    @Bean
    public DayJob nationalPublishQualifierDaySix() {
        return nationalPublish(6, NationalTournamentSchedule.QUALIFYING_WEEK);
    }

    @Bean
    public DayJob nationalPrepareTournamentDayTwo() {
        return nationalPrepare(2, NationalTournamentSchedule.TOURNAMENT_WEEK);
    }

    @Bean
    public DayJob nationalPublishTournamentDayTwo() {
        return nationalPublish(2, NationalTournamentSchedule.TOURNAMENT_WEEK);
    }

    @Bean
    public DayJob nationalPrepareTournamentDayFour() {
        return nationalPrepare(4, NationalTournamentSchedule.TOURNAMENT_WEEK);
    }

    @Bean
    public DayJob nationalPublishTournamentDayFour() {
        return nationalPublish(4, NationalTournamentSchedule.TOURNAMENT_WEEK);
    }

    @Bean
    public DayJob nationalPrepareTournamentDaySix() {
        return nationalPrepare(6, NationalTournamentSchedule.TOURNAMENT_WEEK);
    }

    @Bean
    public DayJob nationalPublishTournamentDaySix() {
        return nationalPublish(6, NationalTournamentSchedule.TOURNAMENT_WEEK);
    }
}
