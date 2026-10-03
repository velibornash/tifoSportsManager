package org.example.footballmanager.newLogic.jobs.config;

import org.example.footballmanager.newLogic.jobs.impl.LeagueTableReconcileJob;
import org.example.footballmanager.newLogic.service.LeagueTableReconciliationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the nightly league-table repair (owner, 2026-10-01).
 *
 * <p>Two instances, because the two league matchdays are on days 3 and 7 and the done-flag is keyed on
 * (season, week, day, key) — one job on one day would leave the other matchday's results unreconciled
 * until the following week. This is the same reason {@code MatchdayJobsConfig} registers day 3 and day 7
 * separately, and for the same reason: a shared key lets one job suppress the other.
 *
 * <p>Not a {@code @Component}. {@link LeagueTableReconcileJob} takes its key and day as constructor
 * arguments, and a component whose constructor wants a {@code String} would ask Spring for a bean of
 * type String. The two instances are declared here instead, which is also where the day numbers are
 * legible next to the matchday days they follow.
 */
@Configuration
public class LeagueTableJobsConfig {

    /** The day after the day-3 league matchday. */
    public static final String KEY_AFTER_FIRST_LEAGUE_DAY = "league-table-reconcile-a";

    /** The day after the day-7 league matchday. */
    public static final String KEY_AFTER_SECOND_LEAGUE_DAY = "league-table-reconcile-b";

    /** Day 3 is the first league day, so this repairs the night after it. */
    private static final int DAY_AFTER_FIRST_LEAGUE_MATCHDAY = 4;

    /**
     * Day 7 is the second league day — and the repair runs on <b>day 7</b>, late.
     *
     * <p>This was <b>8</b>, and <b>day 8 does not exist</b>: the calendar is days 1 to 7
     * ({@code GameDay.LAST}), so {@code league-table-reconcile-b} could never fire. It had never run, and
     * nothing said so — the second league matchday's results were never reconciled, by a job that existed
     * specifically to reconcile them. Caught by {@code EveryGameDayHasAJobTest}, which asks what is
     * registered rather than what a document claims.
     *
     * <p><b>Day 1 of the following week is the day after day 7, and it is wrong here.</b> This job takes
     * its season from the dispatch context — "the season the runner dispatched in" — and the week rolls
     * over at day 7 hour 23, so by day 1 it would be handed the <em>new</em> season and rebuild tables
     * that were created minutes earlier, leaving the week it was meant to repair unrepaired.
     *
     * <p>So it runs on day 7 itself, at an hour with only two constraints and both are already fixed
     * elsewhere in the codebase: <b>after the 16:00 league match</b> it repairs, and <b>before the 23:00
     * week and season rollover</b> that would move the season out from under it.
     */
    private static final int DAY_AFTER_SECOND_LEAGUE_MATCHDAY = 7;

    /** Late enough that every match of the day-7 matchday is finished; before the 23:00 rollover. */
    private static final int HOUR_AFTER_SECOND_LEAGUE_MATCHDAY = 22;

    @Bean
    public LeagueTableReconcileJob leagueTableReconcileAfterFirstMatchday(
            LeagueTableReconciliationService tables) {
        return new LeagueTableReconcileJob(KEY_AFTER_FIRST_LEAGUE_DAY,
                DAY_AFTER_FIRST_LEAGUE_MATCHDAY, tables);
    }

    @Bean
    public LeagueTableReconcileJob leagueTableReconcileAfterSecondMatchday(
            LeagueTableReconciliationService tables) {
        return new LeagueTableReconcileJob(KEY_AFTER_SECOND_LEAGUE_DAY,
                DAY_AFTER_SECOND_LEAGUE_MATCHDAY, HOUR_AFTER_SECOND_LEAGUE_MATCHDAY, tables);
    }
}
