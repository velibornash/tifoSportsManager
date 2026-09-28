package org.example.footballmanager.newLogic.jobs.impl;

import org.example.footballmanager.newLogic.jobs.DayJob;
import org.example.footballmanager.newLogic.jobs.JobContext;
import org.example.footballmanager.newLogic.service.FriendlyRequestService;
import org.example.footballmanager.newLogic.service.LoanService;
import org.example.footballmanager.newLogic.service.SeasonService;
import org.example.footballmanager.newLogic.service.TransferService;
import org.example.footballmanager.newLogic.service.YouthAcademyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Day 7, last hour: end-of-week rollover (owner, 2026-09-28).
 *
 * <p>This is the bundle that used to run inside {@code advanceWeekAndHandleSeasonTransition}, split
 * out rather than rewritten. Everything in it is week-scoped work that has no business firing
 * mid-week: injuries ticking down, fatigue recovering, contracts expiring, loans closing, youth
 * intake, and the transfer market moving.
 *
 * <p><b>Why day 7 at 23:00 and not day 1.</b> It has to land after the day-7 league round at 16:00
 * and before the next day-1 internationals at 20:45, so the week it closes is fully played. Firing it
 * on day 1 would settle a week that has not happened yet.
 *
 * <p><b>What was deliberately not split.</b> The owner asked for today's week-advance work to be
 * distributed into jobs with explicit triggers rather than re-homed by hand. These operations are
 * indivisible at the season level - a contract cannot expire on a Tuesday, and a loan does not close
 * for a day that has not been played - so they stay in one job with one trigger rather than being
 * scattered across seven days and run seven times.
 */
@Component
public class WeekRolloverJob implements DayJob {

    private static final Logger log = LoggerFactory.getLogger(WeekRolloverJob.class);

    public static final String KEY = "week-rollover";

    private final SeasonService seasons;
    private final YouthAcademyService youthAcademy;
    private final TransferService transfers;
    private final FriendlyRequestService friendlyRequests;
    private final LoanService loans;

    public WeekRolloverJob(SeasonService seasons, YouthAcademyService youthAcademy,
                           TransferService transfers, FriendlyRequestService friendlyRequests,
                           LoanService loans) {
        this.seasons = seasons;
        this.youthAcademy = youthAcademy;
        this.transfers = transfers;
        this.friendlyRequests = friendlyRequests;
        this.loans = loans;
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
        return 7;
    }

    @Override
    public int hour() {
        return 23;
    }

    @Override
    public int order() {
        return 90;
    }

    @Override
    public void run(JobContext context) {
        int seasonYear = context.seasonYear();
        int week = context.weekNumber();

        // These were protected methods on SeasonService, called from inside it. They are called
        // through the same service rather than duplicated, so there is one implementation of what
        // an injury tick and a fatigue recovery actually do.
        seasons.applyWeekMaintenance();

        int loansClosed = loans.closeFinishedLoans();
        int lapsed = friendlyRequests.expireStaleRequests();
        if (loansClosed > 0 || lapsed > 0) {
            log.info("Week {} season {}: {} loans finished, {} friendly requests lapsed.",
                    week, seasonYear, loansClosed, lapsed);
        }

        if (week == 2) {
            youthAcademy.generateSeasonIntakeForWeek2(seasonYear, week);
        } else {
            youthAcademy.progressActiveJuniorsWeekly(seasonYear, week);
        }

        transfers.simulateWeeklyMarketActivity();
    }
}
