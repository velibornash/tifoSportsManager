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
    /**
     * The owner's season shape, week by week (rule of 2026-09-27).
     *
     * <p>Week 5 now carries both round 9 and round 10, and week 6 carries none: it is midseason,
     * reserved for national-team qualifiers played day by day. Weeks 11 and 12 have no league rounds
     * either — week 11's day-3 slot is the playoff and week 12 is the World Cup.
     */
    private static final List<List<Integer>> EXPECTED = List.of(
            List.of(1, 2), List.of(3, 4), List.of(5, 6), List.of(7, 8),
            List.of(9, 10), List.of(),
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
        assertEquals(2, SeasonCalendar.matchesIn(5), "week 5 ends the first half, both slots league");
        assertEquals(0, SeasonCalendar.matchesIn(6), "week 6 is midseason, no league");
        for (int week = 7; week <= 10; week++) assertEquals(2, SeasonCalendar.matchesIn(week));
    }

    @Test
    @DisplayName("a club fits in two match slots a week - league plus friendly")
    void clubFitsInTheWeek() {
        for (int week = 1; week <= 12; week++) {
            for (boolean inPlayoff : new boolean[] { false, true }) {
                assertTrue(SeasonCalendar.matchesIn(week)
                                + SeasonCalendar.friendlySlots(week, inPlayoff) <= 2,
                        "week " + week + " overbooks a club");
            }
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
    @DisplayName("a club can ask for a friendly in weeks 6, 11 and 12 only")
    void friendlyWeeks() {
        Set<Integer> friendlyWeeks = new HashSet<>();
        for (int week = 1; week <= 12; week++) {
            if (SeasonCalendar.friendlySlots(week) > 0) friendlyWeeks.add(week);
        }
        // Week 5 dropped out of this set when round 10 moved into it, so the first half of the
        // season now has no friendly at all. The remaining three are the two non-league weeks and
        // the playoff week.
        assertEquals(Set.of(6, 11, 12), friendlyWeeks);
        assertEquals(2, SeasonCalendar.friendlySlots(6), "midseason has two friendly slots");
        assertEquals(2, SeasonCalendar.friendlySlots(12), "the World Cup week has two");
    }

    @Test
    @DisplayName("friendly round numbers cannot collide with league rounds or with each other")
    void friendlyRoundNumbersAreDistinct() {
        Set<Integer> used = SeasonCalendar.roundsIn(1).stream().collect(Collectors.toSet());
        for (int week = 1; week <= 12; week++) {
            for (int slot = 1; slot <= 2; slot++) {
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
