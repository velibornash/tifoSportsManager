package org.example.footballmanager.newLogic.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rewrites the season column from a calendar year to a season number (owner, 2026-09-29).
 *
 * <p>A season is a number counted from 1. It was written as a calendar year instead -
 * {@code BASE_SEASON_YEAR} was 2025 and every caller asked for {@code BASE_SEASON_YEAR + (season - 1)} -
 * and the two are not interchangeable: a manager's season is twelve weeks, so four of them run in a
 * year and no calendar year can name one. Everything that reads the column now asks the game clock,
 * so a world still holding 2025 finds nothing and behaves like a world with no football in it.
 *
 * <p>Every value at or above 1000 is a calendar year, and every world that had one was on its first
 * season, so all of them map to 1. That test is what makes this safe to run twice: after the first
 * pass there is nothing left above 1000, so the migration is a no-op from then on. It carries no year
 * of its own, which is the point - the constant it replaces is the thing that let two artefacts
 * disagree about which season a cup tie belonged to.
 *
 * <p>Its own transaction, for the reason the day backfill needs one: this runs inside the boot
 * listener's transaction, and a save that joins somebody else's transaction is a save that may never
 * have happened. A migration that logs its work and rolls back is the worst of both.
 */
@Component
public class SeasonNumberBackfill {

    private static final Logger log = LoggerFactory.getLogger(SeasonNumberBackfill.class);

    /** A calendar year is four digits; a season number is not. This is how the two are told apart. */
    private static final int FIRST_SEASON = 1;
    private static final int CALENDAR_YEAR_FLOOR = 1000;

    /**
     * The football tables that hold a season, and nothing else.
     *
     * <p>Deliberately excludes the other games: {@code af_*}, {@code bb_*} and {@code common_seasons}
     * are the American football and basketball seasons, which carry their own year and are not this
     * change. {@code csseason_competition} is excluded because no Java class owns it - it is a legacy
     * table from the old nine-country seed, and rewriting a season in a table nothing reads would be
     * changing data for its own sake.
     *
     * <p>{@code match} is quoted because it is a keyword in H2, which is the database the test
     * profile runs on. On a fresh test database there is never anything to rewrite, so the statement
     * is a no-op there - but it still has to parse.
     *
     * <p>Only tables that really carry the column are listed. {@code loan}, {@code friendly_request}
     * and {@code player_training_intensity} have a {@code season} column instead, which is a
     * different question and is not answered here.
     */
    private static final String[] SEASON_COLUMNS = {
            "match_fixture", "season", "season_competition",
            "national_team_election", "job_run", "finance_ledger_entry",
            "\"match\"", "new_logic_match", "new_logic_season"
    };

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate requiresNew;

    public SeasonNumberBackfill(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * How many rows a table still holds at a calendar year, or 0 when the table is not there.
     *
     * <p>A missing table is not an error. {@code ddl-auto=update} creates what the entities need, so
     * on a fresh install several of these do not exist yet and there is nothing in them to rewrite.
     */
    private int countAtCalendarYear(String table) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM " + table + " WHERE season_year >= " + CALENDAR_YEAR_FLOOR,
                    Integer.class);
            return count == null ? 0 : count;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** @return how many rows were rewritten, per table, for tables that actually had any. */
    public Map<String, Integer> backfill() {
        Map<String, Integer> changed = new LinkedHashMap<>();
        List<String> missingOrEmpty = new ArrayList<>();

        requiresNew.executeWithoutResult(status -> {
            for (String table : SEASON_COLUMNS) {
                int before = countAtCalendarYear(table);
                if (before == 0) {
                    missingOrEmpty.add(table);
                    continue;
                }
                int after = jdbcTemplate.update(
                        "UPDATE " + table + " SET season_year = ? WHERE season_year >= " + CALENDAR_YEAR_FLOOR,
                        FIRST_SEASON);
                if (after > 0) {
                    changed.put(table, after);
                }
            }
        });

        if (changed.isEmpty()) {
            return changed;
        }
        int total = changed.values().stream().mapToInt(Integer::intValue).sum();
        log.info("Season numbers: rewrote {} row(s) from a calendar year to season {}. {}",
                total, FIRST_SEASON, changed);
        log.info("Season numbers: no season value at or above {} remains in {}", CALENDAR_YEAR_FLOOR,
                changed.keySet());
        return changed;
    }
}
