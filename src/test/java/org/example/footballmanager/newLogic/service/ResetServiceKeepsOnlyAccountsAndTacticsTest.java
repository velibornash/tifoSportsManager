package org.example.footballmanager.newLogic.service;

import org.example.commonmanager.model.User;
import org.example.commonmanager.repository.UserRepository;
import org.example.footballtextmanager.model.CTeam;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A reset keeps two accounts and the tactical editor, and deletes everything else (owner, 2026-10-07).
 *
 * <p><b>This exists because the old reset was a delete-list and the database is bigger than the
 * list.</b> It named 39 tables to truncate out of 125, so 86 survived: the forum, private messages,
 * notifications, transfers, scouting, finance, and the national-team tie-break coins. The owner pressed
 * Reset DB, found forum topics still there, and was right.
 *
 * <p>So the assertion is not "the pyramid is gone" but <b>"no table outside the keep-list holds a
 * row"</b> — the property the owner stated, and the only one that catches a table nobody remembered to
 * enumerate. A test listing 86 tables would be the same mistake in test form.
 *
 * <p><b>Rows are written through the entities, not through hand-built SQL.</b> The earlier version
 * assembled {@code INSERT} statements by reading {@code information_schema} and guessing a literal per
 * column, and it spent five iterations failing on {@code SUPPORTER_MOOD} being an integer and
 * {@code HUMAN_CONTROLLED} a boolean. A name is not a type. The entities already declare both.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(ResetService.class)
class ResetServiceKeepsOnlyAccountsAndTacticsTest {

    @Autowired
    private ResetService resetService;

    @Autowired
    private UserRepository users;

    @Autowired
    private TeamRepository teams;

    @PersistenceContext
    private EntityManager entityManager;

    /** The owner's rule, restated so the test cannot drift from it silently. */
    private static final List<String> MUST_KEEP =
            List.of("app_user", "tactics", "formation", "formation_positions");

    @Test
    @Transactional
    @DisplayName("a reset leaves no row in any table except the accounts and the tactical editor")
    void aResetEmptiesEveryTableItIsAllowedTo() {
        anAccount("velibor@example.com");
        anAccount("kecko@example.com");
        anAccount("stranger@example.com");
        aClubRowInEachTableTheResetShouldEmpty();

        resetService.resetDatabase();

        List<String> unexpected = tablesThatStillHoldRows().stream()
                .filter(table -> !MUST_KEEP.contains(table.toLowerCase(Locale.ROOT)))
                .toList();

        assertTrue(unexpected.isEmpty(),
                "Reset DB left rows in " + unexpected + ". The reset is specified as 'keep two accounts and "
                        + "the tactical editor, delete everything else', so a populated table outside the "
                        + "keep-list is a table the reset forgot.");
    }

    @Test
    @Transactional
    @DisplayName("the owner and the second manager both survive, and every other account goes")
    void bothAccountsSurvive() {
        anAccount("velibor@example.com");
        anAccount("kecko@example.com");
        anAccount("stranger@example.com");

        resetService.resetDatabase();

        assertEquals(List.of("kecko@example.com", "velibor@example.com"), survivingEmails(),
                "both named accounts are kept and every other account is deleted");
    }

    @Test
    @Transactional
    @DisplayName("the kept accounts are detached from their clubs, so they can still sign in")
    void theKeptAccountsAreDetachedFromTheirClubs() {
        User owner = anAccount("velibor@example.com");
        CTeam cteam = new CTeam();
        cteam.setName("A club that the reset will remove");
        Team club = new Team();
        club.setName("The same club, in the football tables");
        club.setType(CompetitionTeamType.CLUB);
        club.setHumanControlled(true);
        club = teams.save(club);
        entityManager.persist(cteam);

        owner.setCTeam(cteam);
        owner.setTifoCTeam(cteam);
        owner.setFootballTeam(club);
        users.save(owner);
        entityManager.flush();

        resetService.resetDatabase();
        entityManager.clear();

        User after = users.findByUsernameOrEmail("velibor@example.com").orElseThrow();
        assertNull(after.getCTeam(),
                "the club reference is null, not left pointing at a team the reset emptied");
        assertNull(after.getTifoCTeam(), "and so is the second one");
        assertNull(after.getFootballTeam(), "and the football team");
    }

