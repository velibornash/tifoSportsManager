package org.example.footballmanager.newLogic.service;

import org.example.commonmanager.model.User;
import org.example.commonmanager.model.UserRole;
import org.example.commonmanager.repository.UserRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The reset on a database whose foreign keys are **real** (owner, 2026-10-07).
 *
 * <p><b>This is the test that had to exist.</b> Three attempts at the reset were made against the H2 test
 * schema and against the owner's PostgreSQL separately, and the one that reached the owner's database was
 * never run against a database with data in it — so it shipped a typo
 * ({@code session_replica_role}, which does not exist; the real parameter is
 * {@code session_replication_role}) and took the Reset DB button out entirely.
 *
 * <p>It runs on the <b>real</b> datasource, which is what makes it different from every other reset test:
 *
 * <ul>
 * <li>H2 has no {@code TRUNCATE ... CASCADE} and no multi-table truncate, so H2 exercises the
 * <em>fallback</em> path only. The production path — one statement, cycles resolved by the database —
 * was never executed by any test.
 * <li>The test schema does not carry the basketball and legacy foreign keys, so a wrongly ordered
 * delete completes there and the suite stays green.
 * </ul>
 *
 * <p>Everything outside the keep-list is emptied; the two named accounts and the tactical editor are not.
 * The assertion is over the live catalogue rather than a list written in the test, because the point is
 * "every table this database actually has".
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("resetprobe")
@Import(ResetService.class)
class ResetServiceOnRealPostgresTest {

    @Autowired
    private ResetService resetService;

    @Autowired
    private UserRepository users;

    @Autowired
    private CountryRepository countries;

    @Autowired
    private TeamRepository teams;

    @Autowired
    private MatchFixtureRepository fixtures;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Autowired
    private javax.sql.DataSource dataSource;

    @Test
    @Transactional
    @DisplayName("Reset DB completes on a real PostgreSQL with its foreign-key cycles")
    void theResetRunsOnRealPostgres() {
        // **Refuses to run against the owner's world.** This test deletes every row in the database it
        // is given, so it demands a scratch one by name. A destructive test whose target is a config
        // value is one edit away from emptying the real world, and the check that stops it has to be
        // in the same file as the edit.
        // Read from the DataSource rather than from the EntityFactory: Hibernate does not expose
        // the JDBC url in its properties under that key, so the guard was silently reading "null" and
        // skipping - which would have left this test never having run while looking green.
        String url = directUrl();
        assumeTrue(url != null && url.contains("reset_probe"),
                "refusing to run: the datasource is not a scratch database (" + url + "). Set "
                        + "test.reset.jdbc-url to a throwaway database for this test.");

        long tablesBefore = tables().size();
        long countriesBefore = countries.count();
        long teamsBefore = teams.count();
        assertTrue(tablesBefore > 50, "this is the real schema, with " + tablesBefore + " tables");
        assertTrue(countriesBefore > 0, "and there is a world to clear: " + countriesBefore + " countries");

        // Only the one that must not survive: the two keepers already exist in this database, which is
        // the point - the reset has to *keep* them, not create them.
        aKeptAccount("stranger@example.com");

        // The whole point: this is what the button does, on this database.
        resetService.resetDatabase();

        assertEquals(0L, countries.count(), "every country is cleared");
        assertEquals(0L, teams.count(), "every team is cleared");
        assertEquals(0L, fixtures.count(), "and every fixture");

        // A new list: populatedTables() returns an immutable stream result, and the failure would
        // otherwise be about the test's own plumbing rather than about the reset.
        List<String> surviving = new java.util.ArrayList<>(populatedTables());
        surviving.removeIf(name -> List.of("app_user", "user", "tactics", "formation", "formation_positions")
                .contains(name.toLowerCase(Locale.ROOT)));
        assertTrue(surviving.isEmpty(),
                "Reset DB left rows in " + surviving + ". This is the failure the owner reported - the "
                        + "button must empty everything outside the keep-list on THIS database.");

        // Read through the entity manager with the persistence context cleared: the reset ran inside
        // this transaction, so the repository may still be serving a first-level cache that predates it.
        entityManager.clear();
        List<String> emails = users.findAll().stream().map(User::getEmail).sorted().toList();
        assertEquals(List.of("kecko@example.com", "velibor@example.com"), emails,
                "the two named accounts survive and every other account is deleted");
    }

    /**
     * The JDBC url of the database this run is pointed at, read through the same DataSource Spring
     * gave the test, so the guard checks what is actually in use rather than a config file.
     */
    private String directUrl() {
        try (java.sql.Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getURL();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Could not read the datasource url", e);
        }
    }

    // ---------- helpers ----------

    private void aKeptAccount(String email) {
        if (users.findByUsernameOrEmail(email).isPresent()) {
            return;
        }
        User user = new User();
        user.setUsername(email);
        user.setEmail(email);
        user.setPassword("test-hash");
        user.setRole(UserRole.REGULAR);
        users.saveAndFlush(user);
    }

    private long count(String table) {
        return ((Number) entityManager
                .createNativeQuery("SELECT count(*) FROM \"" + table + "\"").getSingleResult()).longValue();
    }

    private List<String> tables() {
        @SuppressWarnings("unchecked")
        List<Object> rows = entityManager.createNativeQuery("""
                SELECT table_name FROM information_schema.tables WHERE lower(table_schema) = 'public'
                """).getResultList();
        return rows.stream().map(String::valueOf).toList();
    }

    private List<String> populatedTables() {
        return tables().stream().filter(table -> {
            try {
                return count(table) > 0;
            } catch (RuntimeException e) {
                return false;
            }
        }).toList();
    }
}