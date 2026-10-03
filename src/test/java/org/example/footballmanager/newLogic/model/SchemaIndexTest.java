package org.example.footballmanager.newLogic.model;

import org.example.footballmanager.BaseTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The indexes the schema promises actually exist.
 *
 * <p><b>Declaring an index is not the same as having one.</b> A typo in a column name, a table renamed
 * quietly, or a profile whose {@code ddl-auto} is not {@code update} each produce a build that is green and a
 * database that is not faster. That is the shape of failure this repository keeps hitting, and the reason
 * this reads the <b>metadata</b> rather than trusting the annotations.
 *
 * <p>The list is the ranked omissions from {@code dataFixSuggestions.md} §4.3 — the ones a query in the app
 * actually depends on:
 *
 * <ul>
 *   <li>{@code match_fixture (season_year, week_number, day_number, played)} — eight repository methods key on
 *       exactly this triple and the hourly job calls them every hour. The identical triple is indexed on
 *       {@code job_run}: the bookkeeping table got it and the data table did not.</li>
 *   <li>{@code player (team_id)} — no index at all on the largest table, and {@code findByTeamId} is the
 *       most-called query in the app.</li>
 *   <li>{@code match_player_stats (player_id, match_id)} — the lookup behind every squad load.</li>
 *   <li>{@code season_competition (competition_id, season_year)} — called inside loops in six places.</li>
 *   <li>{@code competition_entry (team_id)} — also called inside loops during seeding.</li>
 * </ul>
 *
 * <p>§4.2 also records a dead index: {@code ix_competition_entry_sc} is a strict prefix of
 * {@code ix_competition_entry_sc_pos} and was carrying nothing. Dropped, and asserted dropped.
 */
class SchemaIndexTest extends BaseTest {

    @Autowired
    private javax.sql.DataSource dataSource;

    /**
     * The index names the <b>database</b> has on a table.
     *
     * <p>Read from JDBC metadata rather than from the annotations, because the question is whether the
     * schema has them and not whether someone typed them.
     */
    private Set<String> indexNamesOn(String table) throws Exception {
        Set<String> names = new LinkedHashSet<>();
        try (var connection = dataSource.getConnection();
             var rs = connection.getMetaData()
                     .getIndexInfo(null, null, table, false, false)) {
            while (rs.next()) {
                String name = rs.getString("INDEX_NAME");
                if (name != null) {
                    names.add(name.toUpperCase());
                }
            }
        }
        return names;
    }

    @Test
    @Transactional
    @DisplayName("the indexes the hot queries depend on exist in the schema")
    void theHotIndexesExist() throws Exception {
        assertTrue(indexNamesOn("MATCH_FIXTURE").stream()
                        .anyMatch(n -> n.equals("IX_MATCH_FIXTURE_SEASON_WEEK_DAY")),
                "match_fixture has no (season_year, week_number, day_number, played) index. Eight repository "
                        + "methods key on exactly that triple and the hourly job calls them every hour.");

        assertTrue(indexNamesOn("PLAYER").stream().anyMatch(n -> n.equals("IX_PLAYER_TEAM")),
                "player has no index on team_id, on the largest table, for the most-called query in the app.");

        assertTrue(indexNamesOn("MATCH_PLAYER_STATS").stream()
                        .anyMatch(n -> n.equals("IX_MATCH_PLAYER_STATS_PLAYER_MATCH")),
                "match_player_stats has no (player_id, match_id) index, and findByPlayerId is the 1+N "
                        + "lookup behind every squad load.");

        assertTrue(indexNamesOn("SEASON_COMPETITION").stream()
                        .anyMatch(n -> n.equals("IX_SEASON_COMPETITION_COMP_SEASON")),
                "season_competition has no (competition_id, season_year) index, and that lookup runs inside "
                        + "loops in six places.");

        assertTrue(indexNamesOn("COMPETITION_ENTRY").stream()
                        .anyMatch(n -> n.equals("IX_COMPETITION_ENTRY_TEAM")),
                "competition_entry has no index on team_id, and findBySeasonCompetitionAndTeam runs inside "
                        + "seeding loops.");
    }

    @Test
    @Transactional
    @DisplayName("the dead strict-prefix index is gone")
    void theDeadPrefixIndexIsGone() throws Exception {
        Set<String> names = indexNamesOn("COMPETITION_ENTRY");
        assertTrue(names.stream().noneMatch(n -> n.equals("ix_competition_entry_sc")),
                "ix_competition_entry_sc is back. It is a strict prefix of ix_competition_entry_sc_pos, so "
                        + "every query it served is already served - it was dead weight. Asserting its "
                        + "absence, because an index that is dropped and comes back is not a drop.");
    }

    /**
     * The three indexes that make the D1 query narrowing actually pay.
     *
     * <p><b>Added after measuring, and the measurement is why they exist.</b> Each of the three new
     * queries was measured with {@code EXPLAIN ANALYZE} against a seeded Serbian world both with and
     * without its index. Without the index, narrowing the result set does <b>not</b> narrow the scan —
     * {@code player.fatigue > 0} came back <em>slower</em> than the {@code findAll()} it replaced, 4.75 ms
     * against 3.77 ms, both reading the same 753 buffers, because Postgres still had to read every
     * player to find the tired ones. With it: 0.79 ms and 279 buffers.
     *
     * <p>So the code fixes and these indexes are one change, not two. The code stops materialising rows
     * the JVM does not need; the index stops the database reading rows it does not need. Without both,
     * only the first half is happening.
     */
    @Test
    @Transactional
    @DisplayName("the indexes the narrowed D1 queries depend on exist")
    void theD1IndexesExist() throws Exception {
        assertTrue(indexNamesOn("PLAYER").stream().anyMatch(n -> n.equals("IX_PLAYER_FATIGUE_TIRED")),
                "player has no fatigue index. findBySkillsFatigueGreaterThan(0) runs weekly and, unindexed, "
                        + "is a Seq Scan of every player in the world - measured slower than the findAll() it "
                        + "replaced.");

        assertTrue(indexNamesOn("TEAM").stream().anyMatch(n -> n.equals("IX_TEAM_COUNTRY")),
                "team has no index on country_id. findClubTeamsForCountry is asked once per country, so a "
                        + "seeding pass of the world's squads asks it 48 times, and unindexed each one scans "
                        + "every club in the world.");

        assertTrue(indexNamesOn("MATCH_FIXTURE").stream().anyMatch(n -> n.equals("IX_MATCH_FIXTURE_SEASON_ROUND")),
                "match_fixture has no (season_year, round_number) index. Four request paths filter on those two "
                        + "columns and the existing season/week/day index cannot seek on round_number, so they "
                        + "fall back to reading the whole fixture table.");
    }
}
