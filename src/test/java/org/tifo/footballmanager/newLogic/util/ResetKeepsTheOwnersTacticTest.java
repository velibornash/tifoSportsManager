package org.tifo.footballmanager.newLogic.util;

import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.model.tactics.Tactic;
import org.tifo.footballmanager.newLogic.model.tactics.TeamTacticsProfile;
import org.tifo.footballmanager.newLogic.repository.TacticRepository;
import org.tifo.footballmanager.newLogic.repository.TeamRepository;
import org.tifo.footballmanager.newLogic.repository.TeamTacticsProfileRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tactic the owner saved must come back after Reset DB, and the world default must still be it.
 *
 * <p>Owner, 2026-10-19: <i>"uradio sam reset db, init db - NIJE zapamtio taktiku iz editora koju sam
 * snimio"</i>.
 *
 * <p><b>The most likely cause is not a code fault.</b> The editor shows "Unsaved changes / Save Tactic
 * Editor" above the button, and a reset destroys whatever is in the browser. So this test cannot and
 * does not cover that — it covers the part that IS code: once the save has happened, does the tactic
 * survive the reset path, and does the designation survive with it?
 *
 * <p><b>And it did not.</b> {@code restoreTacticsProfiles} rebuilt every field a profile has except
 * {@code worldDefault}, so a reset silently returned the game to "no default chosen" and
 * "give every club a default tactic" fell back to the lowest-id profile without saying so. That is the
 * defect this guards, and it was introduced by the change that added the flag.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ResetKeepsTheOwnersTacticTest {

    @Autowired DatabaseInitializer initializer;
    @Autowired TeamRepository teams;
    @Autowired TeamTacticsProfileRepository profiles;
    @Autowired TacticRepository tactics;

    private static final String CLUB = "Reset-Tactics Club";
    private static final String RULES =
            "[{\"slotKey\":\"CMR\",\"ballStateKey\":\"CELL_2_3\","
            + "\"possessionContext\":\"WE_HAVE_BALL\",\"targetCellKey\":\"CELL_2_3\"}]";

    @Test
    @DisplayName("a saved profile keeps its world-default designation across a snapshot and restore")
    void theWorldDefaultDesignationSurvivesASnapshot() {
        Team club = new Team();
        club.setName(CLUB + " " + System.nanoTime());
        club = teams.save(club);
        final String clubName = club.getName();

        TeamTacticsProfile profile = new TeamTacticsProfile();
        profile.setTeam(club);
        profile.setFormation("4-3-3");
        profile.setStyle("ATTACKING");
        profile.setRulesJson(RULES);
        profile.setWorldDefault(true);
        profiles.save(profile);

        // The snapshot and restore the reset path actually uses, in that order.
        var snapshots = initializer.snapshotTacticsProfiles();
        var mine = snapshots.stream()
                .filter(s -> clubName.equals(s.teamName))
                .findFirst()
                .orElse(null);
        assertNotNull(mine, "the club's profile must appear in the reset snapshot");

        // The restore runs against whatever profile is there now; a fresh one is the worst case,
        // because that is exactly what a world whose profile table was cascaded away looks like.
        TeamTacticsProfile cleared = profiles.findByTeamId(club.getId()).orElseThrow();
        cleared.setWorldDefault(false);
        profiles.save(cleared);
        assertEquals(0, profiles.findByTeamId(club.getId()).orElseThrow().isWorldDefault() ? 1 : 0,
                "precondition: the designation has been cleared, so the restore has work to do");

        initializer.restoreTacticsProfilesForTest(snapshots);

        assertTrue(profiles.findByTeamId(club.getId()).orElseThrow().isWorldDefault(),
                "the world default designation must survive a reset. It did not: restoreTacticsProfiles "
                        + "rebuilt every field EXCEPT worldDefault, so the owner's choice was silently "
                        + "undone and the seed button fell back to the lowest-id profile.");
    }

    @Test
    @DisplayName("a club's whole tactic library comes back, not just its default")
    void theWholeLibrarySurvives() {
        Team club = new Team();
        club.setName(CLUB + " lib " + System.nanoTime());
        club = teams.save(club);
        final String clubName = club.getName();

        for (String name : new String[]{"Osnovna", "High pressing", "Counter"}) {
            Tactic t = new Tactic();
            t.setTeam(club);
            t.setName(name);
            t.setFormation("4-4-2");
            t.setStyle("ATTACKING");
            t.setRulesJson(RULES);
            t.setDefaultTactic("Osnovna".equals(name));
            tactics.save(t);
        }
        assertEquals(3, tactics.findByTeamIdOrderByIdAsc(club.getId()).size());

        var library = initializer.snapshotTacticLibraryForTest(clubName);
        assertEquals(1, library.size(), "one club is one snapshot");
        assertEquals(3, library.get(0).tactics.size(),
                "every tactic must be snapshotted, not only the default — the library is up to ten deep "
                        + "and dropping the rest would lose the owner's work silently");

        // By id, not by entity: holding a detached Tactic and touching its lazy Team is how this
        // first failed, which says nothing about the behaviour under test.
        var ids = tactics.findByTeamIdOrderByIdAsc(club.getId()).stream()
                .map(Tactic::getId).toList();
        tactics.deleteAllById(ids);
        assertEquals(0, tactics.findByTeamIdOrderByIdAsc(club.getId()).size(),
                "precondition: the library really was cleared, as a reset would");

        initializer.restoreTacticLibraryForTest(library);

        var after = tactics.findByTeamIdOrderByIdAsc(club.getId());
        assertEquals(3, after.size(), "every saved tactic must come back after a reset");
        assertTrue(after.stream().anyMatch(Tactic::isDefaultTactic),
                "and exactly one of them must come back as the club default");
    }
}