package org.tifo.footballmanager.newLogic.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The label the owner reads on his dashboard.
 *
 * <p>The defect was found by looking at the screen, not the code: he reported two different matches on
 * the same day, and a day 7 fixture that had not been played yet. Both were one missing word.
 */
class MatchDTOTest {

    private static final LocalDateTime KICKOFF = LocalDateTime.of(2026, 10, 25, 19, 0);

    @Test
    @DisplayName("the label names the week, because a season is twelve weeks of seven days")
    void theLabelNamesTheWeek() {
        assertEquals("Season 1 · Week 3 · Day 3 · 19:00",
                MatchDTO.buildSeasonDayLabel(1, 3, 3, KICKOFF));
    }

    @Test
    @DisplayName("two matches twelve days apart are not the same slot")
    void twoWeeksApartDoNotShareALabel() {
        // Week 1 day 3 and week 3 day 3 both used to read "Season 1 · Day 3 · 19:00", which is what
        // the owner reported as two different matches on the same day.
        String week1 = MatchDTO.buildSeasonDayLabel(1, 1, 3, KICKOFF);
        String week3 = MatchDTO.buildSeasonDayLabel(1, 3, 3, KICKOFF);

        assertNotEquals(week1, week3,
                "two matches in different weeks still share a label, so they read as one day");
        assertTrue(week1.contains("Week 1"), "the label does not name the week it is in");
        assertTrue(week3.contains("Week 3"), "the label does not name the week it is in");
    }

    @Test
    @DisplayName("the day alone is ambiguous, and the label is not")
    void dayAloneIsNotEnough() {
        // The reason the week had to be added: "Day 3" on its own is one of eighty-four slots in a
        // twelve-week season.
        assertEquals(12 * 7, 84);
        assertTrue(MatchDTO.buildSeasonDayLabel(1, 4, 3, KICKOFF).contains("Week 4"));
    }

    @Test
    @DisplayName("an unknown week is left out rather than rendered as zero")
    void anUnknownWeekIsLeftOut() {
        // The rule the label already followed for the day, applied to the week as well. "Week 0" would
        // be a lie; a missing week should simply not be claimed.
        assertEquals("Season 1 · Day 3 · 19:00",
                MatchDTO.buildSeasonDayLabel(1, null, 3, KICKOFF));
    }

    @Test
    @DisplayName("without a season there is no label at all")
    void noSeasonNoLabel() {
        assertNull(MatchDTO.buildSeasonDayLabel(null, 3, 3, KICKOFF));
    }
}
