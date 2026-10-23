package org.tifo.footballmanager.newLogic.model;

import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The five cosmetic columns pair a {@code columnDefinition} whose {@code varchar(N)} differs from
 * the JPA default length, and both halves of that pair are load-bearing (owner's startup log,
 * 2026-10-23):
 *
 * <p><b>1 — {@code length} must equal the definition's {@code varchar(N)}.</b> {@code ddl-auto=update}
 * compares the column's declared length against the real one; without {@code length} the declared
 * value is the JPA default 255, the database holds 16 or 7, and the mismatch makes Hibernate
 * re-emit the whole columnDefinition as
 * {@code alter column x set data type varchar(16) not null default 'PLAIN'} — PostgreSQL rejects
 * {@code not null} inside {@code SET DATA TYPE}, so the statement failed with a WARN on
 * <b>every startup of every database</b> while the columns themselves were already exactly right.
 * {@code Country.country_state} carries {@code length = 16} and never warned; these five did not.
 *
 * <p><b>2 — the definition must keep {@code not null default}.</b> That is what a fresh database
 * gets its guaranteed schema from (the AGENTS.md rule for NOT NULL columns): the columns were born
 * from this text and there is deliberately no ResetService step backing them up.
 *
 * <p>Removing either half from any of the five turns this test red.
 */
class ColumnLengthMatchesItsDefinitionTest {

    private static final Pattern VARCHAR_LENGTH = Pattern.compile("varchar\\((\\d+)\\)");

    @Test
    void everyCosmeticColumnKeepsBothHalvesOfItsDefinition() throws Exception {
        assertColumnIsComplete(Stadium.class, "pitchPattern");
        assertColumnIsComplete(Stadium.class, "floodlightStyle");
        assertColumnIsComplete(Team.class, "homeKitColor");
        assertColumnIsComplete(Team.class, "awayKitColor");
        assertColumnIsComplete(Team.class, "kitPattern");
    }

    private static void assertColumnIsComplete(Class<?> owner, String fieldName) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(fieldName);
        Column column = field.getAnnotation(Column.class);
        String where = owner.getSimpleName() + "." + fieldName;
        assertTrue(column != null, where + " must carry @Column");

        Matcher declared = VARCHAR_LENGTH.matcher(column.columnDefinition());
        assertTrue(declared.find(),
                where + " columnDefinition must declare varchar(N): " + column.columnDefinition());
        assertEquals(Integer.parseInt(declared.group(1)), column.length(),
                where + " @Column.length must equal the columnDefinition's varchar(N), or ddl-auto=update "
                        + "compares the JPA default 255 against the real column length and re-emits the full "
                        + "columnDefinition as invalid 'set data type ... not null default ...' on every startup");
        assertTrue(column.columnDefinition().contains("not null default"),
                where + " columnDefinition must keep its 'not null default' — it is the only thing a fresh "
                        + "database gets its guaranteed schema from");
    }
}
