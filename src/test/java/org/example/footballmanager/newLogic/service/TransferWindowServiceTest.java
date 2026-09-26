package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.service.TransferWindowService.Kind;
import org.example.footballmanager.newLogic.service.TransferWindowService.Window;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 3.3 — transfer windows.
 *
 * <p>There was no window enforcement anywhere before this. A club could sign a striker in week 15
 * with no rule and no reason, which removed the largest piece of scheduling tension in a football
 * management game. These are pure rules with no database behind them, so they are tested as pure
 * rules — which also means they are exhaustively checkable.
 */
class TransferWindowServiceTest {

    @Test
    @DisplayName("the summer window is weeks 1-6 and the winter window is 10-12")
    void windowsAreWhereTheyShouldBe() {
        for (int w = 1; w <= 6; w++) {
            assertEquals(Window.SUMMER, TransferWindowService.windowForWeek(w), "week " + w);
        }
        for (int w = 7; w <= 9; w++) {
            assertEquals(Window.CLOSED, TransferWindowService.windowForWeek(w), "week " + w);
        }
        for (int w = 10; w <= 12; w++) {
            assertEquals(Window.WINTER, TransferWindowService.windowForWeek(w), "week " + w);
        }
        for (int w = 13; w <= 45; w++) {
            assertEquals(Window.CLOSED, TransferWindowService.windowForWeek(w), "week " + w);
        }
    }

    @Test
    @DisplayName("a registered player cannot move while the window is shut")
    void closedWindowRefusesPermanentMoves() {
        for (int week = 7; week <= 9; week++) {
            TransferWindowService.Decision d =
                    TransferWindowService.decide(TransferWindowService.windowForWeek(week), Kind.PERMANENT);
            assertFalse(d.permitted(), "week " + week + " is shut");
            assertTrue(d.reason().contains("week " + TransferWindowService.WINTER_OPEN),
                    "the refusal must say when it reopens: " + d.reason());
        }
    }

    @Test
    @DisplayName("a free agent can be signed at any time - nobody is being disappointed")
    void freeAgentsIgnoreTheWindow() {
        for (int week = 1; week <= 45; week++) {
            TransferWindowService.Decision d =
                    TransferWindowService.decide(TransferWindowService.windowForWeek(week), Kind.FREE_AGENT);
            assertTrue(d.permitted(), "week " + week + ": a free agent must always be signable");
        }
    }

    @Test
    @DisplayName("a loan recall is the parent club's right and is never blocked")
    void loanRecallIgnoresTheWindow() {
        for (int week = 1; week <= 45; week++) {
            assertTrue(TransferWindowService.decide(
                            TransferWindowService.windowForWeek(week), Kind.LOAN_RECALL).permitted(),
                    "week " + week + ": a recall is exercised against the player's will");
        }
    }

    @Test
    @DisplayName("a loan IN is not exempt - taking a player in April is a decision, not a right")
    void loanInIsNotExempt() {
        for (int week = 13; week <= 45; week++) {
            assertFalse(TransferWindowService.decide(
                            TransferWindowService.windowForWeek(week), Kind.LOAN_IN).permitted(),
                    "week " + week + ": no league lets a club take a player on loan in April");
        }
    }

    @Test
    @DisplayName("permanent moves are allowed while a window is open")
    void openWindowPermitsPermanentMoves() {
        for (int week : new int[] { 1, 3, 6, 10, 11, 12 }) {
            assertTrue(TransferWindowService.decide(
                            TransferWindowService.windowForWeek(week), Kind.PERMANENT).permitted(),
                    "week " + week + " is open");
        }
    }

    @Test
    @DisplayName("an unknown week is closed rather than open")
    void unknownWeekIsClosed() {
        assertEquals(Window.CLOSED, TransferWindowService.windowForWeek(null),
                "no week is not the same as any week, and 'closed' is the safe answer");
    }

    @Test
    @DisplayName("every refusal carries a reason the manager can act on")
    void refusalsExplainThemselves() {
        for (Kind kind : Kind.values()) {
            for (int week = 1; week <= 45; week++) {
                TransferWindowService.Decision d =
                        TransferWindowService.decide(TransferWindowService.windowForWeek(week), kind);
                assertFalse(d.reason() == null || d.reason().isBlank(),
                        kind + " at week " + week + " has no explanation");
            }
        }
    }
}
