package org.example.footballmanager.newLogic.jobs.impl;

import org.example.footballmanager.newLogic.fantasy.FantasyScoringService;
import org.example.footballmanager.newLogic.jobs.DayJob;
import org.example.footballmanager.newLogic.jobs.JobContext;
import org.example.footballmanager.newLogic.model.GameDay;
import org.example.footballmanager.newLogic.repository.FantasyTeamRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Scores the fantasy gameweek (owner, 2026-10-17).
 *
 * <p><b>Day 7, at 17:00.</b> The last club matchday of a week kicks off at 16:00 and its publish job runs
 * at order 60; this runs an hour later at order 70, so by the time it looks, every league fixture of the
 * week is in the books.
 *
 * <p><b>It scores every finished week that has not been scored, not just this one.</b> A week whose
 * fixtures are still to play is skipped rather than scored as a half-week, and that skip is what makes the
 * catch-up work: if the app is down on the day, or a matchday runs long, the next run picks the week up
 * because the loop starts at week one. The scoring pass is idempotent, so the catch-up cannot double
 * anybody's points.
 *
 * <p>Only countries somebody has entered are scored. A country with no fantasy league has no totals to get
 * wrong, and walking all 48 every week to discover that would be work for nothing.
 */
@Component
public class FantasyScoringJob implements DayJob {

    private static final Logger log = LoggerFactory.getLogger(FantasyScoringJob.class);

    public static final String KEY = "fantasy-scoring";

    /** An hour after the day-7 kickoff, which is 16:00. */
    private static final int SCORING_HOUR = 17;

    private final FantasyScoringService scoring;
    private final FantasyTeamRepository fantasyTeams;

    public FantasyScoringJob(FantasyScoringService scoring, FantasyTeamRepository fantasyTeams) {
        this.scoring = scoring;
        this.fantasyTeams = fantasyTeams;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public int week() {
        return ANY_WEEK;
    }

    @Override
    public int day() {
        return GameDay.LEAGUE_SECOND_DAY;
    }

    @Override
    public int hour() {
        return SCORING_HOUR;
    }

    @Override
    public int order() {
        // After the day-7 publish job, which is 60. The football has to be in the books first.
        return 70;
    }

    @Override
    public void run(JobContext context) {
        int seasonYear = context.seasonYear();
        int weekNumber = context.weekNumber();

        List<Long> countries = fantasyTeams.findCountryIdsWithEntries(seasonYear);
        if (countries.isEmpty()) {
            log.debug("Fantasy: no entries in season {}, nothing to score.", seasonYear);
            return;
        }

        for (Long countryId : countries) {
            List<FantasyScoringService.ScoreResult> scored =
                    scoring.scoreFinishedWeeks(countryId, seasonYear, weekNumber);
            for (FantasyScoringService.ScoreResult result : scored) {
                log.info("Fantasy: country {} week {} scored — {} player(s), {} team(s).",
                        countryId, result.weekNumber(), result.playersScored(), result.teamsScored());
            }
        }
    }
}
