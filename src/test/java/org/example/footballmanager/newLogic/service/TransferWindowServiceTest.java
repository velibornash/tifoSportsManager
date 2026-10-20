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
    @DisplayName("a season is 12 weeks, and the service agrees with the calendar")
    void seasonShape() {
        assertEquals(12, TransferWindowService.SEASON_WEEKS);
        assertEquals(10, TransferWindowService.LEAGUE_END);
        assertEquals(11, TransferWindowService.PLAYOFF_WEEK);
    }

    @Test
    @DisplayName("windows are weeks 5-6 and weeks 11-12, exactly as the calendar says")
    void windowsAreWhereTheyShouldBe() {
        for (int w = 1; w <= 4; w++) {
            assertEquals(Window.CLOSED, TransferWindowService.windowForWeek(w), "week " + w);
        }
        for (int w = 5; w <= 6; w++) {
            assertEquals(Window.SUMMER, TransferWindowService.windowForWeek(w), "week " + w);
        }
        for (int w = 7; w <= 10; w++) {
            assertEquals(Window.CLOSED, TransferWindowService.windowForWeek(w), "week " + w);
        }
        for (int w = 11; w <= 12; w++) {
            assertEquals(Window.WINTER, TransferWindowService.windowForWeek(w), "week " + w);
        }
        for (int w = 13; w <= 45; w++) {
            assertEquals(Window.CLOSED, TransferWindowService.windowForWeek(w), "week " + w);
        }
    }

    @Test
    @DisplayName("the end-of-season window covers the playoffs and the break")
    void endWindowCoversPlayoffsAndBreak() {
        assertEquals(Window.WINTER, TransferWindowService.windowForWeek(TransferWindowService.PLAYOFF_WEEK),
                "a club must be able to sign during the playoffs - that is how a season is won");
        assertTrue(TransferWindowService.WINTER_OPEN > TransferWindowService.LEAGUE_END,
                "the end-of-season window opens once the league is over");
    }

    @Test
    @DisplayName("a registered player cannot move while the window is shut")
    void closedWindowRefusesPermanentMoves() {
        // Weeks 1-4, 7-8 and everything after 11 are shut.
        for (int week : new int[] { 1, 2, 3, 4, 7, 8, 9, 10, 20 }) {
            TransferWindowService.Decision d =
                    TransferWindowService.decide(TransferWindowService.windowForWeek(week), Kind.PERMANENT);
            assertFalse(d.permitted(), "week " + week + " is shut");
            assertTrue(d.reason().contains("week " + TransferWindowService.SUMMER_OPEN),
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
    @DisplayName("a released player can be signed at any time")
    void releasedPlayersIgnoreTheWindow() {
        for (int week = 1; week <= 45; week++) {
            assertTrue(TransferWindowService.decide(
                            TransferWindowService.windowForWeek(week), Kind.RELEASED).permitted(),
                    "week " + week + ": a released man must be able to find a club");
        }
    }

    @Test
    @DisplayName("a player with no asking price can be approached, but not bid on")
    void unlistedPlayersCanBeApproached() {
        for (int week = 1; week <= 45; week++) {
            TransferWindowService.Decision d = TransferWindowService.decide(
                    TransferWindowService.windowForWeek(week), Kind.UNLISTED);
            assertTrue(d.permitted(), "week " + week + ": no asking price means he is not for sale");
            assertTrue(d.reason().toLowerCase().contains("no asking price"),
                    "and the manager must be told that he cannot be bid on: " + d.reason());
        }
    }

    @Test
    @DisplayName("a loan IN is not exempt - taking a player in April is a decision, not a right")
    void loanInIsNotExempt() {
        for (int week = 7; week <= 10; week++) {
            assertFalse(TransferWindowService.decide(
                            TransferWindowService.windowForWeek(week), Kind.LOAN_IN).permitted(),
                    "week " + week + ": no league lets a club take a player on loan in April");
        }
    }

    @Test
    @DisplayName("permanent moves are allowed while a window is open")
    void openWindowPermitsPermanentMoves() {
        for (int week : new int[] { 5, 6, 11, 12 }) {
            assertTrue(TransferWindowService.decide(
                            TransferWindowService.windowForWeek(week), Kind.PERMANENT).permitted(),
                    "week " + week + " is open");
        }
    }

    @Test
    @DisplayName("every kind of move is either allowed or has a reason")
    void everyKindIsAnswered() {
        for (Kind kind : Kind.values()) {
            for (int week = 1; week <= TransferWindowService.SEASON_WEEKS; week++) {
                TransferWindowService.Decision d = TransferWindowService.decide(
                        TransferWindowService.windowForWeek(week), kind, week);
                assertFalse(d.reason() == null || d.reason().isBlank(),
                        kind + " at week " + week + " has no explanation");
            }
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
