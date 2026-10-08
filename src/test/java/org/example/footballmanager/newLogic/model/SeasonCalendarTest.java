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
    @DisplayName("two rounds a week for nine weeks, and no league at all in weeks 6 and 12")
    void twoRoundsExceptTheTwoNonLeagueWeeks() {
        for (int week = 1; week <= 5; week++) {
            assertEquals(2, SeasonCalendar.matchesIn(week), "week " + week);
        }
        // Week 6 is midseason, given over to national-team qualifiers. Not a gap in the calendar -
        // a week with nothing in it because something else is in it.
        assertEquals(0, SeasonCalendar.matchesIn(6), "week 6 must have no league football");
        for (int week = 7; week <= 10; week++) {
            assertEquals(2, SeasonCalendar.matchesIn(week), "week " + week);
        }
        assertEquals(0, SeasonCalendar.matchesIn(12), "week 12 is the World Cup and has no league");
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
        // Round 10 moved from week 6 into week 5 so week 6 could be left to national-team
        // qualifiers. The first half of the season now ends inside week 5.
        assertEquals(List.of(9, 10), SeasonCalendar.roundsIn(5));
        assertEquals(List.of(), SeasonCalendar.roundsIn(6), "week 6 has no league football");
        assertEquals(List.of(11, 12), SeasonCalendar.roundsIn(7));
        assertEquals(List.of(13, 14), SeasonCalendar.roundsIn(8));
        assertEquals(List.of(15, 16), SeasonCalendar.roundsIn(9));
        assertEquals(List.of(17, 18), SeasonCalendar.roundsIn(10));
    }

    @Test
    @DisplayName("the whole twelve weeks match the owner's table, slot by slot")
    void theOwnersTableVerbatim() {
        // Written out longhand rather than derived, on purpose: a test that recomputes the table
        // from the same constant it is checking proves nothing. This is the owner's instruction
        // transcribed, and it is the thing a change has to keep matching.
        //
        // **Four slots per week, not two** (owner rule 2026-10-06): day 1, day 3, day 5, day 7. This
        // transcription was written when a week had two slots, so every league round below sat at
        // slot 1 or 2 and the friendly slots were not mentioned at all — which meant the table it
        // claimed to pin verbatim was not the table in the code, and three tests in this class said so.
        // The rounds are now at slots 2 and 4, with friendly opportunities at 1 and 3.
        assertEquals("FRIENDLY", slotLabel(1, 1));
        assertEquals("r1", slotLabel(1, 2));
        assertEquals("FRIENDLY", slotLabel(1, 3));
        assertEquals("r2", slotLabel(1, 4));
        assertEquals("r3", slotLabel(2, 2));
        assertEquals("r4", slotLabel(2, 4));
        assertEquals("r5", slotLabel(3, 2));
        assertEquals("r6", slotLabel(3, 4));
        assertEquals("r7", slotLabel(4, 2));
        assertEquals("r8", slotLabel(4, 4));

        // Round 10 used to be week 6's second slot. The owner moved it into week 5 so that week 6
        // could be left entirely to national-team qualifiers, which is why the first half now ends
        // on round 10 in a single week and the mid-season window opens the moment that round is done.
        assertEquals("r9", slotLabel(5, 2));
        assertEquals("r10", slotLabel(5, 4));
        // Week 6 is four friendly slots: no league at all, and the qualifiers are not built yet.
        for (int slot = 1; slot <= SeasonCalendar.SLOTS_PER_WEEK; slot++) {
            assertEquals("FRIENDLY", slotLabel(6, slot), "week 6 slot " + slot);
        }

        assertEquals("r11", slotLabel(7, 2));
        assertEquals("r12", slotLabel(7, 4));
        assertEquals("r13", slotLabel(8, 2));
        assertEquals("r14", slotLabel(8, 4));
        assertEquals("r15", slotLabel(9, 2));
        assertEquals("r16", slotLabel(9, 4));
        assertEquals("r17", slotLabel(10, 2));
        assertEquals("r18", slotLabel(10, 4));

        // Week 11: the playoff takes day 3, so only the clubs not in it may play then.
        assertEquals("FRIENDLY", slotLabel(11, 1));
        assertEquals("FRIENDLY_IF_NOT_IN_PLAYOFF", slotLabel(11, 2));
        assertEquals("FRIENDLY", slotLabel(11, 3));
        assertEquals("FRIENDLY", slotLabel(11, 4));
        // The break is four friendly slots and nothing else.
        for (int slot = 1; slot <= SeasonCalendar.SLOTS_PER_WEEK; slot++) {
            assertEquals("FRIENDLY", slotLabel(12, slot), "week 12 slot " + slot);
        }
    }

    /**
     * The slots are the four match days, and that is what the whole table is written against.
     *
     * <p>Stated here because the transcription above silently assumed something else for a long time:
     * if a "slot" were a weekday rather than a day number, moving a fixture would move the season.
     */
    @Test
    @DisplayName("the four slots are day 1, day 3, day 5 and day 7 of the week")
    void slotsAreFourMatchDays() {
        assertEquals(4, SeasonCalendar.SLOTS_PER_WEEK);
        assertEquals(1, SeasonCalendar.dayForSlot(1));
        assertEquals(3, SeasonCalendar.dayForSlot(2));
        assertEquals(5, SeasonCalendar.dayForSlot(3));
        assertEquals(7, SeasonCalendar.dayForSlot(4));
        assertEquals(-1, SeasonCalendar.dayForSlot(0), "slot 0 is not a day of the week");
        assertEquals(-1, SeasonCalendar.dayForSlot(5), "and neither is slot 5");
    }

    /** A short, readable label for a slot, for the assertions above. */
    private String slotLabel(int week, int slot) {
        SeasonCalendar.WeekSlot s = SeasonCalendar.slot(week, slot);
        return s.kind() == SeasonCalendar.SlotKind.LEAGUE
                ? "r" + s.leagueRound()
                : s.kind().name();
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
    @DisplayName("friendly slots appear wherever a week has no league round")
    void friendlySlots() {
        // Every league week still offers two — day 1 and day 5 — because a friendly is an option, not
        // an obligation, and the owner's four-slot week leaves room for it beside two rounds.
        assertEquals(2, SeasonCalendar.friendlySlots(1));
        assertEquals(2, SeasonCalendar.friendlySlots(5), "week 5 is two league rounds and still two friendlies");
        assertEquals(4, SeasonCalendar.friendlySlots(6), "week 6 is four friendly slots now");
        assertEquals(2, SeasonCalendar.friendlySlots(7));
        // Week 11 offers four, but a club in the playoff loses the day-3 slot to it.
        assertEquals(4, SeasonCalendar.friendlySlots(11, false));
        assertEquals(3, SeasonCalendar.friendlySlots(11, true));
        assertEquals(4, SeasonCalendar.friendlySlots(12));
    }

    @Test
    @DisplayName("a club's fixtures fit inside the slots its week has")
    void fixturesFitTheWeek() {
        for (int week = 1; week <= SeasonCalendar.WEEKS_PER_SEASON; week++) {
            for (boolean inPlayoff : new boolean[] { false, true }) {
                int total = SeasonCalendar.matchesIn(week)
                        + SeasonCalendar.friendlySlots(week, inPlayoff);
                // Four, because that is how many match moments a week has (owner rule 2026-10-06).
                // This said two, which is the bound from before the four-slot week — and it failed for
                // the two weeks that are nothing but friendlies, which is the calendar working.
                assertTrue(total <= SeasonCalendar.SLOTS_PER_WEEK,
                        "week " + week + " would need " + total + " matches; only "
                                + SeasonCalendar.SLOTS_PER_WEEK + " slots exist");
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
