package org.tifo.footballmanager.newLogic.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.tifo.footballmanager.newLogic.model.Country;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.model.tactics.Tactic;
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
 *
 * <p><b>2026-10-23: the source itself was wrong.</b> The owner transferred his tactic from Oracle,
 * named it "Osnovna" in OFK Omladinac's library and exported it as default from the admin panel —
 * and the designation took the club's <em>stale editor profile</em> (the old 4-3-3) instead, so the
 * whole chain — designated row, backup file, every seeder — handed out the profile while his club
 * played the library tactic. The designated world default is now sourced from the club's default
 * library tactic, and {@link #theDesignationComesFromTheLibraryTactic()} and
 * {@link #everyBuiltClubCarriesHisRules()} hold the two halves: what the designation captures, and
 * what a fresh country is built with.
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

    /** What the stale editor profile says — the content the designation must NOT hand out. */
    private static final String STALE_PROFILE_RULES =
            "[{\"slotKey\":\"GK\",\"targetCellKey\":\"STALE_EDITOR_PROFILE\"}]";

    /** What "Osnovna" says — the content every club must be built with. */
    private static final String OSNOVNA_RULES =
            "[{\"slotKey\":\"GK\",\"targetCellKey\":\"OSNOVNA_LIBRARY_TACTIC\"}]";

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

    @Test
    @DisplayName("the designated default is the club's CURRENT library tactic, not its stale editor profile")
    void theDesignationComesFromTheLibraryTactic() throws Exception {
        // A file that starts empty, so every assertion is about what this run wrote.
        java.nio.file.Path path = java.nio.file.Path.of("target/test-tactics-editor-profiles.json");
        java.nio.file.Files.deleteIfExists(path);

        Team club = aClubWithAStaleProfileAndHisCurrentTactic("OFK Omladinac Library");
        tactics.markProfileAsWorldDefault(club.getId());

        // The designated row — the thing every seeder reads — carries HIS rules, not the profile's.
        List<TeamTacticsProfile> flagged = profiles.findByWorldDefaultTrue();
        assertEquals(1, flagged.size(), "exactly one profile carries the designation; got " + flagged.size());
        TeamTacticsProfile designated = flagged.get(0);
        assertEquals("4-4-2", designated.getFormation(),
                "the designated default must be the club's current tactic (Osnovna, 4-4-2), not the "
                        + "editor profile as it was last saved (4-3-3)");
        assertEquals(OSNOVNA_RULES, designated.getRulesJson(),
                "the designated row carries the stale editor rules, so every club the world seeds "
                        + "would get the profile instead of the tactic the owner exported");

        // The file — what survives a reset — saves the tactic under the name he gave it.
        Optional<TacticsProfileBackupService.ClubBackup> inTheFile = backup.loadAll().stream()
                .filter(entry -> "OFK Omladinac Library".equalsIgnoreCase(entry.getTeamName()))
                .findFirst();
        assertTrue(inTheFile.isPresent(),
                "the club's tactics are not in the backup file at all: " + backup.loadAll().size() + " club(s) in it");
        TacticsProfileBackupService.ClubBackup saved = inTheFile.get();
        assertTrue(saved.isWorldDefault(),
                "the backup file does not carry the designation, so a reset would lose it. The file is "
                        + "the only half of the world default that survives a reset.");
        assertEquals("Osnovna", saved.getDefaultTacticName(),
                "the file must save the tactic under the name the owner gave it, not under its formation");
        assertEquals(OSNOVNA_RULES,
                TacticsProfileBackupService.defaultTacticOf(saved).orElseThrow().getRulesJson(),
                "the designated entry in the file must be Osnovna's rules");
        assertFalse(saved.getTactics().stream()
                        .anyMatch(tactic -> STALE_PROFILE_RULES.equals(tactic.getRulesJson())),
                "the stale editor profile must not have been written into the file as the club's tactics");
    }

    @Test
    @DisplayName("every club built after the designation carries HIS rules, byte for byte")
    void everyBuiltClubCarriesHisRules() {
        Team club = aClubWithAStaleProfileAndHisCurrentTactic("OFK Omladinac Seeded");
        tactics.markProfileAsWorldDefault(club.getId());

        Country country = new Country();
        country.setName("World Default Libraryland");
        country.setIsoCode("WDL");
        country.setState(org.tifo.footballmanager.newLogic.model.CountryState.SIMULATED);
        country = countries.save(country);

        pyramids.buildStatic(country, 1);

        List<Team> built = teams.findByCountryId(country.getId());
        assertFalse(built.isEmpty(), "the country was built with no clubs at all");
        for (Team team : built) {
            List<Tactic> library = tactics.forTeam(team.getId());
            assertTrue(library.stream().anyMatch(Tactic::isDefaultTactic),
                    "the club " + team.getName() + " was built with no default tactic");
            Tactic seeded = library.stream().filter(Tactic::isDefaultTactic).findFirst().orElseThrow();
            assertEquals(OSNOVNA_RULES, seeded.getRulesJson(),
                    "the club " + team.getName() + " was seeded with the stale editor profile instead of "
                            + "the owner's Osnovna rules");
            assertEquals("4-4-2", seeded.getFormation(),
                    "the club " + team.getName() + " was seeded with " + seeded.getFormation());
        }
    }

    /** His club as it stands: a stale 4-3-3 editor profile, and "Osnovna" in the library. */
    private Team aClubWithAStaleProfileAndHisCurrentTactic(String name) {
        Team team = aClubWithATactic(name);

        TeamTacticsProfile stale = profiles.findByTeamId(team.getId()).orElseThrow();
        stale.setFormation("4-3-3");
        stale.setRulesJson(STALE_PROFILE_RULES);
        profiles.save(stale);

        tactics.save(team.getId(), "Osnovna", "4-4-2", "ATTACKING", OSNOVNA_RULES, "[]", true);
        return team;
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
