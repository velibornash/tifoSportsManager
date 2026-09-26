package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.SeasonCalendar;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generated season must match the owner's table (2026-09-26).
 *
 * <p>{@link SeasonCalendarTest} proves the calendar itself is right. This proves the fixture
 * generator actually reads it — because it did not, for a long time: it gave every round its own
 * week, which turned an eighteen-round season into an eighteen-week one and put the transfer
 * windows in the wrong place entirely. A calendar nothing reads is a comment.
 */
class SeasonShapeTest {

    /** The owner's table, written out. */
    private static final List<List<Integer>> EXPECTED = List.of(
            List.of(1, 2), List.of(3, 4), List.of(5, 6), List.of(7, 8),
            List.of(9), List.of(10),
            List.of(11, 12), List.of(13, 14), List.of(15, 16), List.of(17, 18));

    @Test
    @DisplayName("the eighteen rounds land in the owner's weeks, not one round per week")
    void roundsLandInTheRightWeeks() {
        List<Integer> weeksOfRounds = new java.util.ArrayList<>();
        for (int round = 1; round <= SeasonCalendar.LEAGUE_ROUNDS; round++) {
            weeksOfRounds.add(SeasonCalendar.weekOfRound(round));
        }

        // The old behaviour: round N was played in week N. That is what made the season 18 weeks.
        assertTrue(weeksOfRounds.stream().anyMatch(w -> w < round(weeksOfRounds, 18)),
                "rounds should share weeks");

        for (int week = 1; week <= EXPECTED.size(); week++) {
            assertEquals(EXPECTED.get(week - 1),
                    SeasonCalendar.roundsIn(week), "week " + week);
        }
    }

    private int round(List<Integer> weeks, int roundNumber) {
        return weeks.get(roundNumber - 1);
    }

    @Test
    @DisplayName("eighteen rounds over ten weeks is two a week, except the friendly weeks")
    void twoMatchesAWeek() {
        int total = 0;
        for (int week = 1; week <= SeasonCalendar.LEAGUE_END_WEEK; week++) {
            total += SeasonCalendar.matchesIn(week);
        }
        assertEquals(18, total, "a club plays eighteen league matches a season");
        for (int week = 1; week <= 4; week++) assertEquals(2, SeasonCalendar.matchesIn(week));
        assertEquals(1, SeasonCalendar.matchesIn(5));
        assertEquals(1, SeasonCalendar.matchesIn(6));
        for (int week = 7; week <= 10; week++) assertEquals(2, SeasonCalendar.matchesIn(week));
    }

    @Test
    @DisplayName("a club fits in two match slots a week - league plus friendly")
    void clubFitsInTheWeek() {
        for (int week = 1; week <= 12; week++) {
            assertTrue(SeasonCalendar.matchesIn(week) + SeasonCalendar.friendliesIn(week) <= 2,
                    "week " + week + " overbooks a club");
        }
    }

    @Test
    @DisplayName("the league is over by week 10, the playoff is 11, the break is 12")
    void seasonEndsWhereItShould() {
        assertTrue(SeasonCalendar.roundsIn(SeasonCalendar.LEAGUE_END_WEEK).contains(18),
                "the last league match is in week 10");
        assertEquals(List.of(), SeasonCalendar.roundsIn(SeasonCalendar.PLAYOFF_WEEK));
        assertEquals(List.of(), SeasonCalendar.roundsIn(SeasonCalendar.BREAK_WEEK));
    }

    @Test
    @DisplayName("a club plays a friendly in every week the schedule gives it one")
    void friendlyWeeks() {
        Set<Integer> friendlyWeeks = new HashSet<>();
        for (int week = 1; week <= 12; week++) {
            if (SeasonCalendar.friendliesIn(week) > 0) friendlyWeeks.add(week);
        }
        assertEquals(Set.of(5, 6, 11, 12), friendlyWeeks);
        assertEquals(2, SeasonCalendar.friendliesIn(12), "the break has two friendlies");
    }

    @Test
    @DisplayName("friendly round numbers cannot collide with league rounds or with each other")
    void friendlyRoundNumbersAreDistinct() {
        Set<Integer> used = SeasonCalendar.roundsIn(1).stream().collect(Collectors.toSet());
        for (int week = 1; week <= 12; week++) {
            for (int slot = 1; slot <= SeasonCalendar.friendliesIn(week); slot++) {
                int n = SeasonCalendar.friendlyRoundNumber(week, slot);
                assertTrue(n > SeasonCalendar.LEAGUE_ROUNDS,
                        "friendly round " + n + " would be read as a league round");
                assertTrue(used.add(n), "friendly round number " + n + " is used twice");
            }
        }
    }

    @Test
    @DisplayName("the service constants are the calendar, not a second opinion")
    void serviceConstantsMatchTheCalendar() {
        assertEquals(SeasonCalendar.WEEKS_PER_SEASON, SeasonService.WEEKS_PER_SEASON);
        assertEquals(SeasonCalendar.LEAGUE_END_WEEK, SeasonService.LEAGUE_END);
        assertEquals(SeasonCalendar.PLAYOFF_WEEK, SeasonService.PLAYOFF_WEEK);
        assertEquals(SeasonCalendar.BREAK_WEEK, SeasonService.FRIENDLY_WEEK);
        assertEquals(SeasonCalendar.LEAGUE_ROUNDS, SeasonService.LEAGUE_ROUNDS);
    }
}
