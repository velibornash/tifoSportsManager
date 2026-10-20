package org.tifo.footballmanager.newLogic.service;

import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.model.tactics.TeamTacticsProfile;
import org.tifo.footballmanager.newLogic.repository.TeamRepository;
import org.tifo.footballmanager.newLogic.repository.TeamTacticsProfileRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One tactic is <em>the</em> world default, and the owner chose it.
 *
 * <p><b>What was in the way.</b> Two bugs, both behind a button labelled "Save Default Tactics":
 *
 * <ol>
 *   <li>{@code saveDefaultTactics} in {@code admin-view.js} read the club's saved editor profile and PUT
 *       it straight back, field for field, then reported success. A no-op that stored nothing.</li>
 *   <li>There was nowhere to record the choice, so {@code seedDefaultTacticForEveryClub} used
 *       {@code findAll().stream().findFirst()} — whichever row the database ordered first. The same
 *       button on two databases holding the same profiles could hand every club a different tactic, and
 *       nothing in the UI said which one it had picked.</li>
 * </ol>
 *
 * <p>Owner, 2026-10-18: <i>"da ova opcija snimi taktiku koju ću napraviti za Omladinac i da ta taktika tj
 * ti tactical rules preživi Reset db i da se stalno učitavaju nazad"</i>.
 *
 * <p><b>The test that matters is {@link #theDesignatedDefaultBeatsTheLowestId()}.</b> Asserting only
 * that "a default is returned" would pass against the arbitrary {@code findFirst()}, which is the code
 * that was there.
 */
@SpringBootTest
@ActiveProfiles("test")
class WorldDefaultTacticTest {

    @Autowired TacticLibraryService library;
    @Autowired TeamRepository teams;
    @Autowired TeamTacticsProfileRepository profiles;

    private Team clubWith(String name, String formation, String rules) {
        Team club = new Team();
        club.setName(name + " " + System.nanoTime());
        club = teams.save(club);

        TeamTacticsProfile profile = new TeamTacticsProfile();
        profile.setTeam(club);
        profile.setFormation(formation);
        profile.setStyle("Balanced");
        profile.setRulesJson(rules);
        profile.setWorldDefault(false);
        profiles.save(profile);
        return club;
    }

    private static String rules(int n) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"slotKey\":\"CMR\",\"ballStateKey\":\"CELL_").append(i % 7)
              .append('_').append(i % 6)
              .append("\",\"possessionContext\":\"WE_HAVE_BALL\",\"targetCellKey\":\"CELL_")
              .append(3 + i % 2).append("_3\"}");
        }
        return sb.append(']').toString();
    }

    @Test
    @DisplayName("the designated default beats the lowest id")
    void theDesignatedDefaultBeatsTheLowestId() {
        // The oldest profile is deliberately NOT the one we designate, so that a provider still using
        // findFirst()/lowest-id cannot pass this.
        Team oldest = clubWith("World Default Oldest", "4-4-2", rules(3));
        Team chosen = clubWith("World Default Chosen", "3-5-2", rules(9));

        Map<String, Object> saved = library.markProfileAsWorldDefault(chosen.getId());
        assertEquals("3-5-2", saved.get("formation"),
                "the call must report what it actually designated, not what the caller hoped for");

        var template = library.worldDefaultProfile();
        assertTrue(template.isPresent(), "a profile was just designated, so there must be one");
        assertEquals("3-5-2", template.get().getFormation(),
                "the world default must be the one the owner chose, not the oldest profile");
        assertTrue(template.get().isWorldDefault());
        assertFalse(profiles.findByTeamId(oldest.getId()).orElseThrow().isWorldDefault(),
                "exactly one profile carries the flag; the older one must have lost it");
    }

    @Test
    @DisplayName("exactly one profile is ever the world default")
    void onlyOneProfileIsEverTheWorldDefault() {
        Team a = clubWith("Single Default A", "4-4-2", rules(3));
        Team b = clubWith("Single Default B", "4-4-3", rules(5));

        library.markProfileAsWorldDefault(a.getId());
        library.markProfileAsWorldDefault(b.getId());

        List<TeamTacticsProfile> flagged = profiles.findByWorldDefaultTrue();
        assertEquals(1, flagged.size(),
                "a world-wide setting that can be ambiguous is not a setting; got " + flagged.size()
                        + " profiles carrying the flag: "
                        + flagged.stream().map(p -> p.getFormation()).toList());
        assertEquals("4-4-3", flagged.get(0).getFormation(),
                "the most recent designation wins");
    }

    @Test
    @DisplayName("naming a club with no profile fails loudly")
    void namingAClubWithNoProfileFailsLoudly() {
        Team empty = new Team();
        empty.setName("No Profile Club " + System.nanoTime());
        final Long emptyId = teams.save(empty).getId();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> library.markProfileAsWorldDefault(emptyId),
                "silently doing nothing is what produced this bug; the button reported success while "
                        + "storing nothing, and must not do it again");
        assertTrue(thrown.getMessage().contains("Tactic Editor"),
                "the message should tell the owner what to do, not just that it failed");
    }
}