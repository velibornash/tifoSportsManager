package org.example.footballmanager.newLogic.fantasy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What changing a squad costs (owner, 2026-10-17).
 *
 * <p>The owner asked for FPL's rule by name: one change a week free, four points for each one beyond it.
 * The numbers are small enough to state here and be read against what he asked for.
 */
class FantasyTransferRulesTest {

    @Test
    @DisplayName("one change a gameweek is free")
    void theFirstChangeIsFree() {
        assertEquals(0, FantasyTransferRules.cost(0), "no change, no cost");
        assertEquals(0, FantasyTransferRules.cost(1), "the free one");
    }

    @Test
    @DisplayName("every change beyond the first costs four points")
    void extrasCostFourEach() {
        assertEquals(4, FantasyTransferRules.cost(2));
        assertEquals(8, FantasyTransferRules.cost(3));
        assertEquals(16, FantasyTransferRules.cost(5));
        assertEquals(56, FantasyTransferRules.cost(15), "a whole new squad, which is what it is worth");
    }

    @Test
    @DisplayName("standing still cannot earn points")
    void aNegativeChangeCountIsNotACredit() {
        assertEquals(0, FantasyTransferRules.cost(-1));
        assertEquals(0, FantasyTransferRules.cost(-99));
    }

    @Test
    @DisplayName("the free change is reported so the picker can say what is left")
    void freeRemaining() {
        assertEquals(1, FantasyTransferRules.freeRemaining(0));
        assertEquals(0, FantasyTransferRules.freeRemaining(1));
        assertEquals(0, FantasyTransferRules.freeRemaining(4));
        assertEquals(1, FantasyTransferRules.freeRemaining(-3));
    }
}
