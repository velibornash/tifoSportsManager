package org.example.footballmanager.newLogic.model;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * When national-team football is played, and what round each match is (owner, 2026-10-06).
 *
 * <p>Qualifying: week 6, days 2-6, one matchday a day. World Cup: week 12, round of 16 on day 1,
 * quarter-finals day 2, semi-finals day 4, third place and final on day 6.
 */
public final class NationalTournamentSchedule {

    private NationalTournamentSchedule() {
    }

    public static final int QUALIFYING_WEEK = SeasonCalendar.MIDSEASON_WEEK;
    public static final int TOURNAMENT_WEEK = SeasonCalendar.BREAK_WEEK;

    public static final int GROUPS = 8;
    public static final int GROUP_SIZE = 6;
    public static final int QUALIFYING_FIELD = GROUPS * GROUP_SIZE;
    public static final int GROUP_MATCHDAYS = GROUP_SIZE - 1;
    public static final int QUALIFY_PER_GROUP = 2;
    public static final int TOURNAMENT_FIELD = GROUPS * QUALIFY_PER_GROUP;

    public static final LocalTime DEFAULT_NATIONAL_KICKOFF = LocalTime.of(20, 0);

    public static final int[] QUALIFYING_DAYS = {2, 3, 4, 5, 6};

    public static final int ROUND_LAST_SIXTEEN = 1;
    public static final int ROUND_QUARTER_FINAL = 2;
    public static final int ROUND_SEMI_FINAL = 3;
    public static final int ROUND_THIRD_PLACE = 4;
    public static final int ROUND_FINAL = 5;

    public static final List<Integer> TOURNAMENT_ROUNDS = List.of(
            ROUND_LAST_SIXTEEN, ROUND_QUARTER_FINAL, ROUND_SEMI_FINAL, ROUND_THIRD_PLACE, ROUND_FINAL);

    /**
     * The rounds whose winners go into the next round.
     *
     * <p>Round of 16, quarter-finals, semi-finals - and nothing else. The third place is played between
     * the two who <i>lost</i> the semi-finals and has no winner that matters, so including it here
     * would carry a team that had just been knocked out into the final.
     */
    public static final List<Integer> FEED_FORWARD_ROUNDS = List.of(
            ROUND_LAST_SIXTEEN, ROUND_QUARTER_FINAL, ROUND_SEMI_FINAL);

    public static int qualifyingDay(int matchday) {
        if (matchday < 1 || matchday > QUALIFYING_DAYS.length) {
            return -1;
        }
        return QUALIFYING_DAYS[matchday - 1];
    }

    public static int tournamentDay(int round) {
        return switch (round) {
            case ROUND_LAST_SIXTEEN -> 1;
            case ROUND_QUARTER_FINAL -> 2;
            case ROUND_SEMI_FINAL -> 4;
            case ROUND_THIRD_PLACE, ROUND_FINAL -> 6;
            default -> -1;
        };
    }

    public static String roundLabel(int round) {
        return switch (round) {
            case ROUND_LAST_SIXTEEN -> "Round of 16";
            case ROUND_QUARTER_FINAL -> "Quarter-finals";
            case ROUND_SEMI_FINAL -> "Semi-finals";
            case ROUND_THIRD_PLACE -> "Third place";
            case ROUND_FINAL -> "Final";
            default -> "Round " + round;
        };
    }

    public static String groupCode(int groupIndex) {
        return String.valueOf((char) ('A' + groupIndex));
    }

    public static LocalTime kickoffFor(int dayNumber) {
        WeekTemplate.DaySlot slot = WeekTemplate.day(dayNumber);
        if (slot != null && slot.kickoff() != null) {
            return slot.kickoff();
        }
        return DEFAULT_NATIONAL_KICKOFF;
    }

    public static int kickoffHour(int dayNumber) {
        return kickoffFor(dayNumber).getHour();
    }

    public static LocalDateTime matchDate(LocalDateTime seasonStart, int week, int dayNumber) {
        return LocalDateTime.of(
                seasonStart.toLocalDate().plusWeeks(week - 1L).plusDays(dayNumber - 1L),
                kickoffFor(dayNumber));
    }
}