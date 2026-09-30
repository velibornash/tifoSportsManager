package org.example.footballmanager.newLogic.jobs.impl;

import org.example.footballmanager.newLogic.jobs.DayJob;
import org.example.footballmanager.newLogic.jobs.JobContext;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.service.SeasonService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * End of the season: promotion, relegation and a fresh season (owner, 2026-09-28).
 *
 * <p>Fires in the last week of the season, at the end of day 7, and <b>after</b>
 * {@link WeekRolloverJob}. That ordering is declared, not accidental: the week has to be fully closed
 * - injuries ticked, contracts expired, table complete - before anyone is promoted or relegated out
 * of it.
 *
 * <p>This used to be the tail of {@code advanceWeekAndHandleSeasonTransition}, which meant the
 * season only turned over if somebody clicked Advance Week. Left there, it would never fire from an
 * advance-day, and a season played entirely through the day jobs would run to week 13 and keep going.
 */
@Component
public class SeasonRolloverJob implements DayJob {

    private static final Logger log = LoggerFactory.getLogger(SeasonRolloverJob.class);

    public static final String KEY = "season-rollover";

    private final SeasonService seasons;
    private final CompetitionRepository competitions;

    public SeasonRolloverJob(SeasonService seasons, CompetitionRepository competitions) {
        this.seasons = seasons;
        this.competitions = competitions;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public int week() {
        return SeasonService.WEEKS_PER_SEASON;
    }

    @Override
    public int day() {
        return 7;
    }

    @Override
    public int hour() {
        return 23;
    }

    @Override
    public int order() {
        // After week-rollover, which is 90. The week must be closed before it is judged.
        return 95;
    }

    @Override
    public void run(JobContext context) {
        // The guard used to be "did we find Superliga Srbije" — a Serbia-shaped question asked before a
        // country-agnostic job. A world with no Serbia would have skipped the rollover for every country
        // in it, including the ones it had just built pyramids for. The question now is the real one:
        // is there any league at all to roll over?
        boolean anyLeague = competitions.findAll().stream()
                .anyMatch(league -> league.getType()
                        == org.example.footballmanager.newLogic.model.CompetitionType.LEAGUE);
        if (!anyLeague) {
            log.warn("Season {} closed but the world holds no league divisions; nothing promoted.",
                    context.seasonYear());
            return;
        }
        seasons.performPromotionRelegationAndNewSeason();
        log.info("Season {} closed: promotion, relegation and the new season are done.",
                context.seasonYear());
    }
}
