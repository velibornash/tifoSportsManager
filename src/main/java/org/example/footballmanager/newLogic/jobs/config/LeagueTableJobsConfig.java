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

    /** Day 7 is the second league day, so this repairs the night after it. */
    private static final int DAY_AFTER_SECOND_LEAGUE_MATCHDAY = 8;

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
                DAY_AFTER_SECOND_LEAGUE_MATCHDAY, tables);
    }
}
