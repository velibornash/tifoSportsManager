package org.example.commonmanager.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dump a database, change it, restore it, and get the change back (owner, 2026-10-06).
 *
 * <p><b>This is the only test that proves the feature works.</b> The name checks in
 * {@link DatabaseBackupServiceTest} are about not destroying the wrong thing; this one is about whether
 * {@code pg_dump} and {@code pg_restore} actually put the world back, which is the whole request.
 *
 * <p><b>It runs against its own database and never the owner's.</b> The scratch name ends in
 * {@value #SCRATCH_MARKER} and the test refuses to start without it — a destructive test whose target
 * is a constant in a test file is one edit away from dropping the owner's world, and the check that
 * stops it has to live in the same file as the edit.
 *
 * <p>Skipped, not failed, where there is no PostgreSQL to talk to, because the rest of the suite runs
 * on H2 and a machine without a database should not report a broken backup.
 */
class DatabaseBackupRoundTripTest {

    private static final String HOST = "localhost";
    private static final int PORT = 5432;
    private static final String USER = "postgres";
    private static final String PASSWORD = "stojke";

    private static final String SCRATCH_MARKER = "_roundtrip_scratch";
    private static final String SCRATCH_DB = "sokker" + SCRATCH_MARKER;
    private static final String MAINTENANCE_DB = "postgres";
    private static final String JDBC = "jdbc:postgresql://" + HOST + ":" + PORT + "/" + SCRATCH_DB;

    @TempDir
    Path backupDirectory;

    private DatabaseBackupService service;

    @BeforeEach
    void createScratchDatabase() {
        Assumptions.assumeTrue(postgresAnswers(), "no PostgreSQL on " + HOST + ":" + PORT + ", skipping the backup round trip");
        Assumptions.assumeTrue(SCRATCH_DB.endsWith(SCRATCH_MARKER),
                "refusing to run: the scratch database name does not carry its safety marker");

        dropScratchDatabase();
        runTool("createdb", SCRATCH_DB);
        service = new DatabaseBackupService(JDBC, USER, PASSWORD, backupDirectory.toString(), "");
    }

    @AfterEach
    void dropScratchDatabaseQuietly() {
        if (postgresAnswers()) {
            dropScratchDatabase();
        }
    }

    @Test
    @DisplayName("a dump taken before a change puts that change back")
    void aDumpPutsTheWorldBack() throws Exception {
        createTableWithMarker("a fresh world");
        assertEquals(0, dumpCount(), "nothing dumped yet");

        // The world moves on, which is the only reason to want a backup.
        updateMarker("a season played");
        assertEquals("a season played", readMarker());

        DatabaseBackupService.Backup backup = service.create();
        assertEquals(1, dumpCount(), "the dump is a file in the backup directory");
        assertTrue(backup.bytes() > 0, "a dump of a database is not an empty file: " + backup.bytes() + " bytes");
        assertTrue(Files.isRegularFile(backupDirectory.resolve(backup.name())), backup.name() + " is on disk");

        updateMarker("changed again, long after the dump");
        assertEquals("changed again, long after the dump", readMarker());

        DatabaseBackupService.Restore restored = service.restore(backup.name());

        assertEquals("a season played", readMarker(),
                "the restore put the database back to the moment the dump was taken");
        assertTrue(restored.objects() > 0, "the archive listed real objects: " + restored.objects());
        assertTrue(restored.note().contains("Restart"), "the owner is told the application needs a restart");
    }

    @Test
    @DisplayName("the restore brings back the schema, not only the rows")
    void theDumpContainsTheSchema() throws Exception {
        createTableWithMarker("a value");
        DatabaseBackupService.Backup backup = service.create();

        // Drop the table entirely. If the dump were empty or partial the restore would leave it
        // missing, and the next read would fail rather than quietly returning something plausible.
        execute("drop table roundtrip_marker");

        service.restore(backup.name());

        assertEquals("a value", readMarker(), "the table and its row came back");
    }

    @Test
    @DisplayName("restoring twice in a row lands in the same place as restoring once")
    void restoringTwiceIsTheSameAsOnce() throws Exception {
        createTableWithMarker("original");
        DatabaseBackupService.Backup backup = service.create();
        updateMarker("scratch value");

        service.restore(backup.name());
        service.restore(backup.name());

        assertEquals("original", readMarker(), "a repeated restore is not a second, different restore");
        assertEquals(1, dumpCount(), "restoring does not consume or copy the archive");
    }

    @Test
    @DisplayName("a file that is not a dump is refused, and the database is untouched")
    void aCorruptArchiveIsRefusedBeforeAnythingIsDropped() throws Exception {
        createTableWithMarker("safe");
        Files.writeString(backupDirectory.resolve("2026-10-06-23-15-04.dump"), "this is not an archive");

        RuntimeException refusal =
                assertThrows(RuntimeException.class, () -> service.restore("2026-10-06-23-15-04.dump"));

        assertNotNull(refusal.getMessage());
        assertEquals("safe", readMarker(), "a bad archive left the database exactly as it was");
    }

    // ---------- the scratch database ----------

    private void createTableWithMarker(String value) throws Exception {
        execute("create table roundtrip_marker (note varchar(64))");
        execute("insert into roundtrip_marker values ('" + value + "')");
    }

    private void updateMarker(String value) throws Exception {
        execute("update roundtrip_marker set note = '" + value + "'");
    }

    private String readMarker() throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC, USER, PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select note from roundtrip_marker")) {
            assertTrue(rows.next(), "roundtrip_marker has a row");
            return rows.getString(1);
        }
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void dropScratchDatabase() {
        runTool("dropdb", "--if-exists", "--force", SCRATCH_DB);
    }

    private long dumpCount() throws IOException {
        try (var files = Files.list(backupDirectory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".dump")).count();
        }
    }

    /**
     * Runs {@code createdb} or {@code dropdb} against the maintenance database.
     *
     * <p>Neither tool takes a {@code --dbname}: they take the database to act <em>on</em> as a
     * positional argument and connect to the maintenance database instead, because a database cannot
     * create or drop itself. So the scratch world is made and destroyed from
     * {@value #MAINTENANCE_DB} rather than from {@value #SCRATCH_DB}.
     */
    private void runTool(String tool, String... trailing) {
        List<String> command = new ArrayList<>(List.of(
                tool,
                "--host=" + HOST,
                "--port=" + PORT,
                "--username=" + USER,
                "--maintenance-db=" + MAINTENANCE_DB));
        command.addAll(List.of(trailing));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
        builder.environment().put("PGPASSWORD", PASSWORD);
        try {
            Process process = builder.start();
            String output;
            try (InputStream stream = process.getInputStream()) {
                output = new String(stream.readAllBytes());
            }
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException(tool + " timed out");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException(tool + " failed: " + output.strip());
            }
        } catch (IOException e) {
            throw new IllegalStateException(tool + " could not be run: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running " + tool + ".", e);
        }
    }

    /** Whether there is a PostgreSQL to talk to at all, asked of the maintenance database. */
    private boolean postgresAnswers() {
        try {
            ProcessBuilder builder = new ProcessBuilder(List.of(
                    "psql", "--no-psqlrc", "--quiet", "--tuples-only",
                    "--host=" + HOST, "--port=" + PORT, "--username=" + USER, "--dbname=" + MAINTENANCE_DB,
                    "--command", "select 1"));
            builder.redirectErrorStream(true);
            builder.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
            builder.environment().put("PGPASSWORD", PASSWORD);
            Process process = builder.start();
            String output;
            try (InputStream stream = process.getInputStream()) {
                output = new String(stream.readAllBytes());
            }
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0 && output.contains("1");
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}