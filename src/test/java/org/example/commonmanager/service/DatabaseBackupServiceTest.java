package org.example.commonmanager.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A backup is named {@code yyyy-mm-dd-HH-mm-ss.dump} and a restore only ever reads a file of that name
 * inside the backup directory (owner, 2026-10-06).
 *
 * <p><b>No PostgreSQL here on purpose.</b> These are the checks that decide what a request is allowed to
 * touch, and they must hold on a machine with no database and no {@code pg_dump} — otherwise the guard
 * that protects a destructive operation is only tested where the operation would have been harmless
 * anyway. The round trip itself is in {@code DatabaseBackupRoundTripTest}, against a scratch database.
 *
 * <p>The service is constructed directly rather than injected, because its constructor arguments are
 * the connection details and a directory, and the point is to control both.
 */
class DatabaseBackupServiceTest {

    private static final String URL = "jdbc:postgresql://localhost:5432/sokker_db";

    private DatabaseBackupService serviceIn(Path directory) {
        return new DatabaseBackupService(URL, "postgres", "stojke", directory.toString(), "");
    }

    @Test
    @DisplayName("a name that is not a timestamp is refused, whatever else it contains")
    void aNameThatIsNotATimestampIsRefused(@TempDir Path directory) {
        DatabaseBackupService service = serviceIn(directory);

        for (String name : List.of(
                "latest.dump",
                "../../etc/passwd",
                "2026-10-06-23-15-04.sql",
                "2026-10-06-23-15-04.dump; rm -rf /",
                "",
                " 2026-10-06-23-15-04.dump")) {
            assertThrows(IllegalArgumentException.class, () -> service.restore(name),
                    "'" + name + "' must not be restorable");
        }
    }

    @Test
    @DisplayName("a traversal that ends in a valid-looking name is still refused")
    void aTraversalThatLooksValidIsRefused(@TempDir Path directory) throws IOException {
        DatabaseBackupService service = serviceIn(directory);
        Files.createDirectories(directory.getParent());
        Files.writeString(directory.getParent().resolve("2026-10-06-23-15-04.dump"), "not ours");

        assertThrows(IllegalArgumentException.class,
                () -> service.restore("../" + directory.getFileName() + "/2026-10-06-23-15-04.dump"),
                "a name that climbs out of the directory is not a backup name");
    }

    @Test
    @DisplayName("a well-formed name that is not there is a refusal, not an empty restore")
    void aMissingBackupIsARefusal(@TempDir Path directory) {
        DatabaseBackupService service = serviceIn(directory);

        IllegalArgumentException refusal =
                assertThrows(IllegalArgumentException.class, () -> service.restore("2026-10-06-23-15-04.dump"));
        assertTrue(refusal.getMessage().contains("2026-10-06-23-15-04.dump"),
                "the refusal names the file asked for: " + refusal.getMessage());
    }

    @Test
    @DisplayName("the list shows dumps newest first and ignores everything that is not one")
    void theListIsNewestFirstAndOnlyDumps(@TempDir Path directory) throws IOException {
        DatabaseBackupService service = serviceIn(directory);
        Files.writeString(directory.resolve("2026-10-05-09-00-00.dump"), "a");
        Files.writeString(directory.resolve("2026-10-07-19-30-00.dump"), "c");
        Files.writeString(directory.resolve("2026-10-06-23-15-04.dump"), "b");
        Files.writeString(directory.resolve("notes.txt"), "not a backup");
        Files.createDirectory(directory.resolve("2026-10-06-23-15-05.dump"));

        List<DatabaseBackupService.Backup> backups = service.list();

        assertEquals(List.of("2026-10-07-19-30-00.dump", "2026-10-06-23-15-04.dump", "2026-10-05-09-00-00.dump"),
                backups.stream().map(DatabaseBackupService.Backup::name).toList(),
                "newest first, and a .txt or a directory is not a backup");
        assertEquals("2026-10-06 23:15:04", backups.get(1).createdAt(),
                "the timestamp in the name is what the screen shows");
        assertEquals(1L, backups.get(1).bytes());
    }

    @Test
    @DisplayName("an absent backup directory is an empty list, not a failure")
    void anAbsentDirectoryIsAnEmptyList(@TempDir Path directory) {
        DatabaseBackupService service = serviceIn(directory.resolve("never-created"));

        assertEquals(List.of(), service.list(),
                "no backups taken yet is a normal state for a server that has never been dumped");
    }

    @Test
    @DisplayName("the name is the owner's yyyy-mm-dd-HH-mm-ss, and it is a name this service will restore")
    void theNameIsTheOwnersFormat(@TempDir Path directory) {
        DatabaseBackupService service = serviceIn(directory);
        LocalDateTime moment = LocalDateTime.of(2026, 10, 6, 23, 15, 4);

        String name = service.nameFor(moment);

        assertEquals("2026-10-06-23-15-04.dump", name, "yyyy-mm-dd-HH-mm-ss, as the owner asked for it");
        assertEquals("2026-10-07-00-00-00.dump",
                service.nameFor(LocalDateTime.of(2026, 10, 6, 23, 59, 59).plusSeconds(1)),
                "midnight rolls the date over instead of running on into hour 25");

        // Same method the restore path validates against, so a name this writes is a name it accepts.
        // It gets as far as "no such file", which is a different refusal from a rejected name.
        IllegalArgumentException missing =
                assertThrows(IllegalArgumentException.class, () -> service.restore(name));
        assertTrue(missing.getMessage().contains("no backup"),
                "'" + name + "' passed validation and was looked for on disk: " + missing.getMessage());
    }
}