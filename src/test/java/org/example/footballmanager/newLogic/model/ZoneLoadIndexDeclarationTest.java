package org.example.footballmanager.newLogic.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The zone-load indexes: the entity declares them, and the migration file carries the same ones.
 *
 * <p><b>Why two (found 2026-10-19, P1-1).</b> {@code player_zone_load} had one index, on
 * {@code player_id} — and {@code findByMatchId} cannot use it. The table is one row per player per
 * zone per match, so asking for one match's rows walked the whole table, once per fixture.
 *
 * <p><b>Why the entity and a SQL file both.</b> The same reason as the {@code match} table's five:
 * {@code ddl-auto=update} issues a plain {@code CREATE INDEX} at every boot, which takes a write lock
 * for the length of the build, and a full-scale table is one row per match per week for twelve weeks.
 * {@code CONCURRENTLY} does not block. The entity stays the source of truth and this test asserts the
 * two cannot drift apart.
 */
class ZoneLoadIndexDeclarationTest {

    @Test
    @DisplayName("the entity declares both indexes")
    void theEntityDeclaresBothIndexes() {
        Table table = PlayerZoneLoad.class.getAnnotation(Table.class);
        assertTrue(table != null, "the entity must declare an explicit @Table");

        for (String name : new String[]{"ix_zone_load_player", "ix_zone_load_match"}) {
            assertTrue(Arrays.stream(table.indexes()).anyMatch(i -> name.equals(i.name())),
                    "the entity no longer declares " + name + ". The migration file carries the same "
                            + "indexes, and the two must not drift apart");
        }
    }

    @Test
    @DisplayName("the match index is on the match, not on something else")
    void theMatchIndexIsOnTheMatchColumn() {
        Table table = PlayerZoneLoad.class.getAnnotation(Table.class);
        Index matchIndex = Arrays.stream(table.indexes())
                .filter(i -> "ix_zone_load_match".equals(i.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("ix_zone_load_match is not declared"));

        assertTrue("match_id".equals(matchIndex.columnList()),
                "ix_zone_load_match must be on match_id - that is the column findByMatchId reads, and "
                        + "an index on anything else would not serve it. It is on "
                        + matchIndex.columnList());
    }

    @Test
    @DisplayName("the migration file carries the same indexes")
    void theMigrationFileCarriesTheSameIndexes() throws Exception {
        String sql = Files.readString(Path.of("tools/create-zone-load-indexes.sql"), StandardCharsets.UTF_8);
        for (String name : new String[]{"ix_zone_load_player", "ix_zone_load_match"}) {
            assertTrue(sql.contains(name),
                    "the migration file no longer creates " + name + ". The entity declares it and a "
                            + "fresh database gets it from ddl-auto, but an EXISTING database only gets "
                            + "it from this file");
        }
        assertTrue(sql.contains("CONCURRENTLY"),
                "the migration must build the index CONCURRENTLY. A plain CREATE INDEX takes a write "
                        + "lock for the length of the build, and this table is one row per player per "
                        + "zone per match");
    }
}
