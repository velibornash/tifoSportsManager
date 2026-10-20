package org.tifo.footballmanager.newLogic.fantasy;

import org.tifo.footballmanager.newLogic.model.Position;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The scoring table, line by line (owner, 2026-10-17).
 *
 * <p>Every value here is a number the owner approved. They are asserted individually rather than through
 * one big example, because a single example that is out by two points tells you nothing about which rule
 * moved — and the point of writing the table down as code is that it can be read against the table he was
 * shown.
 */
class FantasyPointsRulesTest {

    @Test
    @DisplayName("appearing is worth one, staying an hour is worth two")
    void appearancePoints() {
        assertEquals(0, FantasyPointsRules.points(performance(Position.MID, 0)));
        assertEquals(1, FantasyPointsRules.points(performance(Position.MID, 1)));
        assertEquals(1, FantasyPointsRules.points(performance(Position.MID, 59)));
        assertEquals(2, FantasyPointsRules.points(performance(Position.MID, 60)));
        assertEquals(2, FantasyPointsRules.points(performance(Position.MID, 90)));
        assertEquals(0, FantasyPointsRules.points(null), "no match, no points");
    }

    @Test
    @DisplayName("a goal is worth more the further back he plays")
    void goalPoints() {
        assertEquals(6, goals(Position.GK, 1));
        assertEquals(6, goals(Position.DEF, 1));
        assertEquals(5, goals(Position.MID, 1));
        assertEquals(5, goals(Position.WNG, 1), "a winger is a midfielder and scores as one");
        assertEquals(4, goals(Position.ATT, 1));
        assertEquals(12, goals(Position.DEF, 2), "two goals are worth two goals");
    }

    @Test
    @DisplayName("an assist is worth three")
    void assistPoints() {
        FantasyMatchPerformance p = new FantasyMatchPerformance(Position.ATT, 90, 0, 2, 0, 0, 0, 1, 70, 0);
        assertEquals(2 + 6, FantasyPointsRules.points(p), "two assists on top of the appearance");
    }

    @Test
    @DisplayName("a clean sheet is four at the back and one in midfield, and needs an hour")
    void cleanSheetPoints() {
        assertEquals(4, cleanSheet(Position.GK, 90, 0));
        assertEquals(4, cleanSheet(Position.DEF, 90, 0));
        assertEquals(1, cleanSheet(Position.MID, 90, 0));
        assertEquals(0, cleanSheet(Position.ATT, 90, 0), "an attacker gets nothing for a clean sheet");
        assertEquals(0, cleanSheet(Position.DEF, 59, 0), "an hour is the condition");
        assertEquals(0, cleanSheet(Position.DEF, 90, 1), "conceding ends it");
    }

    @Test
    @DisplayName("a goalkeeper earns one for every three saves")
    void savePoints() {
        assertEquals(0, saves(0));
        assertEquals(0, saves(2));
        assertEquals(1, saves(3));
        assertEquals(2, saves(6));
        assertEquals(2, saves(8), "the remainder does not carry");
    }

    @Test
    @DisplayName("cards cost, and the top three ratings in a match are worth three, two and one")
    void cardsAndBonus() {
        FantasyMatchPerformance yellow = new FantasyMatchPerformance(Position.DEF, 90, 0, 0, 1, 0, 0, 0, 70, 0);
        assertEquals(2 + 4 - 1, FantasyPointsRules.points(yellow));

        FantasyMatchPerformance red = new FantasyMatchPerformance(Position.DEF, 90, 0, 0, 0, 1, 0, 0, 70, 0);
        assertEquals(2 + 4 - 3, FantasyPointsRules.points(red));

        assertEquals(3, FantasyPointsRules.bonusFor(0));
        assertEquals(2, FantasyPointsRules.bonusFor(1));
        assertEquals(1, FantasyPointsRules.bonusFor(2));
        assertEquals(0, FantasyPointsRules.bonusFor(3));
        assertEquals(0, FantasyPointsRules.bonusFor(99));
    }

    @Test
    @DisplayName("a whole match: a defender who scored, kept a clean sheet and was booked")
    void aWholeMatch() {
        FantasyMatchPerformance p = new FantasyMatchPerformance(
                Position.DEF, 90, 1, 0, 1, 0, 0, 0, 88, 3);
        // 2 appearance + 6 goal + 4 clean sheet - 1 yellow + 3 bonus
        assertEquals(14, FantasyPointsRules.points(p));
    }

    @Test
    @DisplayName("a substitute who did not get on scores nothing")
    void anUnusedSubstituteScoresNothing() {
        FantasyMatchPerformance unused = new FantasyMatchPerformance(Position.ATT, 0, 0, 0, 0, 0, 0, 3, 50, 0);
        assertEquals(0, FantasyPointsRules.points(unused),
                "on the bench and never on the pitch is not an appearance, however badly his side defended");
    }

    // ------------------------------------------------------------------ helpers

    private static FantasyMatchPerformance performance(Position position, int minutes) {
        return new FantasyMatchPerformance(position, minutes, 0, 0, 0, 0, 0, 1, 70, 0);
    }

    private static int goals(Position position, int goals) {
        return FantasyPointsRules.points(
                new FantasyMatchPerformance(position, 90, goals, 0, 0, 0, 0, 1, 70, 0)) - 2;
    }

    private static int cleanSheet(Position position, int minutes, int conceded) {
        return FantasyPointsRules.cleanSheetPoints(FantasyRules.fantasyPositionOf(position), minutes, conceded);
    }

    private static int saves(int saves) {
        return FantasyPointsRules.points(
                new FantasyMatchPerformance(Position.GK, 90, 0, 0, 0, 0, saves, 2, 70, 0))
                - 2 - FantasyPointsRules.cleanSheetPoints(Position.GK, 90, 2);
    }
}
