package org.example.footballmanager.newLogic.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The season calendar (owner-defined, 2026-09-26).
 *
 * <p>A season is <b>12 weeks</b> and there is no month anywhere in it. A manager's season lasts
 * twelve real weeks, so roughly four run in a year — which is the whole reason the game does not use
 * a January-to-May calendar. Displaying "Week 7" and "Week 12" is honest; displaying "March" would
 * imply a season lasts five months of the player's life, which it does not.
 *
 * <h2>The schedule</h2>
 * Ten clubs play each other twice: eighteen league matches. Two are played a week, except in weeks 5
 * and 6 where a friendly takes the second slot.
 *
 * <pre>
 *   week  1   rounds  1,  2
 *   week  2   rounds  3,  4
 *   week  3   rounds  5,  6
 *   week  4   rounds  7,  8
 *   week  5   round   9     + friendly   | mid-season window OPENS after round 9
 *   week  6   round  10     + friendly   | mid-season window CLOSES at end of week
 *   week  7   rounds 11, 12
 *   week  8   rounds 13, 14
 *   week  9   rounds 15, 16
 *   week 10   rounds 17, 18
 *   week 11   playoff (7th/8th) or friendly   | window OPENS at start of week
 *   week 12   two friendlies                    | window CLOSES at end of week
 * </pre>
 *
 * <p>Everything that needs to know "what is happening in week N" reads this, so the fixture
 * generator, the transfer windows and the viewer cannot drift apart.
 */
public final class SeasonCalendar {

    private SeasonCalendar() { }

    /** A season is twelve weeks. */
    public static final int WEEKS_PER_SEASON = 12;

    /** Roughly four seasons to a year, at twelve real weeks each. */
    public static final int SEASONS_PER_YEAR = 4;

    // --- windows ---
    /** The mid-season window opens once round 9 is played, in week 5. */
    public static final int MID_WINDOW_OPEN = 5;
    public static final int MID_WINDOW_CLOSE = 6;

    /** The end-of-season window opens at the start of week 11 and closes at the end of week 12. */
    public static final int END_WINDOW_OPEN = 11;
    public static final int END_WINDOW_CLOSE = 12;

    /** The last league match is in week 10. */
    public static final int LEAGUE_END_WEEK = 10;

    /** Playoffs are week 11: the clubs that did not qualify play friendlies instead. */
    public static final int PLAYOFF_WEEK = 11;

    /** Week 12 is the mid-season break: friendlies only. */
    public static final int BREAK_WEEK = 12;

    /** League rounds a ten-club double round-robin takes. */
    public static final int LEAGUE_ROUNDS = 18;

    /**
     * Which league rounds are played in which week.
     *
     * <p>Weeks 1-4 and 7-10 take two rounds each; weeks 5 and 6 take one each, because a friendly
     * occupies the second slot. That is 8 + 1 + 1 + 8 = 18.
     */
    private static final Map<Integer, List<Integer>> ROUNDS_BY_WEEK = build();

    private static Map<Integer, List<Integer>> build() {
        Map<Integer, List<Integer>> m = new LinkedHashMap<>();
        m.put(1, List.of(1, 2));
        m.put(2, List.of(3, 4));
        m.put(3, List.of(5, 6));
        m.put(4, List.of(7, 8));
        m.put(5, List.of(9));
        m.put(6, List.of(10));
        m.put(7, List.of(11, 12));
        m.put(8, List.of(13, 14));
        m.put(9, List.of(15, 16));
        m.put(10, List.of(17, 18));
        m.put(11, List.of());          // playoffs, or friendlies for those who did not qualify
        m.put(12, List.of());          // the break: friendlies only
        return m;
    }

    /** The league rounds played in a given week, in order. */
    public static List<Integer> roundsIn(int week) {
        return ROUNDS_BY_WEEK.getOrDefault(week, List.of());
    }

    /** How many league matches a club plays in a given week. */
    public static int matchesIn(int week) {
        return roundsIn(week).size();
    }

    /** How many friendlies a club plays in a given week. */
    public static int friendliesIn(int week) {
        return switch (week) {
            // Weeks 5, 6 and 11 have one friendly in the second slot; week 12 is friendlies only.
            case 5, 6, 11 -> 1;
            case 12 -> 2;
            default -> 0;
        };
    }

    /** Whether a transfer window is open in a given week. */
    public static boolean isWindowOpen(int week) {
        return (week >= MID_WINDOW_OPEN && week <= MID_WINDOW_CLOSE)
                || (week >= END_WINDOW_OPEN && week <= END_WINDOW_CLOSE);
    }

    /** Whether a given week is the mid-season window. */
    public static boolean isMidSeasonWindow(int week) {
        return week >= MID_WINDOW_OPEN && week <= MID_WINDOW_CLOSE;
    }

    /** Whether a given week is the end-of-season window. */
    public static boolean isEndOfSeasonWindow(int week) {
        return week >= END_WINDOW_OPEN && week <= END_WINDOW_CLOSE;
    }

    /** A short label for the week, for the viewer and the transfer centre. */
    public static String describe(int week) {
        if (week < 1 || week > WEEKS_PER_SEASON) return "Out of season";
        if (isEndOfSeasonWindow(week)) {
            return week == PLAYOFF_WEEK
                    ? "Playoffs and the end-of-season window opens"
                    : "Mid-season break, window closes tonight";
        }
        if (isMidSeasonWindow(week)) {
            return week == MID_WINDOW_OPEN
                    ? "Mid-season window opens after this round"
                    : "Mid-season window closes tonight";
        }
        if (week == PLAYOFF_WEEK) return "Playoffs";
        if (week == BREAK_WEEK) return "Mid-season break";
        return "League, " + matchesIn(week) + (matchesIn(week) == 1 ? " match" : " matches");
    }

    /** The whole season as rows, for a calendar view. */
    public static List<Map<String, Object>> asRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int week = 1; week <= WEEKS_PER_SEASON; week++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("week", week);
            row.put("leagueRounds", roundsIn(week));
            row.put("friendlies", friendliesIn(week));
            row.put("windowOpen", isWindowOpen(week));
            row.put("label", describe(week));
            rows.add(row);
        }
        return rows;
    }

    /**
     * A round number for the nth friendly round of a week.
     *
     * <p>Friendlies share a week with league matches and, in the break, with each other, so they
     * cannot borrow a league round number. These live well above the eighteen league rounds to
     * leave room for both friendly rounds in week 12.
     */
    public static int friendlyRoundNumber(int week, int slot) {
        return 1000 + week * 10 + slot;
    }

    /**
     * The week a league round is played in, or -1 if there is no such round.
     *
     * <p>Used when converting an existing fixture list, where a round was previously assumed to be
     * its own week.
     */
    public static int weekOfRound(int round) {
        for (Map.Entry<Integer, List<Integer>> e : ROUNDS_BY_WEEK.entrySet()) {
            if (e.getValue().contains(round)) return e.getKey();
        }
        return -1;
    }
}
