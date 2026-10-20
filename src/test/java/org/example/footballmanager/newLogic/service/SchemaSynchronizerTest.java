package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.BaseTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A database that has fallen behind the code can be brought up to date without a restart (owner, 2026-10-18).
 *
 * <p>Two admin actions leave the schema older than the entities while the application keeps running, and
 * {@code ddl-auto=update} only runs at startup: <b>restoring a backup</b> replaces the whole database with a
 * dump that may predate a column, and <b>resetting</b> then fails on the first query that touches a table the
 * code has moved on from. Both were reported as failures with a message about a missing column and nothing to
 * suggest a restart would fix it.
 *
 * <p><b>The test drops a real column and asks for it back.</b> That is the whole claim: not that the call
 * runs, but that the database changes. The columns are the two that were actually reported —
 * {@code stadium.sponsor_name} and {@code game_clock.anchor_date} — so this fails for the same reason the
 * owner's button did if it ever regresses.
 */
class SchemaSynchronizerTest extends BaseTest {

    @Autowired private SchemaSynchronizer synchronizer;
    @Autowired private DataSource dataSource;

    @Test
    @DisplayName("a column the code needs, and the database does not have, is put back")
    void aMissingColumnIsPutBack() throws SQLException {
        assertTrue(columnExists("stadium", "sponsor_name"), "the fixture assumes the column is there to begin with");
        assertTrue(columnExists("game_clock", "anchor_date"), "and this one");

        // Simulate the drift exactly: a dump restored without the column, or a database the code moved on
        // from. Dropping it is what a restore of an older dump does.
        dropColumn("stadium", "sponsor_name");
        dropColumn("game_clock", "anchor_date");
        assertFalse(columnExists("stadium", "sponsor_name"), "the column has to be gone for this to mean anything");
        assertFalse(columnExists("game_clock", "anchor_date"));

        synchronizer.synchronize();

        assertTrue(columnExists("stadium", "sponsor_name"),
                "the synchroniser must put stadium.sponsor_name back — this is what 'Database operation failed: "
                        + "column \"sponsor_name\" of relation \"stadium\" does not exist' was");
        assertTrue(columnExists("game_clock", "anchor_date"),
                "and game_clock.anchor_date — this is what 'column gc1_0.anchor_date does not exist' was");
    }

    @Test
    @DisplayName("running it on a database that is already current changes nothing")
    void itIsIdempotent() throws SQLException {
        synchronizer.synchronize();
        synchronizer.synchronize();
        assertTrue(columnExists("stadium", "sponsor_name"),
                "a second run must not undo the first");
        assertTrue(columnExists("game_clock", "anchor_date"));
    }

    private boolean columnExists(String table, String column) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select count(*) from information_schema.columns "
                             + "where lower(table_name) = ? and lower(column_name) = ?")) {
            statement.setString(1, table.toLowerCase());
            statement.setString(2, column.toLowerCase());
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1) > 0;
            }
        }
    }

    private void dropColumn(String table, String column) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("alter table " + table + " drop column " + column);
        }
    }
}
