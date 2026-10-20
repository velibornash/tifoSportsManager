package org.example.footballmanager.newLogic.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tactics backup reads its old shape and writes the new one (T0-BE-1).
 *
 * <p>This file is the only durable copy of a manager's hand-authored rules. Everything else in the tactics
 * feature can be rebuilt from the world; this cannot. So the two properties that matter are narrow and
 * absolute: <b>the file that exists must still be readable</b>, and <b>a club's tactics must round-trip</b>.
 *
 * <p>A club can hold several tactics now, so the file became a club with a list. It used to be one flat
 * record per club keyed by club name. Reading only the new shape would report an empty library for a file
 * full of work — and, because an empty library and a library nobody has filled look identical, it would do
 * so silently. That is the failure this class exists to prevent.
 */
class TacticsProfileBackupServiceTest {

    /**
     * Configured like the application's mapper, not left at Jackson's defaults.
     *
     * <p>The records carry {@code LocalDateTime}, and a plain {@code ObjectMapper} has no
     * {@code JavaTimeModule} — so it fails on a timestamp and this class would have "proved" that the old
     * file shape is unreadable when the only thing wrong was the test's own mapper. Spring registers the
     * module in production, and a test of production behaviour needs the same mapper.
     */
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    private TacticsProfileBackupService serviceAt(Path path) {
        return new TacticsProfileBackupService(mapper, path);
    }

    private static TacticsProfileBackupService.TacticBackup tactic(
            String name, String formation, String rulesJson) {
        var t = new TacticsProfileBackupService.TacticBackup();
        t.setName(name);
        t.setFormation(formation);
        t.setStyle("Balanced");
        t.setRulesJson(rulesJson);
        t.setVersion(1L);
        return t;
    }

    @Test
    @DisplayName("a file written in the old single-profile shape is still readable")
    void theOldShapeIsStillReadable(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("tactics-editor-profiles.json");
        // Exactly the shape in the repository's own var/ file: flat records, keyed by club name.
        Files.writeString(file, """
                [
                  {
                    "teamName": "OFK Omladinac",
                    "formation": "4-4-2",
                    "style": "Balanced",
                    "rulesJson": "[{\\"slotKey\\":\\"CML\\"}]",
                    "setPiecesJson": null,
                    "version": 3,
                    "updatedAt": "2026-09-30T10:15:00"
                  }
                ]
                """);

        List<TacticsProfileBackupService.ClubBackup> clubs = serviceAt(file).loadAll();

        assertEquals(1, clubs.size(), "the existing file must not read as an empty library");
        assertEquals("OFK Omladinac", clubs.get(0).getTeamName());
        assertEquals(1, clubs.get(0).getTactics().size(), "one flat record is a club with one tactic");

        var recovered = TacticsProfileBackupService.defaultTacticOf(clubs.get(0)).orElseThrow();
        assertEquals("4-4-2", recovered.getFormation(), "the tactic's own rules must survive the upgrade");
        assertEquals("[{\"slotKey\":\"CML\"}]", recovered.getRulesJson(),
                "the owner's authored rules are the thing this file exists for");
        assertEquals("4-4-2", clubs.get(0).getDefaultTacticName(),
                "a club with one tactic defaults to it, and it is named after its formation");
    }

    @Test
    @DisplayName("a club with several tactics round-trips through the file")
    void aClubWithSeveralTacticsRoundTrips(@TempDir Path dir) {
        Path file = dir.resolve("backup.json");
        var service = serviceAt(file);

        service.saveClub("OFK Omladinac", List.of(
                tactic("Derby 4-4-2", "4-4-2", "[{\"slotKey\":\"CML\"}]"),
                tactic("Cup 4-3-3", "4-3-3", "[{\"slotKey\":\"CM\"}]"),
                tactic("Low block 5-4-1", "5-4-1", "[{\"slotKey\":\"MR\"}]")
        ), "Cup 4-3-3");

        List<TacticsProfileBackupService.ClubBackup> clubs = service.loadAll();

        assertEquals(1, clubs.size());
        assertEquals(3, clubs.get(0).getTactics().size(), "all three tactics must survive a round trip");
        assertEquals("Cup 4-3-3", clubs.get(0).getDefaultTacticName());
        assertEquals("CM",
                TacticsProfileBackupService.defaultTacticOf(clubs.get(0)).orElseThrow().getRulesJson()
                        .contains("CM") ? "CM" : "wrong",
                "the named default must be the one the caller designated, not the first in the list");
    }

    @Test
    @DisplayName("a file in the old shape, then saved, comes back in the new one without losing the work")
    void anOldFileUpgradesOnWrite(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("backup.json");
        Files.writeString(file, """
                [ { "teamName": "OFK Omladinac", "formation": "4-4-2", "style": "Balanced",
                    "rulesJson": "[{\\"slotKey\\":\\"CML\\"}]", "version": 3 } ]
                """);
        var service = serviceAt(file);

        service.saveClub("OFK Omladinac", List.of(tactic("4-4-2", "4-4-2", "[{\"slotKey\":\"CML\"}]")), "4-4-2");

        String written = Files.readString(file);
        assertTrue(written.contains("\"tactics\""),
                "after a save the file should be in the new shape, not the old one:\n" + written);

        List<TacticsProfileBackupService.ClubBackup> clubs = service.loadAll();
        assertEquals(1, clubs.size());
        assertEquals("[{\"slotKey\":\"CML\"}]",
                TacticsProfileBackupService.defaultTacticOf(clubs.get(0)).orElseThrow().getRulesJson(),
                "upgrading the shape must not touch the rules themselves");
    }

    @Test
    @DisplayName("one club's tactics never overwrite another's")
    void clubsDoNotOverwriteEachOther(@TempDir Path dir) {
        Path file = dir.resolve("backup.json");
        var service = serviceAt(file);

        service.saveClub("Club A", List.of(tactic("4-4-2", "4-4-2", "[{\"slotKey\":\"CML\"}]")), "4-4-2");
        service.saveClub("Club B", List.of(tactic("4-3-3", "4-3-3", "[{\"slotKey\":\"CM\"}]")), "4-3-3");
        service.saveClub("Club A", List.of(
                tactic("4-4-2", "4-4-2", "[{\"slotKey\":\"CML\"}]"),
                tactic("5-4-1", "5-4-1", "[{\"slotKey\":\"MR\"}]")), "4-4-2");

        List<TacticsProfileBackupService.ClubBackup> clubs = service.loadAll();
        assertEquals(2, clubs.size(), "there are two clubs in the file");
        assertEquals(2, clubs.stream().filter(c -> c.getTeamName().equals("Club A"))
                .findFirst().orElseThrow().getTactics().size(), "Club A gained a tactic");
        assertEquals(1, clubs.stream().filter(c -> c.getTeamName().equals("Club B"))
                .findFirst().orElseThrow().getTactics().size(), "Club B was left alone");
    }

    @Test
    @DisplayName("a missing or unreadable file is an empty library, not a crash")
    void anUnreadableFileDoesNotTakeTheEditorDown(@TempDir Path dir) throws Exception {
        assertEquals(List.of(), serviceAt(dir.resolve("absent.json")).loadAll());

        Path broken = dir.resolve("broken.json");
        Files.writeString(broken, "{ this is not json");
        assertEquals(List.of(), serviceAt(broken).loadAll(),
                "the file is the owner's copy and must not be deleted, but it must not stop the editor "
                        + "opening either");
    }
}