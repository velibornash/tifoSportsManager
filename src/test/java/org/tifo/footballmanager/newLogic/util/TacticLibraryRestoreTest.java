package org.tifo.footballmanager.newLogic.util;

import org.tifo.footballmanager.BaseTest;
import org.tifo.footballmanager.newLogic.model.Country;
import org.tifo.footballmanager.newLogic.model.CountryState;
import org.tifo.footballmanager.newLogic.model.CompetitionTeamType;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.model.tactics.Tactic;
import org.tifo.footballmanager.newLogic.repository.CountryRepository;
import org.tifo.footballmanager.newLogic.repository.TeamRepository;
import org.tifo.footballmanager.newLogic.repository.TacticRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A reset must not delete the tactic library.
 *
 * <p>The owner asked for a reset that keeps his tactical-editor data and his two accounts. The reset
 * already kept the legacy profile table and the legacy {@code tactics} table, and it deleted the
 * {@code tactic} table — the one the multi-tactic library writes to, and the one holding the renamed
 * default and the copy he had just made.
 *
 * <p>These tests go through the snapshot and the restore, not around them, because the defect was
 * exactly that the snapshot did not mention the library at all.
 */
class TacticLibraryRestoreTest extends BaseTest {

    @Autowired private DatabaseInitializer initializer;
    @Autowired private TacticRepository tactics;
    @Autowired private TeamRepository teams;
    @Autowired private CountryRepository countries;

    @Test
    @Transactional
    @DisplayName("both of a club's tactics come back from a snapshot")
    void theLibraryIsRestored() {
        String clubName = aClub("Library Restorable").getName();
        Team club = teams.findByName(clubName).orElseThrow();

        // What the owner's own library looked like at the moment he asked for the reset.
        saveTactic(club, "Default 4-4-2", true, "{\"drawn\":\"by hand\"}");
        saveTactic(club, "Default 4-4-2 copy", false, null);

        initializer.restoreTacticLibrary(
                initializer.snapshotTacticLibraryForTest(clubName), tactics, teams);

        List<Tactic> restored = tactics.findByTeamIdOrderByIdAsc(club.getId());
        assertEquals(List.of("Default 4-4-2", "Default 4-4-2 copy"),
                restored.stream().map(Tactic::getName).toList(),
                "the snapshot did not carry the whole library, so a reset would have kept one tactic "
                        + "and lost the other");
    }

    @Test
    @Transactional
    @DisplayName("the rules the owner drew survive the reset")
    void theRulesSurvive() {
        String clubName = aClub("Library Rules").getName();
        Team club = teams.findByName(clubName).orElseThrow();
        String drawn = "[{\"slotKey\":\"GK\",\"ballStateKey\":\"CELL_0_0\","
                + "\"possessionContext\":\"WE_HAVE_BALL\",\"targetCellKey\":\"CELL_0_3\"}]";
        saveTactic(club, "Default 4-4-2", true, drawn);

        // The snapshot is taken first and the table emptied after it, which is the order the reset
        // uses: snapshot, truncate, re-init, restore.
        var snapshot = initializer.snapshotTacticLibraryForTest(clubName);
        tactics.deleteAll();
        initializer.restoreTacticLibrary(snapshot, tactics, teams);

        Tactic restored = tactics.findByTeamIdOrderByIdAsc(club.getId()).get(0);
        assertEquals(drawn, restored.getRulesJson(),
                "the restored tactic came back without its rules, so the editor would open a blank pitch");
    }

    @Test
    @Transactional
    @DisplayName("the default survives, and there is still exactly one of them")
    void theDefaultSurvives() {
        String clubName = aClub("Library Default").getName();
        Team club = teams.findByName(clubName).orElseThrow();
        saveTactic(club, "Default 4-4-2", true, null);
        saveTactic(club, "Default 4-4-2 copy", false, null);

        var snapshot = initializer.snapshotTacticLibraryForTest(clubName);
        tactics.deleteAll();
        initializer.restoreTacticLibrary(snapshot, tactics, teams);

        List<Tactic> restored = tactics.findByTeamIdOrderByIdAsc(club.getId());
        assertEquals(1, restored.stream().filter(Tactic::isDefaultTactic).count(),
                "a library with no default leaves the editor with nothing to open");
        assertEquals("Default 4-4-2", restored.stream().filter(Tactic::isDefaultTactic).findFirst()
                        .orElseThrow().getName(),
                "the copy came back as the default, so the owner's renamed default was displaced");
    }

    @Test
    @Transactional
    @DisplayName("a tactic the re-init already seeded is not inserted a second time")
    void anExistingTacticIsNotDuplicated() {
        String clubName = aClub("Library Seeded").getName();
        Team club = teams.findByName(clubName).orElseThrow();
        saveTactic(club, "4-4-2", true, null);

        // The unique constraint on (team_id, name) turns a second insert into a failed reset.
        assertTrue(tactics.findByTeamIdAndName(club.getId(), "4-4-2").isPresent(),
                "precondition: the seeded tactic is there");

        initializer.restoreTacticLibrary(
                initializer.snapshotTacticLibraryForTest(clubName), tactics, teams);

        assertEquals(1, tactics.findByTeamIdOrderByIdAsc(club.getId()).size(),
                "the restore inserted a second tactic by the same name");
    }

    @Test
    @Transactional
    @DisplayName("a club the re-init did not rebuild is skipped, not invented")
    void aMissingClubIsSkipped() {
        Team club = aClub("Library Missing");
        saveTactic(club, "4-4-2", true, null);

        // A snapshot for a club the re-init did not rebuild: the owner's world has "FK Beograd" in old
        // backups and five clubs that start with FK. Which one was meant is his decision.
        var snapshot = initializer.snapshotTacticLibraryForTest("FK Beograd");

        tactics.deleteAll();
        initializer.restoreTacticLibrary(snapshot, tactics, teams);

        assertEquals(0, tactics.count(),
                "a restore for a club that does not exist invented tactics for some other club");
    }

    // --- helpers ----------------------------------------------------------

    /** A throwaway club, following TacticsProfileRestoreTest so the two agree on what one is. */
    private Team aClub(String label) {
        Country country = new Country();
        country.setName("ZZ Library " + label + " " + UUID.randomUUID());
        country.setIsoCode("R" + UUID.randomUUID().toString().substring(0, 2).toUpperCase());
        country.setState(CountryState.SIMULATED);
        country = countries.save(country);

        Team team = new Team();
        team.setName("ZZ Library club " + label + " " + UUID.randomUUID());
        team.setCountry(country);
        team.setType(CompetitionTeamType.CLUB);
        return teams.save(team);
    }

    private void saveTactic(Team club, String name, boolean isDefault, String rulesJson) {
        Tactic tactic = new Tactic();
        tactic.setTeam(club);
        tactic.setName(name);
        tactic.setFormation("4-4-2");
        tactic.setStyle(isDefault ? "BALANCED" : "Balanced");
        tactic.setRulesJson(rulesJson);
        tactic.setVersion(5L);
        tactic.setDefaultTactic(isDefault);
        tactics.save(tactic);
    }
}
