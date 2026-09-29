package org.example.footballmanager.newLogic.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResetService {

    @PersistenceContext
    private final EntityManager entityManager;

    @Transactional
    public void sanitizeLegacyLineupOrderSchema() {
        log.info("Checking lineup schema compatibility for legacy order columns...");
        dropLegacyColumnIfExists("lineup_starting_players", "slot_order");
        dropLegacyColumnIfExists("lineup_substitutes", "bench_order");
    }

    /**
     * MINUTE is a reserved word in H2 2.x, so MatchTickState now persists it as
     * {@code match_minute}. Existing dev/prod databases created with the old bare name keep
     * the legacy column, which Hibernate (ddl-auto=update) would simply leave behind, so drop
     * it here. The column is write-only - nothing queries by minute - so no data is lost.
     */
    @Transactional
    public void migrateTickStateMinuteColumn() {
        log.info("Checking match_tick_states minute column compatibility...");
        dropLegacyColumnIfExists("match_tick_states", "minute");
    }

    /**
     * One row per competition per season, and the duplicates that already exist are collapsed.
     *
     * <p>{@code season_competition} carried no unique constraint on (competition, season_year), and
     * {@code findByCompetitionAndSeasonYear} returns an {@code Optional} — so two rows for the same
     * league and season did not degrade, they threw: {@code NonUniqueResultException: Query did not
     * return a unique result: 3 results were returned}, and the league table and the club schedule
     * both went down with it.
     *
     * <p>The duplicates were not ancient. Rewriting the season column from a calendar year to a
     * season number means a world holding 2025 gets rewritten to 1, and anything that creates a
     * season row between those two moments asks for season 1 and finds nothing — so it creates one
     * beside the 2025 row instead of reusing it. Three boots later one league had three season
     * competitions, each with its own ten table entries, and which one the page read was arbitrary.
     *
     * <p>So this does two things, in this order. It collapses the duplicates that exist, keeping the
     * row that actually holds entries and the lowest id among equals, and then adds the constraint so
     * it cannot happen again. {@code ddl-auto=update} does not add constraints, so a fresh install
     * and an existing one both need it written here — the same reason the CHECK on
     * {@code competition.type} had to be widened by hand.
     */
    @Transactional
    public void enforceOneSeasonCompetitionPerSeason() {
        entityManager.flush();
        collapseDuplicateSeasonCompetitions();
        addUniqueConstraintIfMissing();
    }

    /**
     * Keeps one row per (competition, season_year) and deletes the others and their entries.
     *
     * <p>Nothing is moved. The duplicates are <em>copies</em>, not parallel records: every row was
     * created by {@code ensureEntriesForSeasonCompetition} reading a league's team list, so a
     * duplicate necessarily holds the same clubs as its survivor. Moving its entries onto the row
     * that is being kept would leave three copies of the same ten teams on one row.
     *
     * <p>The row with the most entries is the one kept, because that is the one a season has been
     * played into. Ties go to the lowest id, which is the row that existed first.
     */
    private void collapseDuplicateSeasonCompetitions() {
        // Ranked within each (competition, season) group, keeping the row with the most table
        // entries and the lowest id. The earlier version of this query tried to compare a correlated
        // subquery alias inside an EXISTS clause, where the alias does not exist - it compiled, and
        // would have thrown on the first boot that ran it.
        @SuppressWarnings("unchecked")
        List<Object[]> duplicates = entityManager.createNativeQuery("""
                WITH ranked AS (
                    SELECT sc.id,
                           sc.competition_id,
                           sc.season_year,
                           (SELECT count(*) FROM competition_entry ce
                             WHERE ce.season_competition_id = sc.id) AS entry_count,
                           ROW_NUMBER() OVER (
                               PARTITION BY sc.competition_id, sc.season_year
                               ORDER BY (SELECT count(*) FROM competition_entry ce2
                                          WHERE ce2.season_competition_id = sc.id) DESC,
                                        sc.id
                           ) AS position_in_group
                    FROM season_competition sc
                )
                SELECT id, competition_id, season_year, entry_count
                FROM ranked
                WHERE position_in_group > 1
                ORDER BY competition_id, season_year, id
                """).getResultList();

        if (duplicates.isEmpty()) {
            return;
        }

        int removed = 0;
        int entriesRemoved = 0;
        for (Object[] row : duplicates) {
            long id = ((Number) row[0]).longValue();
            int entries = row[3] == null ? 0 : ((Number) row[3]).intValue();
            entriesRemoved += entries;
            entityManager.createNativeQuery(
                    "DELETE FROM competition_entry WHERE season_competition_id = ?")
                    .setParameter(1, id)
                    .executeUpdate();
            entityManager.createNativeQuery("DELETE FROM season_competition WHERE id = ?")
                    .setParameter(1, id)
                    .executeUpdate();
            removed++;
        }
        log.warn("Collapsed {} duplicate season_competition row(s) and the {} duplicate table "
                + "entr(ies) on them. A league had more than one row for the same season, and "
                + "findByCompetitionAndSeasonYear throws on that rather than degrading - the league "
                + "table and the club schedule both went down. A unique constraint now prevents it.",
                removed, entriesRemoved);
    }

    private void addUniqueConstraintIfMissing() {
        // Look before leaping. The first version of this caught the "already exists" error instead,
        // and that does not work: a failed statement inside a transaction marks it rollback-only
        // whether or not you catch the exception, so the catch swallowed the error and the commit
        // then threw UnexpectedRollbackException and the application would not start at all. It is
        // the same trap the world catalogue fell into - caught, reported as success, and fatal at
        // commit. The end state of the world is worth less than the app booting.
        //
        // INFORMATION_SCHEMA is used rather than pg_constraint because the test profile runs on H2,
        // and both have it.
        Object existing = entityManager.createNativeQuery("""
                SELECT count(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
                WHERE CONSTRAINT_NAME = 'uk_season_competition_competition_season'
                  AND TABLE_NAME = 'season_competition'
                """).getSingleResult();
        int present = ((Number) existing).intValue();
        if (present > 0) {
            return;
        }

        entityManager.createNativeQuery("""
                ALTER TABLE season_competition
                ADD CONSTRAINT uk_season_competition_competition_season UNIQUE (competition_id, season_year)
                """).executeUpdate();
        log.info("Added unique constraint on season_competition (competition_id, season_year).");
    }

    /**
     * Full reset: clears everything and rebuilds from scratch (used by initialize-db).
     * Preserves only the owner account row during truncation.
     * Tactical editor profiles (team_tactics_profile) are handled via
     * snapshot/restore in the calling clearDatabaseOnly().
     */
    @Transactional
    public void resetDatabase() {
        log.warn("RESET DATABASE STARTED - preserving owner account and resetting all sport data");
        entityManager.flush();
        entityManager.clear();
        sanitizeLegacyLineupOrderSchema();
        preserveOwnerAccount();
        List<String> desiredOrder = List.of(
                "community_message",
                "registration_request",
                "training_week_report",
                "team_training_setup",
                "training",
                "team_tactics_profile",
                "match_tick_states",
                "match_player_stats",
                "match_event",
                "lineup_starting_players",
                "lineup_substitutes",
                "lineup",
                "match_fixture",
                "match",
                "promotion_rule",
                "competition_entry",
                "season_competition",
                "junior",
                "player",
                "team",
                "competition",
                "season",
                "country",
                "game_clock",
                "user",
                // Basketball tables (children before parents for TRUNCATE CASCADE safety)
                "bb_match_fixtures",
                "bb_matches",
                "bb_player_season_stats",
                "bb_competition_entries",
                "bb_season_competitions",
                "bb_players",
                "bb_teams",
                "bb_leagues",
                // American Football tables
                "af_match_fixtures",
                "af_matches",
                "af_player_season_stats",
                "af_competition_entries",
                "af_season_competitions",
                "af_players",
                "af_teams"
        );

        truncateTables(desiredOrder);

        // Remove basketball and American Football entries from the shared
        // common_competitions table (now safe — all bb_/af_ tables are empty)
        entityManager.createNativeQuery(
                "DELETE FROM common_competitions WHERE sport IN ('BASKETBALL', 'AMERICAN_FOOTBALL')").executeUpdate();

        List<String> existingNormalized = getExistingTableNames();
        if (existingNormalized.contains("app_user")) {
            entityManager.createNativeQuery("""
                    DELETE FROM app_user
                    WHERE lower(coalesce(username, '')) <> 'velibor@example.com'
                      AND lower(coalesce(email, '')) <> 'velibor@example.com'
                    """).executeUpdate();
        }

        log.warn("RESET DATABASE FINISHED - all sport data cleared, owner account preserved");
    }

    /**
     * Soft reset: clears only match/season/player/team data.
     * Preserves: all users (app_user, user), tactics profiles, countries, game_clock.
     * Also clears basketball and American Football match/player data.
     */
    @Transactional
    public void resetFootballDataOnly() {
        log.warn("SOFT RESET STARTED - preserving users, tactics profiles, countries and game clock");
        sanitizeLegacyLineupOrderSchema();

        List<String> desiredOrder = List.of(
                "community_message",
                "registration_request",
                "training_week_report",
                "team_training_setup",
                "training",
                "match_tick_states",
                "match_player_stats",
                "match_event",
                "lineup_starting_players",
                "lineup_substitutes",
                "lineup",
                "match_fixture",
                "match",
                "promotion_rule",
                "competition_entry",
                "season_competition",
                "junior",
                "player",
                "team",
                "competition",
                "season",
                // Basketball tables
                "bb_match_fixtures",
                "bb_matches",
                "bb_player_season_stats",
                "bb_competition_entries",
                "bb_season_competitions",
                "bb_players",
                "bb_teams",
                "bb_leagues",
                // American Football tables
                "af_match_fixtures",
                "af_matches",
                "af_player_season_stats",
                "af_competition_entries",
                "af_season_competitions",
                "af_players",
                "af_teams"
                // deliberately excluded: team_tactics_profile, app_user, user, country, game_clock
        );

        truncateTables(desiredOrder);

        // Remove basketball and American Football entries from the shared
        // common_competitions table (now safe — all bb_/af_ tables are empty)
        entityManager.createNativeQuery(
                "DELETE FROM common_competitions WHERE sport IN ('BASKETBALL', 'AMERICAN_FOOTBALL')").executeUpdate();

        // Detach all users from their teams (teams are gone after reset)
        List<String> existingNormalized = getExistingTableNames();
        if (existingNormalized.contains("app_user")) {
            entityManager.createNativeQuery("UPDATE app_user SET cteam_id = NULL, tifocteam_id = NULL").executeUpdate();
        }

        entityManager.clear();

        log.warn("SOFT RESET FINISHED - users, tactics profiles and base structure preserved");
    }

    private void preserveOwnerAccount() {
        List<String> existingNormalized = getExistingTableNames();
        if (existingNormalized.contains("app_user")) {
            entityManager.createNativeQuery("""
                    UPDATE app_user
                    SET cteam_id = NULL, tifocteam_id = NULL
                    WHERE lower(coalesce(username, '')) = 'velibor@example.com'
                       OR lower(coalesce(email, '')) = 'velibor@example.com'
                    """).executeUpdate();
        }
    }

    private List<String> getExistingTableNames() {
        @SuppressWarnings("unchecked")
        List<Object> existing = entityManager.createNativeQuery("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                """).getResultList();
        return existing.stream()
                .map(String::valueOf)
                .map(String::toLowerCase)
                .toList();
    }

    private void truncateTables(List<String> desiredOrder) {
        List<String> existingNormalized = getExistingTableNames();
        List<String> toTruncate = new ArrayList<>();
        for (String t : desiredOrder) {
            if (existingNormalized.contains(t.toLowerCase())) {
                if ("match".equals(t) || "user".equals(t)) {
                    toTruncate.add("\"" + t + "\"");
                } else {
                    toTruncate.add(t);
                }
            }
        }
        for (String tableName : toTruncate) {
            entityManager.createNativeQuery("TRUNCATE TABLE " + tableName + " RESTART IDENTITY CASCADE").executeUpdate();
        }
    }

    private void dropLegacyColumnIfExists(String tableName, String columnName) {
        // The column name is double-quoted because some of the legacy names are reserved words
        // (e.g. "minute" on H2 2.x), which cannot even be referenced unquoted in a DROP COLUMN.
        // Quoting is standard SQL and is accepted by both H2 and PostgreSQL.
        entityManager.createNativeQuery("ALTER TABLE IF EXISTS " + tableName + " DROP COLUMN IF EXISTS \"" + columnName + "\"")
                .executeUpdate();
        log.info("Schema compatibility ensured for {}.{}", tableName, columnName);
    }
}
