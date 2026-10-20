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
     * <p>Round of 16, quarter-finals, semi-finals - and nothing else.
     *
     * <p><b>Two rounds are deliberately absent, and both were added here by mistake at some point.</b>
     *
     * <ul>
     *   <li><b>The third place</b> is played between the two who <i>lost</i> the semi-finals and has no
     *       winner that matters. In this list it would be drawn like any other round and its winner
     *       carried into the final, so a side that had just been knocked out reached the final.</li>
     *   <li><b>The final</b> does not feed forward either - its winner is the champion, not a feeder.
     *       Putting it here made the loop draw it with {@code drawOneRound} and return, which meant the
     *       post-loop draw that also creates the <b>third-place play-off</b> was never reached: the owner
     *       specifies "the final and the third place are played on day 6", and the final appeared
     *       without the third place.</li>
     * </ul>
     *
     * <p>The final and the third place are created together, after this list has been walked, because
     * neither exists until the semi-finals have decided who the four contenders were.
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