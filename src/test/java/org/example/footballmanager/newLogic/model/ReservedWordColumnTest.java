package org.example.footballmanager.newLogic.model;

import jakarta.persistence.Column;
import jakarta.persistence.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards against entity column names that are reserved words on the databases we actually use.
 *
 * <p>Background: {@code MatchTickState} mapped its minute helper as {@code @Column(name = "minute")}.
 * MINUTE is reserved in H2 2.x, so {@code create table match_tick_states} failed. Hibernate only
 * logs DDL failures and continues, so the table silently did not exist in the test database and
 * {@code MatchPersistenceService.saveTickHistory} failed at runtime - intermittently, depending on
 * how many Spring contexts had been created before the table was touched. PostgreSQL tolerates the
 * bare name, so the bug only ever showed up in tests. Renamed to {@code match_minute}.
 */
class ReservedWordColumnTest {

    /**
     * Reserved in H2 2.x. Keep in sync with the H2 version in pom.xml. Only words that have
     * actually bitten us are listed; this is a tripwire, not a complete keyword list.
     */
    // NB: Set.of() throws on duplicates - keep every entry unique.
    private static final Set<String> KNOWN_RESERVED = Set.of(
            "minute", "second", "value", "key", "year", "day", "month", "hour",
            "user", "order", "group", "table", "select", "from", "where", "check", "column"
    );

    private static final String[] PERSISTED_ENTITIES = {
            "org.example.footballmanager.newLogic.model.MatchTickState"
    };

    @Test
    @DisplayName("MatchTickState persists the minute helper as match_minute, not the reserved 'minute'")
    void matchTickStateUsesNonReservedMinuteColumn() throws Exception {
        Field minute = MatchTickState.class.getDeclaredField("minute");
        Column column = minute.getAnnotation(Column.class);
        assertNotNull(column, "minute field must carry an explicit @Column name");

        String name = column.name().toLowerCase();
        assertFalse(KNOWN_RESERVED.contains(name),
                "column '" + column.name() + "' is a reserved word (MINUTE is reserved in H2 2.x)");
        assertTrue(name.equals("match_minute"),
                "expected the renamed column match_minute, found: " + column.name());
    }

    @Test
    @DisplayName("every explicit @Column name on the entities we care about is a valid identifier")
    void noPersistedEntityUsesAReservedColumnName() throws Exception {
        for (String className : PERSISTED_ENTITIES) {
            Class<?> type = Class.forName(className);
            Table table = type.getAnnotation(Table.class);
            assertNotNull(table, className + " should declare an explicit @Table");

            for (Field field : type.getDeclaredFields()) {
                Column column = field.getAnnotation(Column.class);
                if (column == null || column.name().isBlank()) {
                    continue;
                }
                String name = column.name().toLowerCase();
                assertFalse(KNOWN_RESERVED.contains(name),
                        className + "." + field.getName() + " maps to reserved column '" + column.name() + "'");
            }
        }
    }

    @Test
    @DisplayName("the reserved-word list itself is sane")
    void reservedListIsUsable() {
        assertTrue(KNOWN_RESERVED.contains("minute"));
        assertTrue(KNOWN_RESERVED.stream().allMatch(w -> w.matches("[a-z]+")));
        assertTrue(KNOWN_RESERVED.size() > 5,
                "list should carry more than a token entry, found: " + Arrays.toString(KNOWN_RESERVED.toArray()));
    }
}