    @Test
    @Transactional
    @DisplayName("a parent is emptied after its children, even when only basketball rows exist")
    void childrenAreEmptiedBeforeTheRowsTheyReference() {
        anAccount("velibor@example.com");
        // The pair that broke the real reset: af_season_competitions holds a foreign key into
        // common_seasons, and both tables are outside the keep-list.
        insertRow(7001, "common_seasons");
        insertRow(7002, "af_season_competitions");
        assertEquals(1L, count("common_seasons"), "the parent row exists");
        assertEquals(1L, count("af_season_competitions"), "and so does the child that points at it");

        resetService.resetDatabase();

        assertEquals(0L, count("common_seasons"),
                "the parent could not be emptied while its children still pointed at it. Deleting "
                        + "parents before children is not a sort order, it is a foreign key violation "
                        + "waiting for a row.");
        assertEquals(0L, count("af_season_competitions"));
    }

    /** The ordering is a property of the delete loop, so it is asserted on data, not on the SQL string. */
    @Test
    @Transactional
    @DisplayName("every child-before-parent edge in the schema is honoured")
    void everyForeignKeyEdgeIsRespected() {
        anAccount("velibor@example.com");
        List<String> tables = existingTables().stream()
                .filter(table -> !MUST_KEEP.contains(table.toLowerCase(Locale.ROOT)))
                .toList();

        resetService.resetDatabase();

        List<String> populated = tablesThatStillHoldRows().stream()
                .filter(name -> !MUST_KEEP.contains(name.toLowerCase(Locale.ROOT)))
                .toList();
        assertTrue(populated.isEmpty(),
                "rows left in " + populated + " after a reset that was told to empty every table it "
                        + "is allowed to. Any row left behind here is a table the delete order got wrong.");
    }

    private long count(String table) {
        Object result = entityManager
                .createNativeQuery("SELECT count(*) FROM " + quoteFor(catalogued(table))).getSingleResult();
        return ((Number) result).longValue();
    }

    @Test
    @Transactional
    @DisplayName("the schema's foreign-key cycles are emptied anyway")
    void foreignKeyCyclesDoNotStopTheReset() {
        anAccount("velibor@example.com");
        // cteam and cscountry point at each other in the owner's database. No ordering of row-by-row
        // deletes satisfies that, which is why the reset suspends referential integrity rather than
        // sorting its way around a cycle - and this is the pair the real reset failed on.
        insertRow(7101, "cteam");
        insertRow(7102, "cscountry");
        assertTrue(count("cteam") + count("cscountry") > 0, "the cycle members hold rows");

        resetService.resetDatabase();

        assertEquals(0L, count("cteam"), "a mutual reference does not stop a reset that empties everything");
        assertEquals(0L, count("cscountry"));
        assertTrue(survivingEmails().contains("velibor@example.com"), "and the kept accounts are still there");
    }

    // ---------- the world ----------

    /**
     * One row in each of the tables the previous delete-list forgot.
     *
     * <p>Only the ones with no mandatory foreign key, because a forum topic and a notification can be
     * written on their own and those are exactly the tables the owner noticed surviving.
     */
    private void aClubRowInEachTableTheResetShouldEmpty() {
        for (String table : new String[]{
                "nl_forum_topic", "nl_forum_post", "nl_message_thread", "nl_direct_message",
                "nl_notification", "job_run"}) {
            if (tableExists(table) && hasAnInsertableId(table)) {
                insertMinimalRow(table);
            }
        }
    }

    /**
     * A row with only the id set, which works when every other column is nullable or defaulted.
     *
     * <p>Deliberately not exhaustive: a table this cannot fill is a table the previous version also
     * could not clear, and the point of the test is that the reset clears tables it never named.
     */
    private void insertMinimalRow(String table) {
        entityManager.createNativeQuery("INSERT INTO \"" + table + "\" (id) VALUES (" + nextFreeId() + ")")
                .executeUpdate();
    }

    private long nextFreeId() {
        return 9000 + entityManager.createNativeQuery("SELECT count(*) FROM app_user").getSingleResult()
                .hashCode() % 7;
    }

