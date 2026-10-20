package org.example.footballmanager.newLogic.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.tactics.TeamTacticsProfile;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.repository.TeamTacticsProfileRepository;
import org.example.footballmanager.newLogic.service.TacticsProfileBackupEntry;
import org.example.footballmanager.newLogic.service.TacticsProfileBackupService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step 2: the tactical editor's backup file is the only durable copy of a club's work, and the reset
 * path now reads it back.
 *
 * <p><b>What it was.</b> The reset snapshotted {@code team_tactics_profile} — the table — and restored
 * from that snapshot. On a world where the table was already empty, which is exactly what the previous
 * reset leaves behind, the snapshot was empty too and the restore had nothing to give. Meanwhile
 * {@code TacticsProfileBackupService} had been writing {@code var/tactics-editor-profiles.json} on
 * every editor save since it existed, and its {@code loadAll()} had <b>zero callers</b>. The owner's
 * five profiles were in that file and nowhere else, so one Reset destroyed them permanently — and the
 * log said "Restored 0 tactics editor profiles after reset."
 *
 * <h2>Why the backup path is injected here</h2>
 *
 * <p>The production path is a constant, and pointing a test at it would mean reading and rewriting the
 * repository's own {@code var/} file to prove something about a method. It is injected instead, so this
 * test is hermetic and the real file is never touched.
 */
class TacticsProfileRestoreTest extends BaseTest {

    @Autowired private DatabaseInitializer initializer;
    @Autowired private TeamTacticsProfileRepository profiles;
    @Autowired private TeamRepository teams;
    @Autowired private CountryRepository countries;

    @Test
    @Transactional
    @DisplayName("a profile in the backup file is placed back onto its club")
    void aProfileInTheBackupIsRestored() throws Exception {
        String clubName = aClub("Restorable").getName();
        Path backup = aBackupFile(entry(clubName, "4-4-2"));

        restore(backup);

        TeamTacticsProfile restored = profiles.findByTeamId(idOf(clubName))
                .orElseThrow(() -> new AssertionError(
                        "the backup file holds a profile for " + clubName + " and it was not restored. The "
                                + "restore path reads the database snapshot and nothing else, so a world whose "
                                + "table was already empty silently lost every profile it had."));
        assertEquals("4-4-2", restored.getFormation());
        assertTrue(restored.getRulesJson().contains("WE_HAVE_BALL"),
                "the restored profile has no rules in it");
    }

    @Test
    @Transactional
    @DisplayName("a profile whose club name does not exist is left alone, not half-applied")
    void aProfileWithNoMatchingClubIsNotApplied() throws Exception {
        // The owner's situation exactly: "FK Beograd" is in the backup and the world holds OFK Beograd,
        // SK Beograd, TSK Beograd, GFK Dinamo Beograd and ŽFK Mlava Beograd 1901. Which one was meant is
        // the owner's decision, so the profile is reported and left in the file rather than guessed at.
        Path backup = aBackupFile(entry("FK Beograd", "4-3-3"));

        restore(backup);

        assertEquals(0, profiles.findAll().size(),
                "a profile was applied to a club that does not exist. Guessing between five Beograd clubs "
                        + "would put a manager's tactics on a team they do not run.");
    }

    @Test
    @Transactional
    @DisplayName("restoring onto a club that already has a profile updates it rather than failing")
    void restoringOverAnExistingProfileUpdatesIt() throws Exception {
        // What actually happens on a repair, and what the first version of this file got wrong: it
        // asserted a precedence rule between the table and the file, which cannot occur — the snapshot is
        // read BEFORE the reset, so the two sources are disjoint by construction. The real requirement is
        // that a restore over an existing row updates that row rather than tripping the unique
        // constraint, because the table has one profile per club.
        String clubName = aClub("Existing").getName();
        TeamTacticsProfile live = new TeamTacticsProfile();
        live.setTeam(teamByName(clubName));
        live.setFormation("4-4-2");
        live.setStyle("Before");
        live.setRulesJson(rulesJson());
        live.setVersion(1L);
        profiles.save(live);

        TacticsProfileBackupEntry newer = entry(clubName, "4-4-2");
        newer.setVersion(7L);
        Path backup = aBackupFile(newer);

        restore(backup);

        TeamTacticsProfile after = profiles.findByTeamId(idOf(clubName)).orElseThrow();
        assertEquals(1L, profiles.findAll().stream()
                        .filter(p -> clubName.equals(p.getTeam().getName())).count(),
                "a second profile row was created for a club that already had one. The table is one profile "
                        + "per club and a duplicate would fail the unique constraint on the next save.");
        assertEquals(7L, after.getVersion(),
                "the restore did not update the existing profile; it left version " + after.getVersion()
                        + " where the backup held 7");
    }

    // --- driving the restore ---

    /** Runs the restore with the backup service pointed at a temporary file. */
    private void restore(Path backup) throws Exception {
        TacticsProfileBackupService isolated =
                new TacticsProfileBackupService(new ObjectMapper(), backup);
        initializer.restoreTacticsProfiles(List.of(), isolated);
    }

    private Path aBackupFile(TacticsProfileBackupEntry... entries) throws Exception {
        Path file = Files.createTempFile("tactics-backup", ".json");
        file.toFile().deleteOnExit();
        Files.writeString(file, new ObjectMapper().writeValueAsString(List.of(entries)));
        return file;
    }

    private TacticsProfileBackupEntry entry(String teamName, String formation) {
        TacticsProfileBackupEntry e =
                new TacticsProfileBackupEntry();
        e.setTeamName(teamName);
        e.setFormation(formation);
        e.setStyle("Balanced");
        e.setRulesJson(rulesJson());
        e.setVersion(3L);
        return e;
    }

    private String rulesJson() {
        return "[{\"slotKey\":\"GK\",\"ballStateKey\":\"CELL_0_3\",\"possessionContext\":\"WE_HAVE_BALL\","
                + "\"targetCellKey\":\"CELL_0_3\"}]";
    }

    // --- clubs ---

    private Team aClub(String label) {
        Country country = new Country();
        country.setName("ZZ Restore " + label + " " + UUID.randomUUID());
        country.setIsoCode("R" + UUID.randomUUID().toString().substring(0, 2).toUpperCase());
        country.setState(CountryState.SIMULATED);
        country = countries.save(country);

        Team team = new Team();
        team.setName("ZZ Restore club " + label + " " + UUID.randomUUID());
        team.setCountry(country);
        team.setType(CompetitionTeamType.CLUB);
        return teams.save(team);
    }

    private Team teamByName(String name) {
        return teams.findByName(name).orElseThrow();
    }

    private Long idOf(String name) {
        return teamByName(name).getId();
    }
}
