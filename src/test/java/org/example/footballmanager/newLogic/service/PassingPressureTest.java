package org.example.footballmanager.newLogic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PPDA, and what it is actually made of (T0-UI-6).
 *
 * <p><b>The denominator is the point.</b> PPDA is usually taught as passes per (tackles + interceptions +
 * fouls). This game counts tackles per player and never aggregates them to the side, so a textbook PPDA
 * cannot be computed from what is stored. Rather than invent the missing number or quietly compute
 * something else and call it PPDA, the denominator is the set the engine <em>does</em> record per team, and
 * the screen states which set it is.
 */
class PassingPressureTest {

    private static Map<String, Object> stats(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    @Test
    @DisplayName("passes per defensive action, from the counts the engine records")
    void itComputesFromTheStoredCounts() {
        Map<String, Object> stored = stats(
                "homePassesAttempted", 400, "homeClearances", 2, "homeInterceptions", 8,
                "homeBlocks", 4, "homeDeflections", 3, "homeFouls", 11);

        // 400 / (2+8+4+3+11) = 400/28
        assertEquals(14.3, PassingPressure.forSide(stored, "HOME"), 0.05);
    }

    @Test
    @DisplayName("a team that was barely pressed has an undefined ratio, not an infinite one")
    void anUnpressedTeamHasNoFigure() {
        Map<String, Object> stored = stats(
                "homePassesAttempted", 400,
                "homeClearances", 0, "homeInterceptions", 0, "homeBlocks", 0,
                "homeDeflections", 0, "homeFouls", 0);

        assertNull(PassingPressure.forSide(stored, "HOME"),
                "dividing by zero would print Infinity on a manager's screen; no figure is the honest one");
    }

    @Test
    @DisplayName("a match stored before these counts existed shows nothing rather than zero")
    void anOlderMatchShowsNothing() {
        // The 235 matches already in the world have a 24-key statsJson with none of these keys.
        Map<String, Object> older = stats("homePossession", 55.0, "homeShotsOnTarget", 4);

        assertNull(PassingPressure.forSide(older, "HOME"),
                "zero would read as 'this team never passed', which is a claim about a game rather than "
                        + "a statement about a column that was never written");
    }

    @Test
    @DisplayName("the definition travels with the number")
    void theDefinitionIsCarried() {
        Map<String, Object> both = PassingPressure.bothSides(stats(
                "homePassesAttempted", 300, "homeFouls", 12,
                "awayPassesAttempted", 250, "awayFouls", 20));

        assertTrue(String.valueOf(both.get("definition")).contains("clearances"),
                "a manager cannot act on 'PPDA' alone; the screen has to say what is in the denominator");
        assertEquals(25.0, (Double) both.get("homePpda"), 0.05);
        assertEquals(12.5, (Double) both.get("awayPpda"), 0.05);
    }

    @Test
    @DisplayName("the figure says what it excludes")
    void theExclusionIsNamed() {
        // Tackles are counted per player and never summed to the side, so this is deliberately not the
        // textbook definition — and saying so is the point.
        assertTrue(PassingPressure.DEFINITION.contains("clearances"));
        assertTrue(!PassingPressure.DEFINITION.toLowerCase().contains("tackle"),
                "the definition must not imply a textbook PPDA this does not compute");
    }
}