package org.tifo.footballmanager.newLogic.service;

import org.tifo.footballmanager.BaseTest;
import org.tifo.footballmanager.ProductionSource;
import org.tifo.footballmanager.newLogic.model.CountryNamePoolEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The name pool's table must not keep a column the code no longer writes.
 *
 * <p>Owner, 2026-10-22, from the live log of his own Reset and Initialize on the server:
 *
 * <pre>
 * ERROR: null value in column "value" of relation "country_name_pool" violates not-null constraint
 *   Detail: Failing row contains (2, 0, null, 1, Crvena zvezda).
 *   Detail: Failing row contains (3, 0, null, 25, Manchester City).
 * </pre>
 *
 * <p>The column was renamed: the entity writes {@code name_value} now, and the table carries both.
 * {@code ddl-auto=update} <b>adds</b> a column and never drops one, so the old {@code value} stayed -
 * NOT NULL, with no default - and every insert into the pool failed on it.
 *
 * <p>So the name pools were never filled. The seeders read the table, found nothing, and fell back to
 * the files, which is why the world had the right club and city names while the admin panel said
 * <em>"No names set for this country yet"</em> for every country in the world - and why the owner's
 * own edits could not be saved either: the same column refused the save.
 *
 * <p><b>What this test can and cannot do.</b> It adds the column to the test database, runs the
 * repair and watches the column go - so it fails when the repair is missing or does the wrong thing.
 * It cannot see the server's schema, which is the whole point of the repair: the server is the only
 * place the legacy column is, and the repair has to run there.
 */
class CountryNamePoolLegacyColumnTest extends BaseTest {

    @Autowired ResetService resetService;

    @PersistenceContext EntityManager entityManager;

    @Test
    @Transactional
    @DisplayName("the legacy column is dropped, so the name pools can be written again")
    void theLegacyColumnIsDropped() {
        // The column as the server still carries it: NOT NULL, with no default, and with a unique
        // constraint of its own. Added here because the test database is built from the entities and
        // has never had it.
        entityManager.createNativeQuery("ALTER TABLE country_name_pool ADD COLUMN \"value\" varchar(120) "
                + "NOT NULL").executeUpdate();
        assertTrue(columnExists(), "the test did not reproduce the server's shape");

        resetService.dropLegacyCountryNamePoolValueColumn();

        assertFalse(columnExists(),
                "the legacy column is still on the table, and every insert into the name pool will "
                        + "fail on it - so the pools stay empty and the owner's names cannot be saved");
    }

    @Test
    @DisplayName("the repair runs on the way up, before anything seeds the pools")
    void theRepairRunsBeforeThePoolsAreSeeded() throws IOException {
        // The repair is on the boot path, beside the other two schema repairs, so it has already run by
        // the time the Initialize seeds the pools. A repair nobody calls is a repair that does not
        // happen - which is what the two beside it were written for.
        String source = Files.readString(ProductionSource.file(ResetService.class), StandardCharsets.UTF_8);
        assertTrue(source.contains("dropLegacyCountryNamePoolValueColumn"),
                "the repair is not in the reset service any more");

        String initializer = Files.readString(
                ProductionSource.file(org.tifo.footballmanager.newLogic.util.DatabaseInitializer.class),
                StandardCharsets.UTF_8);
        int startup = initializer.indexOf("sanitizeLegacySchemaOnStartup");
        assertTrue(startup > 0, "the boot-time schema repair is gone from the initializer");
        assertTrue(initializer.contains("resetService.dropLegacyCountryNamePoolValueColumn()"),
                "the boot path does not repair the name pool's table, so the pools are seeded into a "
                        + "table that refuses the insert");
    }

    @Test
    @DisplayName("the entity writes the column the table has, and not the one it does not")
    void theEntityWritesTheColumnTheTableHas() throws IOException {
        // The column the code writes. The legacy one is gone from the entity, and that is why the
        // server's column had to be dropped rather than filled.
        String source = Files.readString(ProductionSource.file(CountryNamePoolEntry.class),
                StandardCharsets.UTF_8);
        assertTrue(source.contains("name_value"),
                "the entity no longer writes name_value, so the table's column is the wrong name");
        assertFalse(source.contains("\"value\""),
                "the entity writes a column called value again, and the name is a reserved word in "
                        + "both databases - the first read of this table failed on it");
    }

    /** Whether the table still carries the legacy column. */
    private boolean columnExists() {
        Object count = entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME) = "
                        + "'COUNTRY_NAME_POOL' AND UPPER(COLUMN_NAME) = 'VALUE'").getSingleResult();
        return ((Number) count).intValue() > 0;
    }
}
