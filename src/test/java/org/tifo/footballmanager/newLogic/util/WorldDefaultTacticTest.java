package org.tifo.footballmanager.newLogic.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.tifo.footballmanager.newLogic.model.Country;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.model.tactics.TeamTacticsProfile;
import org.tifo.footballmanager.newLogic.repository.TeamRepository;
import org.tifo.footballmanager.newLogic.service.TacticLibraryService;
import org.tifo.footballmanager.newLogic.service.TacticsProfileBackupService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * The world's default tactic survives a reset, and the rebuilt world is built with it.
 *
 * <p>Owner, 2026-10-20: <i>"proveri, ono za export default taktike definitivno ne radi.. kad ja snimim
 * taktiku kao velja/omladinac pa iz admina uradim export idefault tactic iz admin i give every club a
 * default tactic.. kada uradim reset db i init SVI klubovi treba da imaju tu jednu taktiku"</i>
 *
 * <p>Two things were broken, and both are checked here.
 *
 * <ol>
 *   <li><b>The designation did not survive a reset.</b> It lived on a table the reset clears, and the
 *       backup file that does survive carried no flag - so after every reset the owner had a saved
 *       tactic and no world default.</li>
 *   <li><b>A rebuilt world had no tactics at all.</b> The world default was a button, and the build
 *       never read it, so every club built after a reset came back with nothing.</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
class WorldDefaultTacticTest {

    @Autowired private PyramidBuilder pyramids;
    @Autowired private TacticLibraryService tactics;
    @Autowired private TacticsProfileBackupService backup;
    @Autowired private TeamRepository teams;
    @Autowired private org.tifo.footballmanager.newLogic.repository.TeamTacticsProfileRepository profiles;
    @Autowired private org.tifo.footballmanager.newLogic.repository.CountryRepository countries;

    @Test
    @DisplayName("the designation travels into the backup file, which is the half that survives a reset")
    void theDesignationTravelsIntoTheFile() throws Exception {
        // A file that starts empty, so the check is about what this run wrote and not about what an
        // earlier one left behind.
        java.nio.file.Path path = java.nio.file.Path.of("target/test-tactics-editor-profiles.json");
        java.nio.file.Files.deleteIfExists(path);

        Team club = aClubWithATactic("OFK Omladinac");
        tactics.markProfileAsWorldDefault(club.getId());

        Optional<TacticsProfileBackupService.ClubBackup> inTheFile = backup.loadAll().stream()
                .filter(entry -> "OFK Omladinac".equalsIgnoreCase(entry.getTeamName()))
                .findFirst();

        assertTrue(inTheFile.isPresent(),
                "the club's tactics are not in the backup file at all: " + backup.loadAll().size() + " club(s) in it");
        assertTrue(inTheFile.get().isWorldDefault(),
                "the backup file does not carry the designation, so a reset would lose it. The file is "
                        + "the only half of the world default that survives a reset.");
    }

    @Test
    @DisplayName("a country built after the designation starts with that tactic on every club")
    void aBuiltCountryStartsWithTheWorldDefaultTactic() {
        Team club = aClubWithATactic("OFK Omladinac");
        tactics.markProfileAsWorldDefault(club.getId());

        Country country = new Country();
        country.setName("World Default Testland");
        country.setIsoCode("WDT");
        country.setState(org.tifo.footballmanager.newLogic.model.CountryState.SIMULATED);
        country = countries.save(country);

        // The static pyramid: the clubs without their players, because a test that builds half a
        // million players to look at ten tactics is a test nobody runs.
        pyramids.buildStatic(country, 1);

        List<Team> built = teams.findByCountryId(country.getId());
        assertFalse(built.isEmpty(), "the country was built with no clubs at all");
        for (Team team : built) {
            assertTrue(tactics.view(team.getId()).stream()
                            .anyMatch(view -> view.isDefault()),
                    "the club " + team.getName() + " was built with no default tactic");
        }
    }

    /** A club with one saved tactic, which is what the editor leaves behind. */
    private Team aClubWithATactic(String name) {
        Team team = new Team();
        team.setName(name);
        Team saved = teams.save(team);

        TeamTacticsProfile profile = new TeamTacticsProfile();
        profile.setTeam(saved);
        profile.setFormation("4-3-3");
        profile.setStyle("ATTACKING");
        profile.setRulesJson("[{\"slotKey\":\"GK\"}]");
        profile.setSetPiecesJson("[]");
        profile.setVersion(1L);
        profile.setUpdatedAt(java.time.LocalDateTime.now());
        profiles.save(profile);
        return saved;
    }
}
