package org.example.footballmanager.newLogic.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The season calendar (owner-defined, 2026-09-26).
 *
 * <p>This is a spec, so it is tested as one: the whole twelve weeks are checked rather than
 * sampled, because a fixture that lands in the wrong week is invisible in an aggregate and ruins a
 * season. There are no months anywhere in it — a manager's season is twelve real weeks, so roughly
 * four run in a year, and showing "March" would imply a season lasts five months of their life.
 */
class SeasonCalendarTest {

    @Test
    @DisplayName("a season is twelve weeks, about four to a year")
    void seasonLength() {
        assertEquals(12, SeasonCalendar.WEEKS_PER_SEASON);
        assertEquals(4, SeasonCalendar.SEASONS_PER_YEAR);
    }

    @Test
    @DisplayName("ten clubs playing each other twice take eighteen league rounds")
    void eighteenRounds() {
        assertEquals(18, SeasonCalendar.LEAGUE_ROUNDS);
    }

    @Test
    @DisplayName("every round is played exactly once across the season")
    void everyRoundPlayedOnce() {
        List<Integer> seen = new ArrayList<>();
        for (int week = 1; week <= SeasonCalendar.WEEKS_PER_SEASON; week++) {
            seen.addAll(SeasonCalendar.roundsIn(week));
        }
        assertEquals(SeasonCalendar.LEAGUE_ROUNDS, seen.size(),
                "expected 18 rounds across the season, got " + seen.size() + ": " + seen);
        assertEquals(18, seen.stream().distinct().count(),
                "a round was played twice or skipped: " + seen);
        for (int round = 1; round <= 18; round++) {
            assertTrue(seen.contains(round), "round " + round + " is never played");
        }
    }

    @Test
    @DisplayName("two rounds a week, except in weeks 5 and 6 where a friendly takes the slot")
    void twoRoundsExceptMidSeason() {
        for (int week = 1; week <= 4; week++) {
            assertEquals(2, SeasonCalendar.matchesIn(week), "week " + week);
        }
        assertEquals(1, SeasonCalendar.matchesIn(5), "week 5 has a friendly in the second slot");
        assertEquals(1, SeasonCalendar.matchesIn(6), "week 6 has a friendly in the second slot");
        for (int week = 7; week <= 10; week++) {
            assertEquals(2, SeasonCalendar.matchesIn(week), "week " + week);
        }
        assertEquals(0, SeasonCalendar.matchesIn(11), "week 11 is playoffs");
        assertEquals(0, SeasonCalendar.matchesIn(12), "week 12 is the break");
    }

    @Test
    @DisplayName("the schedule matches the owner's table week for week")
    void matchesTheOwnersTable() {
        assertEquals(List.of(1, 2), SeasonCalendar.roundsIn(1));
        assertEquals(List.of(3, 4), SeasonCalendar.roundsIn(2));
        assertEquals(List.of(5, 6), SeasonCalendar.roundsIn(3));
        assertEquals(List.of(7, 8), SeasonCalendar.roundsIn(4));
        assertEquals(List.of(9), SeasonCalendar.roundsIn(5));
        assertEquals(List.of(10), SeasonCalendar.roundsIn(6));
        assertEquals(List.of(11, 12), SeasonCalendar.roundsIn(7));
        assertEquals(List.of(13, 14), SeasonCalendar.roundsIn(8));
        assertEquals(List.of(15, 16), SeasonCalendar.roundsIn(9));
        assertEquals(List.of(17, 18), SeasonCalendar.roundsIn(10));
    }

    @Test
    @DisplayName("the mid-season window is weeks 5 and 6")
    void midSeasonWindow() {
        assertFalse(SeasonCalendar.isWindowOpen(4));
        assertTrue(SeasonCalendar.isWindowOpen(5));
        assertTrue(SeasonCalendar.isWindowOpen(6));
        assertFalse(SeasonCalendar.isWindowOpen(7));
        assertTrue(SeasonCalendar.isMidSeasonWindow(5));
        assertTrue(SeasonCalendar.isMidSeasonWindow(6));
    }

    @Test
    @DisplayName("the end-of-season window is weeks 11 and 12")
    void endOfSeasonWindow() {
        for (int week = 7; week <= 10; week++) {
            assertFalse(SeasonCalendar.isWindowOpen(week), "week " + week + " is closed");
        }
        assertTrue(SeasonCalendar.isWindowOpen(11));
        assertTrue(SeasonCalendar.isWindowOpen(12));
        assertTrue(SeasonCalendar.isEndOfSeasonWindow(11));
        assertTrue(SeasonCalendar.isEndOfSeasonWindow(12));
    }

    @Test
    @DisplayName("the league ends in week 10, playoffs are week 11, the break is week 12")
    void seasonShape() {
        assertEquals(10, SeasonCalendar.LEAGUE_END_WEEK);
        assertEquals(11, SeasonCalendar.PLAYOFF_WEEK);
        assertEquals(12, SeasonCalendar.BREAK_WEEK);
    }

    @Test
    @DisplayName("friendly slots appear in weeks 5, 6, 11 and 12 and nowhere else")
    void friendlySlots() {
        assertEquals(0, SeasonCalendar.friendlySlots(1));
        assertEquals(1, SeasonCalendar.friendlySlots(5));
        assertEquals(1, SeasonCalendar.friendlySlots(6));
        assertEquals(0, SeasonCalendar.friendlySlots(7));
        // Week 11 offers two, but a club in the playoff only keeps one of them.
        assertEquals(2, SeasonCalendar.friendlySlots(11, false));
        assertEquals(1, SeasonCalendar.friendlySlots(11, true));
        assertEquals(2, SeasonCalendar.friendlySlots(12));
    }

    @Test
    @DisplayName("a club's fixtures fit inside its two weekly slots")
    void fixturesFitTheWeek() {
        for (int week = 1; week <= SeasonCalendar.WEEKS_PER_SEASON; week++) {
            for (boolean inPlayoff : new boolean[] { false, true }) {
                int total = SeasonCalendar.matchesIn(week)
                        + SeasonCalendar.friendlySlots(week, inPlayoff);
                assertTrue(total <= 2,
                        "week " + week + " would need " + total + " matches; only two slots exist");
            }
        }
    }

    @Test
    @DisplayName("a round can be traced back to its week")
    void roundTracesToWeek() {
        for (int round = 1; round <= 18; round++) {
            int week = SeasonCalendar.weekOfRound(round);
            assertTrue(week >= 1 && week <= 10,
                    "round " + round + " maps to week " + week + ", which is not a league week");
            assertTrue(SeasonCalendar.roundsIn(week).contains(round));
        }
        assertEquals(-1, SeasonCalendar.weekOfRound(19), "there is no round 19");
    }

    @Test
    @DisplayName("every week is describable for the viewer")
    void everyWeekIsDescribable() {
        for (int week = 1; week <= SeasonCalendar.WEEKS_PER_SEASON; week++) {
            String label = SeasonCalendar.describe(week);
            assertTrue(label != null && !label.isBlank(), "week " + week + " has no label");
        }
        assertEquals(SeasonCalendar.WEEKS_PER_SEASON, SeasonCalendar.asRows().size());
    }
}
