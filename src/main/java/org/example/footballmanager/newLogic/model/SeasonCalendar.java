package org.example.footballmanager.newLogic.model;

import java.util.ArrayList;
import java.util.Arrays;
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
 * <h2>Every week has two slots</h2>
 * Thursday and Sunday. Anything that is not a scheduled fixture is an <b>option</b>, not an
 * obligation: a club is not handed a friendly, it asks for one and the other club may refuse.
 *
 * <pre>
 *   week  1   Thu round  1   Sun round  2
 *   week  2   Thu round  3   Sun round  4
 *   week  3   Thu round  5   Sun round  6
 *   week  4   Thu round  7   Sun round  8
 *   week  5   Thu round  9   Sun friendly   | mid-season window OPENS after round 9
 *   week  6   Thu friendly  Sun round 10   | mid-season window CLOSES at end of week
 *   week  7   Thu round 11   Sun round 12
 *   week  8   Thu round 13   Sun round 14
 *   week  9   Thu round 15   Sun round 16
 *   week 10   Thu round 17   Sun round 18
 *   week 11   Thu playoff or friendly, Sun friendly   | window OPENS at start of week
 *   week 12   Thu friendly  Sun friendly               | window CLOSES at end of week
 * </pre>
 *
 * <p>Ten clubs play each other twice: eighteen league matches. Two a week, except in weeks 5 and 6
 * where a friendly slot takes the place of a league round.
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

    /** Playoffs are week 11: the clubs in them play that instead of a friendly. */
    public static final int PLAYOFF_WEEK = 11;

    /** Week 12 is the mid-season break: two friendly slots and nothing else. */
    public static final int BREAK_WEEK = 12;

    /** League rounds a ten-club double round-robin takes. */
    public static final int LEAGUE_ROUNDS = 18;

    /** Every week has two match slots: Thursday and Sunday. */
    public static final int SLOTS_PER_WEEK = 2;

    /** What occupies a slot. */
    public enum SlotKind {
        /** A league round. */
        LEAGUE,
        /** A relegation playoff. */
        PLAYOFF,
        /** A club may request a friendly here. */
        FRIENDLY,
        /**
         * A club may request a friendly here unless it is in that week's playoff.
         *
         * <p>Only week 11's Thursday slot: the playoff clubs are busy, everyone else may play.
         */
        FRIENDLY_IF_NOT_IN_PLAYOFF
    }

    /** One slot of one week, and what fills it. */
    public record WeekSlot(int week, int slot, SlotKind kind, int leagueRound) {

        /** Whether a club could ask for a friendly in this slot. */
        public boolean friendlyCapable() {
            return kind == SlotKind.FRIENDLY || kind == SlotKind.FRIENDLY_IF_NOT_IN_PLAYOFF;
        }

        /** Whether this slot is a scheduled fixture rather than an open one. */
        public boolean scheduled() {
            return kind == SlotKind.LEAGUE || kind == SlotKind.PLAYOFF;
        }
    }

    private static final WeekSlot[][] SLOTS = build();

    /**
     * What a single slot holds, before it becomes a {@link WeekSlot}.
     *
     * <p>Written as an explicit type rather than {@code Object[][]} on purpose: in a nested array
     * initializer, {@code {a, b}, {c, d}} is two rows, not one row of two, and {@code {{a, b}}} is
     * rejected outright for an {@code Object} element. Both mistakes shift the table silently.
     */
    private record SlotSpec(SlotKind kind, int round) { }

    private static WeekSlot[][] build() {
        // One row per week, two columns: Thursday then Sunday. This is the owner's table, and it
        // is the only place the season shape is written down.
        SlotSpec[][] table = {
                {league(1), league(2)},                                                  // week 1
                {league(3), league(4)},                                                  // week 2
                {league(5), league(6)},                                                  // week 3
                {league(7), league(8)},                                                  // week 4
                // Week 5 runs league-then-friendly, week 6 friendly-then-league.
                {league(9), friendly()},                                                 // week 5
                {friendly(), league(10)},                                                // week 6
                {league(11), league(12)},                                                // week 7
                {league(13), league(14)},                                                // week 8
                {league(15), league(16)},                                                // week 9
                {league(17), league(18)},                                                // week 10
                // Week 11: the playoff takes Thursday, so only the clubs not in it may play then.
                {playoffOrFriendly(), friendly()},                                       // week 11
                {friendly(), friendly()},                                                // week 12
        };
        if (table.length != WEEKS_PER_SEASON) {
            throw new IllegalStateException("The season table has " + table.length
                    + " weeks, expected " + WEEKS_PER_SEASON);
        }

        WeekSlot[][] weeks = new WeekSlot[WEEKS_PER_SEASON][SLOTS_PER_WEEK];
        for (int week = 1; week <= WEEKS_PER_SEASON; week++) {
            for (int slot = 1; slot <= SLOTS_PER_WEEK; slot++) {
                SlotSpec spec = table[week - 1][slot - 1];
                weeks[week - 1][slot - 1] =
                        new WeekSlot(week, slot, spec.kind(), spec.round());
            }
        }
        return weeks;
    }

    private static SlotSpec league(int round) {
        return new SlotSpec(SlotKind.LEAGUE, round);
    }

    private static SlotSpec friendly() {
        return new SlotSpec(SlotKind.FRIENDLY, -1);
    }

    private static SlotSpec playoffOrFriendly() {
        return new SlotSpec(SlotKind.FRIENDLY_IF_NOT_IN_PLAYOFF, -1);
    }

    /** The slot for a week and slot number (1 = Thursday, 2 = Sunday). */
    public static WeekSlot slot(int week, int slotNumber) {
        if (week < 1 || week > WEEKS_PER_SEASON || slotNumber < 1 || slotNumber > SLOTS_PER_WEEK) {
            return null;
        }
        return SLOTS[week - 1][slotNumber - 1];
    }

    /** Both slots of a week, in order. */
    public static List<WeekSlot> slots(int week) {
        if (week < 1 || week > WEEKS_PER_SEASON) return List.of();
        return new ArrayList<>(Arrays.asList(SLOTS[week - 1]));
    }

    /** The league rounds played in a given week, in slot order. */
    public static List<Integer> roundsIn(int week) {
        List<Integer> rounds = new ArrayList<>();
        for (WeekSlot s : slots(week)) {
            if (s.kind() == SlotKind.LEAGUE) rounds.add(s.leagueRound());
        }
        return rounds;
    }

    /** How many league matches a club plays in a given week. */
    public static int matchesIn(int week) {
        return roundsIn(week).size();
    }

    /**
     * How many friendly slots a week offers a club that is <b>not</b> in that week's playoff.
     *
     * <p>These are options, not fixtures. Nothing is played unless it is requested and accepted.
     */
    public static int friendlySlots(int week) {
        if (week < 1 || week > WEEKS_PER_SEASON) return 0;
        int n = 0;
        for (WeekSlot s : slots(week)) if (s.friendlyCapable()) n++;
        return n;
    }

    /**
     * How many friendly slots are left to a club that <b>is</b> in that week's playoff.
     *
     * <p>Week 11's Thursday slot is the playoff, so a playoff club keeps only the Sunday slot.
     */
    public static int friendlySlots(int week, boolean inPlayoff) {
        if (week < 1 || week > WEEKS_PER_SEASON) return 0;
        int n = 0;
        for (WeekSlot s : slots(week)) {
            if (s.kind() == SlotKind.FRIENDLY) n++;
            else if (s.kind() == SlotKind.FRIENDLY_IF_NOT_IN_PLAYOFF && !inPlayoff) n++;
        }
        return n;
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
        if (week == PLAYOFF_WEEK) {
            return "Playoffs, and the end-of-season window opens";
        }
        if (week == BREAK_WEEK) return "Mid-season break, window closes tonight";
        if (isMidSeasonWindow(week)) {
            return week == MID_WINDOW_OPEN
                    ? "Mid-season window opens after this round"
                    : "Mid-season window closes tonight";
        }
        int league = matchesIn(week);
        int friendly = friendlySlots(week);
        StringBuilder sb = new StringBuilder();
        sb.append(league == 1 ? "1 match" : league + " matches");
        if (friendly > 0) sb.append(", ").append(friendly).append(" friendly slot")
                .append(friendly == 1 ? "" : "s").append(" available");
        return sb.toString();
    }

    /** The whole season as rows, for a calendar view. */
    public static List<Map<String, Object>> asRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int week = 1; week <= WEEKS_PER_SEASON; week++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("week", week);
            row.put("leagueRounds", roundsIn(week));
            row.put("friendlySlots", friendlySlots(week));
            row.put("windowOpen", isWindowOpen(week));
            row.put("label", describe(week));
            rows.add(row);
        }
        return rows;
    }

    /**
     * A round number for the nth friendly in a week.
     *
     * <p>Friendlies share a week with league matches, so they cannot borrow a league round number.
     * These live well above the eighteen league rounds to leave room for both friendly slots in the
     * break.
     */
    public static int friendlyRoundNumber(int week, int slot) {
        return 1000 + week * 10 + slot;
    }

    /** The week a league round is played in, or -1 if there is no such round. */
    public static int weekOfRound(int round) {
        for (int week = 1; week <= WEEKS_PER_SEASON; week++) {
            for (int slot = 1; slot <= SLOTS_PER_WEEK; slot++) {
                WeekSlot s = slot(week, slot);
                if (s.kind() == SlotKind.LEAGUE && s.leagueRound() == round) return week;
            }
        }
        return -1;
    }

    /**
     * The slot number a league round is played in, or -1.
     *
     * <p>Needed because week 5 runs league-then-friendly while week 6 runs friendly-then-league.
     */
    public static int slotOfRound(int round) {
        for (int week = 1; week <= WEEKS_PER_SEASON; week++) {
            for (int slot = 1; slot <= SLOTS_PER_WEEK; slot++) {
                WeekSlot s = slot(week, slot);
                if (s.kind() == SlotKind.LEAGUE && s.leagueRound() == round) return slot;
            }
        }
        return -1;
    }
}