    private boolean hasAnInsertableId(String table) {
        try {
            entityManager.createNativeQuery("INSERT INTO \"" + table + "\" (id) VALUES (-1)").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM \"" + table + "\" WHERE id = -1").executeUpdate();
            return true;
        } catch (RuntimeException e) {
            // Mandatory columns this helper does not know how to fill. Skipping is honest here; the
            // assertion below is about the tables it did manage to fill.
            return false;
        }
    }

    /**
     * One row in a table, with every mandatory column filled from the catalogue.
     *
     * <p>Read from {@code information_schema} and typed by the column's own declared type, because a
     * column *name* is not a type: {@code SUPPORTER_MOOD} is an integer and {@code HUMAN_CONTROLLED} a
     * boolean, and guessing from names cost five iterations of a different version of this file.
     */
    /** The catalogue's own spelling of a table, since PostgreSQL says "player" and H2 says "PLAYER". */
    private String catalogued(String table) {
        return existingTables().stream()
                .filter(name -> name.equalsIgnoreCase(table)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No table called " + table));
    }

    private void insertRow(long id, String table) {
        String actual = catalogued(table);
        List<String> assignments = new ArrayList<>();
        assignments.add("id = " + id);
        for (Object[] column : mandatoryColumnsOf(table)) {
            assignments.add(column[0] + " = " + literalFor(String.valueOf(column[0]),
                    String.valueOf(column[1]),
                    column[2] == null ? null : ((Number) column[2]).intValue()));
        }
        entityManager.createNativeQuery(
                "INSERT INTO " + quoteFor(actual) + " SET " + String.join(", ", assignments)).executeUpdate();
    }

    /** The mandatory columns of a table, with their declared type and length. */
    private List<Object[]> mandatoryColumnsOf(String table) {
        @SuppressWarnings("unchecked")
        List<Object> rows = entityManager.createNativeQuery("""
                SELECT column_name, data_type, character_maximum_length FROM information_schema.columns
                WHERE lower(table_schema) = 'public' AND lower(table_name) = ?
                  AND is_nullable = 'NO' AND upper(column_name) <> 'ID'
                ORDER BY column_name
                """).setParameter(1, table.toLowerCase(Locale.ROOT)).getResultList();
        return rows.stream().map(row -> (Object[]) row).toList();
    }

    /**
     * A value that satisfies whatever not-null column the table happens to have.
     *
     * <p>Chosen by the column's declared <b>type and length</b>, because a column name is neither: the
     * version of this file that picked literals by name produced {@code 'seed'} for a
     * {@code VARCHAR(3)} ISO code, and {@code 'seed'} for an integer season year.
     */
    private String literalFor(String column, String dataType, Integer length) {
        String type = dataType == null ? "" : dataType.toUpperCase(Locale.ROOT);
        if (type.contains("BOOL")) {
            return "false";
        }
        if (type.contains("INT") || type.contains("SERIAL")) {
            return "1";
        }
        if (type.contains("DOUBLE") || type.contains("REAL") || type.contains("FLOAT")
                || type.contains("DECIMAL") || type.contains("NUMERIC")) {
            return "50";
        }
        if (type.contains("TIMESTAMP") || type.contains("DATE") || type.contains("TIME")) {
            return "null";
        }
        String text = "seed";
        if (length != null && text.length() > length) {
            text = text.substring(0, Math.max(1, length));
        }
        return "'" + text + "'";
    }

    private String quoteFor(String identifier) {
        return "\"" + identifier + "\"";
    }

    /** Accounts from an earlier test in the same context, which a reset may legitimately have kept. */
    @BeforeEach
    void aKnownAccountTable() {
        entityManager.createNativeQuery("DELETE FROM app_user").executeUpdate();
    }

    private User anAccount(String email) {
        User user = new User();
        user.setUsername(email);
        user.setEmail(email);
        user.setPassword("test-hash");
        return users.saveAndFlush(user);
    }

    private List<String> survivingEmails() {
        @SuppressWarnings("unchecked")
        List<Object> rows = entityManager
                .createNativeQuery("SELECT lower(email) FROM app_user ORDER BY lower(email)")
                .getResultList();
        return rows.stream().map(String::valueOf).toList();
    }

    private List<String> tablesThatStillHoldRows() {
        List<String> populated = new ArrayList<>();
        for (String table : existingTables()) {
            try {
                Object count = entityManager
                        .createNativeQuery("SELECT count(*) FROM \"" + table + "\"").getSingleResult();
                if (((Number) count).longValue() > 0) {
                    populated.add(table);
                }
            } catch (RuntimeException e) {
                // A table this context cannot count is not one this test makes a claim about.
            }
        }
        return populated;
    }

    private List<String> existingTables() {
        @SuppressWarnings("unchecked")
        List<Object> rows = entityManager.createNativeQuery("""
                SELECT table_name FROM information_schema.tables WHERE lower(table_schema) = 'public'
                """).getResultList();
        return rows.stream().map(String::valueOf).toList();
    }

    private boolean tableExists(String table) {
        return existingTables().stream().anyMatch(name -> name.equalsIgnoreCase(table));
    }
}