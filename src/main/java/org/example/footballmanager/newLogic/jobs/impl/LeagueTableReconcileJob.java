package org.example.footballmanager.newLogic.jobs.impl;

import org.example.footballmanager.newLogic.jobs.DayJob;
import org.example.footballmanager.newLogic.jobs.JobContext;
import org.example.footballmanager.newLogic.service.ClubRatingService;
import org.example.footballmanager.newLogic.service.LeagueTableReconciliationService;
import org.example.footballmanager.newLogic.service.NationalRatingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rebuilds every league table from the results, the night after the football (owner, 2026-10-01).
 *
 * <p><b>01:00, and the day <i>after</i> the matchday rather than instead of it.</b> The table is written
 * incrementally when a match is persisted, so it is already correct when the manager watches his own
 * match — which is the moment he will look at it. This is the repair pass behind that, and it runs late
 * enough that every match of the matchday has been played and any interrupted run has had hours to be
 * noticed.
 *
 * <p>Two instances, one per league matchday, because the done-flag is keyed on (season, week, day, key):
 * a single job on one day would leave the other matchday's results unreconciled until the following week.
 * Days 3 and 7 are the league days, so this runs on days 4 and 8.
 *
 * <p>The repair itself is a rebuild from the match table rather than another adjustment. See
 * {@link LeagueTableReconciliationService} for why an increment cannot repair itself.
 */
public class LeagueTableReconcileJob implements DayJob {

    private static final Logger log = LoggerFactory.getLogger(LeagueTableReconcileJob.class);

    /** 01:00 — late enough that a fixture dragged in late by a slow simulation is finished. */
    private static final int RECONCILE_HOUR = 1;

    /**
     * The hour to use instead of {@link #RECONCILE_HOUR}, for a job that shares a day with the matchday
     * it repairs. Null for the ordinary case.
     *
     * <p>Day 4 at 01:00 genuinely <em>is</em> the morning after the day-3 match, so the default is
     * right there. The second instance repairs the <b>day-7</b> match and cannot use it: 01:00 on day 7 is
     * six hours <em>before</em> the match it exists to repair. It passes an hour late enough that the
     * matchday is finished, and before the 23:00 week and season rollover.
     */
    private final Integer hourOverride;

    private final String key;
    private final int day;
    private final LeagueTableReconciliationService tables;
    private final ClubRatingService clubRatings;
    private final NationalRatingService nationalRatings;

    public LeagueTableReconcileJob(String key, int day, LeagueTableReconciliationService tables,
                                   ClubRatingService clubRatings,
                                   NationalRatingService nationalRatings) {
        this(key, day, null, tables, clubRatings, nationalRatings);
    }

    public LeagueTableReconcileJob(String key, int day, Integer hourOverride,
                                   LeagueTableReconciliationService tables,
                                   ClubRatingService clubRatings,
                                   NationalRatingService nationalRatings) {
        this.key = key;
        this.day = day;
        this.hourOverride = hourOverride;
        this.tables = tables;
        this.clubRatings = clubRatings;
        this.nationalRatings = nationalRatings;
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public int week() {
        return ANY_WEEK;
    }

    @Override
    public int day() {
        return day;
    }

    @Override
    public int hour() {
        return hourOverride != null ? hourOverride : RECONCILE_HOUR;
    }

    @Override
    public int order() {
        // **After every job that can still be writing a result.** This returned 20, which is *before*
        // `MatchdayPublishJob` at 60 - the comment above it said this job "must not run first" and the
        // number said the opposite, so on any day the two shared, a matchday's results would be rated
        // and reconciled before they were released. The two currently sit on different days, which is
        // the only reason this never showed up.
        return 90;
    }

    @Override
    public void run(JobContext context) {
        // From the context, which already carries the season the runner dispatched in. Asking the clock
        // separately would be a second answer to "which season is it" and this codebase has been bitten
        // by exactly that twice.
        int season = context.seasonYear() > 0 ? context.seasonYear() : 1;
        LeagueTableReconciliationService.Result result = tables.reconcileAll(season);
        log.info("{}: season {} tables rebuilt ({} of {} competition(s)), {} entr(y/ies) corrected from "
                        + "{} played match(es).",
                key, season, result.tablesRebuilt(), result.competitions(),
                result.entriesCorrected(), result.matchesRead());

        // The ratings move here, and this is the only reason they ever did. `ClubRatingService` and
        // `NationalRatingService` are replayed on exactly two paths - the async batch and "advance
        // week" - and neither is the path the clock uses. A matchday played by the scheduler therefore
        // left every club on its seed: 844 played matches and not one club off it. A rebuild that is
        // already reading this week's results is the honest place to fold them into the ladder.
        ClubRatingService.Result clubs = clubRatings.recomputeIncrementallyDurably();
        log.info("{}: club Elo resumed - {} match(es) folded in, {} club(s) moved.",
                key, clubs.matchesReplayed(), clubs.clubsMoved());

        // Full replay, not a resume. National football starts in week 6, so this reads nothing for
        // most of a season, and its replay ends by applying a tournament qualification bonus - which a
        // resume would re-apply on every matchday and walk every country steadily upward. The national
        // replay is a pure function of the match table (48 countries, a few hundred matches), so running
        // it whole is both cheap and, by construction, safe to repeat.
        NationalRatingService.Result nations = nationalRatings.recomputeDurably();
        log.info("{}: national Elo replayed - {} match(es), {} nation(s) rated.",
                key, nations.matchesReplayed(), nations.countriesRated());
    }
}