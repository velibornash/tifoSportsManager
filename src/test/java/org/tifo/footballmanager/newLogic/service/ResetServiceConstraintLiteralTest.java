package org.tifo.footballmanager.newLogic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The constraint-alignment log line has to say the same thing the constraint says.
 *
 * <p><b>What was wrong (found on the owner's server, 2026-10-19).</b> The list of allowed values was
 * joined as {@code A', 'B', 'C} — which is right <em>inside</em> {@code IN ('...')} and nonsense on its
 * own — and the log line printed it raw. The server said:
 *
 * <pre>
 *   staff_member now accepts every StaffRole: HEAD_COACH', 'ASSISTANT', 'GK_COACH', 'PHYSIO',
 *   'DOCTOR', 'SCOUT', 'YOUTH_COACH
 * </pre>
 *
 * and a reader could not tell it was a list at all. The owner read it as a broken value.
 *
 * <p>The list is now built <b>with its quotes</b>, so the same string is both the SQL literal and
 * what the log prints.
 */
class ResetServiceConstraintLiteralTest {

    @Test
    @DisplayName("the literal is quoted at both ends and quotes every value")
    void theLiteralIsQuoted() {
        String literal = ResetService.quotedList(new String[]{"A", "B", "C"});

        assertEquals("'A', 'B', 'C'", literal,
                "the literal must be a quoted list. It used to be joined as \"A', 'B', 'C\" — right "
                        + "inside IN ('...') and nonsense on its own, which is what the log printed");
    }

    @Test
    @DisplayName("one value is still quoted on both sides")
    void aSingleValueIsStillQuoted() {
        assertEquals("'ONLY'", ResetService.quotedList(new String[]{"ONLY"}),
                "a one-value list must be quoted too, or the constraint is written as IN ('')");
    }

    @Test
    @DisplayName("an empty list is empty, not a pair of quotes")
    void anEmptyListIsEmpty() {
        assertEquals("", ResetService.quotedList(new String[0]),
                "an empty list must not become \"''\", which is a constraint that accepts nothing");
    }

    @Test
    @DisplayName("the literal is what goes inside the constraint")
    void theLiteralIsWhatGoesInsideTheConstraint() {
        String literal = ResetService.quotedList(new String[]{"HEAD_COACH", "SCOUT"});

        assertTrue(("CHECK (role IN (" + literal + "))").contains("role IN ('HEAD_COACH', 'SCOUT')"),
                "the statement and the log must be the same string, so the constraint reads exactly "
                        + "what the log says");
    }
}
