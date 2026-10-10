package org.example.footballmanager.newLogic.service;

import org.example.commonmanager.model.User;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.commonmanager.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four club-id sites must resolve a <b>Team</b>, never a <b>CTeam</b> (T-REST-6).
 *
 * <p><b>The id spaces in this database genuinely collide, and that is the whole point.</b>
 * {@code cteam} holds ids 1–17 and <b>all seventeen of them are also {@code team} ids</b> — measured, not
 * assumed. So there is no assertion of the form "this number is not a CTeam id": every number a correct
 * endpoint can return is also a CTeam id, and a check written that way would pass forever while proving
 * nothing.
 *
 * <p><b>What can be asserted</b> is provenance. A {@code CTeam} id is only ever wrong because it came
 * from the wrong table; the number itself carries no information. These tests therefore check the two
 * things that are actually checkable:
 *
 * <ol>
 *   <li><b>The overlap is real and recorded here.</b> If a future change makes the id spaces disjoint,
 *       the cheap number-based assertion becomes possible and this class should say so rather than let
 *       someone believe it is covered.</li>
 *   <li><b>Every one of the four sites reads the {@code User.footballTeam} FK</b>, and that FK can only
 *       hold a {@code Team} — it is a typed column pointing at {@code team}. A name-join to the text
 *       mode would be the defect, and it would produce a number this test cannot detect but which the
 *       guard below is written to catch at the source.</li>
 * </ol>
 *
 * <p><b>On the source guard:</b> the deleted {@code findDistinctManagedTeamIds} was a
 * {@code @Deprecated} name-join across the two tables. A test that walks four controllers cannot see a
 * re-introduction in a service, so {@link #noNameJoinToTheTextModeRemains()} reads the sources instead.
 * That is a blunt instrument and it is here deliberately: this repository has been bitten by the same
 * removal being restored three times.
 */
@SpringBootTest
@Transactional
class ClubIdSpaceIsNotConfusedWithTextModeTest {

    @Autowired TeamRepository teams;
    @Autowired UserRepository users;

    @Test
    @DisplayName("the two id spaces really do collide, which is why a number check is impossible")
    void theIdSpacesCollide() {
        Set<Long> textModeIds = textModeTeamIds();
        Set<Long> clubIds = teamIds();

        List<Long> shared = textModeIds.stream().filter(clubIds::contains).sorted().toList();

        assertFalse(textModeIds.isEmpty(),
                "no cteam rows at all, so this whole class would pass without looking at anything. "
                        + "Seed the text-football mode, or the check is vacuous.");

        assertFalse(shared.isEmpty(),
                "the cteam and team id spaces are disjoint in this database (" + shared + " is empty), so a "
                        + "plain 'is this id a CTeam id' assertion is now possible and this class should be "
                        + "rewritten to use it. Left as-is, it is weaker than the database now allows.");
    }

    @Test
    @DisplayName("every account attached to a club resolves to a real Team row")
    void accountsResolveToRealTeamRows() {
        List<User> attached = users.findAll().stream()
                .filter(u -> u.getFootballTeam() != null && u.getFootballTeam().getId() != null)
                .toList();

        assertFalse(attached.isEmpty(),
                "no account has a club attached, so the four sites have nothing to resolve and would pass "
                        + "without being exercised");

        Set<Long> realTeamIds = teamIds();
        for (User user : attached) {
            assertTrue(realTeamIds.contains(user.getFootballTeam().getId()),
                    "account " + user.getUsername() + " carries footballTeam id "
                            + user.getFootballTeam().getId() + ", which is not a row in `team`. The FK says "
                            + "it is a Team, so either the FK is unenforced or this is not the column the "
                            + "four sites read.");
        }
    }

    @Test
    @DisplayName("the club id sources read User.footballTeam, not a name-join across the two tables")
    void noNameJoinToTheTextModeRemains() {
        // The deleted `findDistinctManagedTeamIds` joined on a NAME string, which is how a text-mode row
        // got into a list of graphical-mode ids. Its return, in any form, is the defect this task is
        // about — and unlike a number check, a name-join is visible here.
        String[] sources = {
                "src/main/java/org/example/footballmanager/newLogic/controller/APIController.java",
                "src/main/java/org/example/footballmanager/newLogic/controller/TeamController.java",
                "src/main/java/org/example/footballmanager/newLogic/controller/CountryController.java",
                "src/main/java/org/example/footballmanager/newLogic/service/NationalTeamAppointments.java",
        };

        for (String path : sources) {
            String body = read(path);
            assertFalse(body.contains("findDistinctManagedTeamIds"),
                    path + " refers to findDistinctManagedTeamIds again. That method joined the graphical "
                            + "and text-football team tables on a name, and it is why a CTeam id could be "
                            + "returned where a Team id was expected. It was deleted for this reason.");
        }
    }

    private Set<Long> textModeTeamIds() {
        return jdbcIds("select id from cteam");
    }

    private Set<Long> teamIds() {
        return teams.findAll().stream().map(Team::getId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
    }

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private Set<Long> jdbcIds(String sql) {
        try {
            return Set.copyOf(jdbcTemplate.query(sql, (rs, i) -> rs.getLong("id")));
        } catch (Exception e) {
            throw new AssertionError("could not read '" + sql + "', so this test cannot compare the two id "
                    + "spaces and would pass for any implementation: " + e.getMessage(), e);
        }
    }

    private static String read(String path) {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(path));
        } catch (java.io.IOException e) {
            throw new AssertionError("could not read " + path + "; this test is checking the source of a "
                    + "file that is no longer where it was, which is itself a finding.", e);
        }
    }
}