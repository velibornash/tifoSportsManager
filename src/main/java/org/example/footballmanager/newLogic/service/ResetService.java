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
     * The accounts a reset must not delete.
     *
     * <p><b>The owner's rule (2026-10-07): a reset keeps the owner account, the second manager, and the
     * tactical-editor data. Everything else goes.</b> Two accounts, named rather than "the first row":
     * a rule that preserves whatever happens to be id 1 deletes whichever manager registered first,
     * which is not the same person twice.
     */
    private static final List<String> PRESERVED_ACCOUNTS = List.of(
            "velibor@example.com",
            "kecko@example.com");

    /**
     * Tables a reset leaves alone.
     *
     * <p>Stated as a keep-list rather than the old delete-list, and that inversion is the fix. The
     * previous version enumerated 39 tables to truncate, and the database has 125 — so **86 tables were
     * never touched**, including the whole forum (`nl_forum_topic`, `nl_forum_post`), private messages
     * (`nl_message_thread`, `nl_direct_message`), notifications, transfers, scouting, finance and the
     * national-team tie-break coins. The owner pressed Reset DB, saw forum topics and messages still
     * there, and was right.
     *
     * <p>A delete-list is a promise to remember every table the application will ever add. A keep-list
     * is a promise about the three things that must survive, and a new table is cleared by default,
     * which is the correct default for a button labelled "delete everything".
     *
     * <p>{@code team_tactics_profile} is deliberately <b>not</b> here: it carries a foreign key to
     * {@code team}, so truncating the teams cascades it away regardless. It is preserved by the
     * snapshot/restore its caller does around this call, keyed on team name for exactly that reason.
     */
    private static final List<String> PRESERVED_TABLES = List.of(
            "app_user",
            "user",
            "tactics",
            "formation",
            "formation_positions");

    /**
     * Full reset: clears everything except the two owner accounts and the tactical-editor data.
     *
     * <p>Every other table is truncated, whatever it is called and whenever it was added — clubs,
     * players, fixtures, results, leagues, cups, forums, messages, notifications, transfers, scouting,
     * finance and the national-team competitions alike. The national-team competitions are included on
     * purpose: they are world data like any other, and leaving four orphan tournament rows behind a
     * reset is how "the qualifying groups are still there after I reset" happens.
     */
    @Transactional
    public void resetDatabase() {
        log.warn("RESET DATABASE STARTED - keeping {} and the tactical editor data",
                PRESERVED_ACCOUNTS);
        entityManager.flush();
        entityManager.clear();
        sanitizeLegacyLineupOrderSchema();
        preserveOwnerAccount();
        truncateEverythingExceptTheKeepList();
        deleteEveryOtherAccount();

        log.warn("RESET DATABASE FINISHED - all world data cleared, {} and tactics kept",
                PRESERVED_ACCOUNTS);
    }

    /**
     * Empties every table except the keep-list.
     *
     * <p><b>Three implementations failed here, and each is recorded because the failures were not
     * obvious from reading the code.</b>
     *
     * <ol>
     * <li>A hand-maintained list of tables to truncate, out of 125. <b>86 survived.</b>
     * <li>A children-first DELETE ordering. The schema has genuine cycles — {@code cteam ↔ cscountry},
     * {@code new_logic_lineup ↔ new_logic_match}, {@code country ↔ team} — and <b>no ordering of
     * row-by-row deletes satisfies an immediate foreign key around a cycle</b>.
     * <li>Suspending referential integrity with {@code SET session_replica_role = 'replica'}. <b>That
     * parameter does not exist</b> — the real one is {@code session_replication_role} — so it shipped
     * "unrecognized configuration parameter" and took the Reset DB button out entirely.
     * <li>One {@code TRUNCATE ... CASCADE}. It runs, it clears the cycles, and it is <b>still wrong</b>:
     * {@code CASCADE} follows references in <em>both</em> directions, and eight tables
     * ({@code nl_forum_topic}, {@code nl_notification}, the message tables and four more) reference
     * {@code app_user} — so truncating them emptied the accounts this reset is supposed to keep.
     * Measured on the owner's schema: truncating {@code nl_notification} alone took {@code app_user}
     * from 8 rows to 5.
     * </ol>
     *
     * <p>So: the ordered deletes, with referential integrity suspended under the <b>correct</b>
     * parameter name, and the restore in a {@code finally} so a failure cannot leave the database
     * running without its foreign keys.
     */
    private void truncateEverythingExceptTheKeepList() {
        List<String> tables = getExistingTableNames().stream()
                .filter(table -> !isPreserved(table))
                .toList();
        if (tables.isEmpty()) {
            return;
        }
        deleteWithIntegritySuspended(tables);
    }

    /**
     * The fallback for a database whose {@code TRUNCATE} cannot take a list — H2, which is what the
     * test suite runs on.
     *
     * <p>Kept separate because it is <b>not</b> the production path and cannot be: it is wrong where
     * there are cycles, which is exactly what the production schema has.
     */
    private void deleteOneByOne(List<String> tables) {
        List<String> childrenFirst = childrenBeforeParents(tables);
        for (String table : childrenFirst) {
            String quoted = quote(table);
            entityManager.createNativeQuery("DELETE FROM " + quoted).executeUpdate();
            restartIdentityOf(table, quoted);
        }
        log.warn("Emptied {} table(s) for the reset, children first.", childrenFirst.size());
    }

    /**
     * Empties the tables, children first, with foreign-key enforcement suspended.
     *
     * <p>{@code session_replication_role} is superuser-only and also disables triggers, which is why
     * the alternative — dropping and recreating constraints — was not taken: it is more code, more
     * state to get wrong, and it leaves the schema touched if the reset dies halfway.
     */
    private void deleteWithIntegritySuspended(List<String> tables) {
        boolean suspended = suspendReferentialIntegrity();
        try {
            deleteOneByOne(tables);
        } finally {
            if (suspended) {
                resumeReferentialIntegrity();
            }
        }
    }

    /**
     * Turns foreign-key enforcement off for this connection, and reports whether it was needed.
     *
     * <p>Two dialects, one attempt each: PostgreSQL takes {@code session_replication_role}, H2 takes
     * {@code REFERENTIAL_INTEGRITY}. A dialect that rejects both falls back to the ordered delete,
     * which is correct wherever there is no cycle.
     */
    private boolean suspendReferentialIntegrity() {
        for (String statement : List.of(
                "SET session_replication_role = 'replica'",
                "SET REFERENTIAL_INTEGRITY FALSE")) {
            try {
                entityManager.createNativeQuery(statement).executeUpdate();
                // **Verified, not assumed.** `SET` through Hibernate can return without taking effect -
                // a pooled connection can be handed back between the SET and the DELETEs, and the
                // failure then shows up as a foreign key violation a long way from its cause. Asking
                // the session what it is now set to is one cheap query and it is the difference between
                // "we suspended the constraints" and "we ran a statement that looked like we did".
                String now = String.valueOf(entityManager
                        .createNativeQuery(replicationRoleOf())
                        .getSingleResult());
                if (now.toLowerCase(java.util.Locale.ROOT).contains("replica")
                        || now.toLowerCase(java.util.Locale.ROOT).contains("false")) {
                    log.info("Referential integrity suspended ({} = {}).", statement, now);
                    return true;
                }
                log.warn("{} ran but the session still reports {}.", statement, now);
            } catch (RuntimeException e) {
                log.debug("{} not accepted here: {}", statement, e.getMessage());
            }
        }
        return false;
    }

    /** The setting each dialect uses to report its own state. */
    private String replicationRoleOf() {
        return "SELECT current_setting('session_replication_role')";
    }

    private void resumeReferentialIntegrity() {
        for (String statement : List.of(
                "SET session_replication_role = 'origin'",
                "SET REFERENTIAL_INTEGRITY TRUE")) {
            try {
                entityManager.createNativeQuery(statement).executeUpdate();
                return;
            } catch (RuntimeException e) {
                log.debug("{} not accepted here: {}", statement, e.getMessage());
            }
        }
    }

    /**
     * Orders tables so a table is emptied before the tables that point at it.
     *
     * <p>Read from the catalogue rather than hard-coded. A table that cannot be ordered — a cycle, or a
     * dependency on a preserved table — keeps its catalogue position rather than looping. **The ordering
     * is now only a nicety**: with referential integrity suspended the deletes succeed in any order,
     * and it is kept so the common case still behaves as the schema describes.
     */
    List<String> childrenBeforeParents(List<String> tables) {
        @SuppressWarnings("unchecked")
        List<Object[]> edges = entityManager.createNativeQuery("""
                SELECT tc.table_name, ccu.table_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.constraint_column_usage ccu
                  ON tc.constraint_name = ccu.constraint_name
                WHERE tc.constraint_type = 'FOREIGN KEY'
                  AND lower(tc.table_schema) = 'public'
                """).getResultList();

        List<String> remaining = new ArrayList<>(tables);
        List<String> ordered = new ArrayList<>();
        while (!remaining.isEmpty()) {
            // edge[0] is the child and edge[1] the parent, so a table is safe to empty when nothing
            // still remaining points AT it.
            List<String> safe = remaining.stream()
                    .filter(candidate -> !edges.stream()
                            .anyMatch(edge -> names(edge[1], candidate) && isIn(remaining, edge[0])))
                    .findFirst()
                    .map(List::of)
                    .orElse(List.of());
            if (safe.isEmpty()) {
                ordered.addAll(remaining);
                break;
            }
            ordered.add(safe.get(0));
            remaining.remove(safe.get(0));
        }
        return ordered;
    }

    private boolean names(Object catalogueName, String candidate) {
        return String.valueOf(catalogueName).equalsIgnoreCase(candidate);
    }

    private boolean isIn(List<String> tables, Object catalogueName) {
        return tables.stream().anyMatch(table -> names(catalogueName, table));
    }

    /**
     * Restarts one table's identity columns, so a rebuilt world gets ids from 1 again.
     *
     * <p>Best-effort per column: a table with no identity column, or a database that spells the
     * statement differently, must not stop a reset that has already deleted the rows.
     */
    private void restartIdentityOf(String table, String quoted) {
        for (String column : identityColumnsOf(table)) {
            try {
                entityManager.createNativeQuery(
                        "ALTER TABLE " + quoted + " ALTER COLUMN " + quote(column) + " RESTART WITH 1")
                        .executeUpdate();
            } catch (RuntimeException e) {
                log.debug("Could not restart identity on {}.{}: {}", table, column, e.getMessage());
            }
        }
    }

    private List<String> identityColumnsOf(String table) {
        @SuppressWarnings("unchecked")
        List<Object> rows = entityManager.createNativeQuery("""
                SELECT column_name FROM information_schema.columns
                WHERE lower(table_schema) = 'public' AND lower(table_name) = ?
                  AND is_identity = 'YES'
                """).setParameter(1, table.toLowerCase(java.util.Locale.ROOT)).getResultList();
        return rows.stream().map(String::valueOf).toList();
    }

    /**
     * Quotes an identifier exactly as the catalogue spelled it.
     *
     * <p>PostgreSQL reports {@code "player"} and H2 reports {@code "PLAYER"}, and an unquoted name
     * resolves to whatever the database folds it to — which is the difference between emptying a table
     * and a syntax error. The names come from {@code information_schema}, never from a request, so this
     * cannot be used to reach a table that is not there.
     */
    private String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /**
     * Deletes every account except the two the owner keeps.
     *
     * <p>A row-level delete rather than a truncate, because {@code app_user} is on the keep-list — its
     * rows have to survive individually, and only some of them do.
     */
    private void deleteEveryOtherAccount() {
        if (!containsIgnoreCase(getExistingTableNames(), "app_user")) {
            return;
        }
        String placeholders = String.join(", ", PRESERVED_ACCOUNTS.stream().map(a -> "'" + a + "'").toList());
        int removed = entityManager.createNativeQuery(
                "DELETE FROM app_user WHERE lower(coalesce(email, '')) NOT IN (" + placeholders + ")")
                .executeUpdate();
        log.warn("Reset removed {} account(s); {} kept.", removed, PRESERVED_ACCOUNTS);
    }

    /**
     * The previous implementation, kept only as the record of what the reset used to miss.
     *
     * <p>Deleted deliberately rather than commented out: the 39-table list is what let 86 tables
     * survive, and leaving it in the file is leaving the reason in the file.
     */
    private List<String> supersededTruncateList() {
        return List.of(
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
        if (containsIgnoreCase(existingNormalized, "app_user")) {
            entityManager.createNativeQuery("UPDATE app_user SET cteam_id = NULL, tifocteam_id = NULL").executeUpdate();
        }

        entityManager.clear();

        log.warn("SOFT RESET FINISHED - users, tactics profiles and base structure preserved");
    }

    /**
     * Detaches the kept accounts from their clubs before the clubs are truncated.
     *
     * <p>Both kept accounts, for the same reason they are both kept: nulling one and truncating the
     * other's club is how a reset leaves a manager unable to log in.
     *
     * <p>{@code NULL} rather than a delete, because the account row is the one thing being preserved.
     */
    private void preserveOwnerAccount() {
        List<String> existingNormalized = getExistingTableNames();
        if (!containsIgnoreCase(existingNormalized, "app_user")) {
            return;
        }
        String placeholders = String.join(", ", PRESERVED_ACCOUNTS.stream().map(a -> "'" + a + "'").toList());
        // **All five team columns, and the list is read rather than written.** This used to name
        // `cteam_id` and `tifocteam_id` only, which left `football_team_id` pointing at a team the
        // reset was about to empty - and since teams are deleted before accounts, the reset then failed
        // on the foreign key rather than completing. The owner would have seen "Database job 'reset'
        // failed" with a constraint violation and a half-cleared world.
        //
        // The columns are discovered from the catalogue so the next sport added to this application
        // cannot repeat the omission, which is the whole failure mode here: a list of columns that is
        // quietly shorter than the table.
        List<String> teamColumns = teamReferenceColumns();
        if (teamColumns.isEmpty()) {
            return;
        }
        String nulls = String.join(", ", teamColumns.stream().map(column -> column + " = NULL").toList());
        int detached = entityManager.createNativeQuery(
                "UPDATE app_user SET " + nulls + " WHERE lower(coalesce(email, '')) IN (" + placeholders + ")")
                .executeUpdate();
        log.warn("Detached {} kept account(s) from their clubs before the reset emptied them.", detached);
    }

    /**
     * The {@code app_user} columns that reference a club in some other table.
     *
     * <p>Every foreign key on {@code app_user}, found by asking the catalogue rather than by listing
     * them. The point is that the previous version named two of five, and nothing failed until a reset
     * ran against a database where the third happened to be set.
     */
    private List<String> teamReferenceColumns() {
        @SuppressWarnings("unchecked")
        List<Object> rows = entityManager.createNativeQuery("""
                SELECT kcu.column_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                 AND tc.table_schema = kcu.table_schema
                WHERE tc.constraint_type = 'FOREIGN KEY'
                  AND lower(tc.table_schema) = 'public'
                  AND lower(tc.table_name) = 'app_user'
                ORDER BY kcu.column_name
                """).getResultList();
        return rows.stream().map(String::valueOf).map(name -> "\"" + name + "\"").toList();
    }

    private List<String> getExistingTableNames() {
        @SuppressWarnings("unchecked")
        List<Object> existing = entityManager.createNativeQuery("""
                SELECT table_name
                FROM information_schema.tables
                WHERE lower(table_schema) = 'public'
                """).getResultList();
        // **Not** lower-cased. The catalogue's own casing is the difference between the two databases
        // this code runs in: PostgreSQL reports "player", H2 reports "PLAYER", and a name is only
        // resolvable if it is quoted exactly as the catalogue spelled it. Callers that want to compare
        // against a list fold case themselves.
        return existing.stream().map(String::valueOf).toList();
    }

    /** Whether a catalogue list holds a name, ignoring case - PostgreSQL and H2 disagree on casing. */
    private boolean containsIgnoreCase(List<String> names, String wanted) {
        String lower = wanted.toLowerCase(java.util.Locale.ROOT);
        return names.stream().anyMatch(name -> name.toLowerCase(java.util.Locale.ROOT).equals(lower));
    }

    /** Whether a catalogue table name is one of the keep-list entries, ignoring case. */
    private boolean isPreserved(String table) {
        return PRESERVED_TABLES.contains(table.toLowerCase(java.util.Locale.ROOT));
    }

    private void truncateTables(List<String> desiredOrder) {
        List<String> existingNormalized = getExistingTableNames();
        List<String> toTruncate = new ArrayList<>();
        for (String t : desiredOrder) {
            if (containsIgnoreCase(existingNormalized, t)) {
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
