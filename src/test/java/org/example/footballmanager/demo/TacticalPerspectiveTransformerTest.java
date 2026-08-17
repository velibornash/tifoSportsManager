package org.example.footballmanager.demo;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TacticalPerspectiveTransformerTest {

    @Test
    void everyPitchCellMirrorsProgressAndLateralAxesForAway() {
        for (int row = 1; row <= 7; row++) {
            for (int column = 1; column <= 6; column++) {
                Position away = TacticalPerspectiveTransformer.toPhysical(
                        new Position(row, column), "AWAY");
                assertEquals(8 - row, away.getRow(), "row " + row + ", col " + column);
                assertEquals(7 - column, away.getColumn(), "row " + row + ", col " + column);

                Position home = TacticalPerspectiveTransformer.toHomePerspective(away, "AWAY");
                assertEquals(row, home.getRow());
                assertEquals(column, home.getColumn());
            }
        }
    }

    @Test
    void sharedTacticalRuleIsLookedUpInAwayPerspectiveAndMappedBack() {
        Map<String, Map<String, Position>> rules = new LinkedHashMap<>();
        rules.put("DL", Map.of("CELL_6_5", new Position(1, 1)));
        TacticsRules tactics = new TacticsRules(rules, Map.of("DL", new Position(1, 1)));

        Position awayTarget = tactics.desiredCell("DL", new Position(1, 1), "AWAY");

        assertEquals(7.0, awayTarget.getRow());
        assertEquals(6.0, awayTarget.getColumn());
    }
}
