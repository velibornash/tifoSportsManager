package org.example.footballmanager.newLogic.model;

import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@code match} is indexed on, asserted on the mapping rather than on the database.
 *
 * <p><b>One entry here reverses an earlier measurement, deliberately.</b> P1-1 proposed
 * {@code match(match_date)}, measured it, found it bought nothing and did not create it — correctly, for
 * the query it was measured against. P1-3 then keyset-paged the recovery read, and the page query walks
 * {@code (match_date, id)}, where this index is 206 ms a page faster than the id-ordered alternative.
 * An index can be worthless and then become necessary when the query beside it changes shape.
 *
 * <p><b>Why the annotation and not {@code pg_indexes}.</b> The schema is derived from the entity —
 * both profiles run {@code ddl-auto=update} — so the annotation <i>is</i> the schema, and asserting
 * it needs neither a running database nor a populated one. {@code tools/create-match-indexes.sql}
 * states the same four indexes for a database that already has rows, because {@code ddl-auto} issues
 * a plain {@code CREATE INDEX} that takes a write lock for the length of the build; the last test
 * here is what stops the two from drifting apart.
 *
 * <p><b>Why the column lists are spelled out.</b> An index on the right columns in the wrong order
 * is the same as no index. {@code player_zone_load} is the worked example in this repository: its
 * unique index leads with {@code player_id}, so {@code findByMatchId} could not use it and that was
 * read as "the index is missing". The order is the part that has to be pinned.
 *
 * <p><b>Why the count is asserted.</b> A fifth index is not free — it is a write tax on every match
 * row, measured at 16.8 us per insert for these four together. Adding one without measuring it
 * should have to break a test on purpose rather than pass review.
 */
class MatchIndexDeclarationTest {

    private static Map<String, String> declaredIndexes() {
        Table table = Match.class.getAnnotation(Table.class);
        assertNotNull(table, "Match must declare @Table(name = \"match\", indexes = ...) or the four "
                + "queries these serve are sequential scans again.");
        Map<String, String> byName = new LinkedHashMap<>();
        for (Index index : table.indexes()) {
            byName.put(index.name(), index.columnList());
        }
        return byName;
    }

    @Test
    @DisplayName("match carries exactly four indexes, each one's columns in the measured order")
    void theFourIndexesAreDeclaredInTheOrderTheyWereMeasuredIn() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("ix_match_competition_season", "competition_id, season_year");
        expected.put("ix_match_season_week", "season_year, week_number");
        expected.put("ix_match_home_team_date", "home_team_id, match_date");
        expected.put("ix_match_away_team_date", "away_team_id, match_date");
        expected.put("ix_match_date_id", "match_date, id");

        assertEquals(expected, declaredIndexes(),
                "The declared indexes on Match changed. Each one needs a named query and a measured "
                        + "before/after, and a new one costs write time on every match row. Measure it, "
                        + "then change this test on purpose - do not let it drift.");
    }

    @Test
    @DisplayName("the club-history OR has an index on each side, because one side is not enough")
    void theOrAcrossHomeAndAwayHasBothHalves() {
        Map<String, String> indexes = declaredIndexes();

        assertTrue(indexes.containsKey("ix_match_home_team_date"),
                "findByHomeTeamIdOrAwayTeamId filters on home_team_id OR away_team_id. With an index on "
                        + "one side only the planner can use neither for the OR and scans the table: "
                        + "measured 170.3 ms against 0.26 ms.");
        assertTrue(indexes.containsKey("ix_match_away_team_date"),
                "The away half of the same OR. Its absence is the regression the home half cannot cover.");
    }

    @Test
    @DisplayName("column lists are physical column names, not property names")
    void columnListsUseColumnNames() {
        assertTrue(declaredIndexes().values().stream().noneMatch(list -> list.contains("homeTeam")),
                "@Index columnList takes column names. 'homeTeam' builds an index Hibernate cannot "
                        + "resolve, and ddl-auto=update logs that DDL failure and carries on - the same "
                        + "silent shape as the match_tick_states table that never existed.");
    }

    @Test
    @DisplayName("the hand-run script creates every index the entity declares")
    void theConcurrentScriptAndTheEntityAgree() {
        String sql;
        try {
            sql = Files.readString(Path.of("tools", "create-match-indexes.sql"));
        } catch (java.io.IOException unreadable) {
            throw new AssertionError("tools/create-match-indexes.sql could not be read", unreadable);
        }

        for (String indexName : declaredIndexes().keySet()) {
            assertTrue(sql.contains("CREATE INDEX CONCURRENTLY IF NOT EXISTS " + indexName + " "),
                    "tools/create-match-indexes.sql does not create " + indexName + ". A fresh database "
                            + "gets it from ddl-auto=update and a populated one does not, which is "
                            + "exactly the drift this test is here to catch.");
        }
    }
}
