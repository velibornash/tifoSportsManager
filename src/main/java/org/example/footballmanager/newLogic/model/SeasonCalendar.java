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
 * <h2>Every week has four slots</h2>
 * day 1, day 3, day 5 and day 7. Anything that is not a scheduled fixture is an <b>option</b>, not an
 * obligation: a club is not handed a friendly, it asks for one and the other club may refuse.
 *
 * <pre>
 *   week  1   day 1 friendly  day 3 round  1   day 5 friendly  day 7 round  2
 *   week  2   day 1 friendly  day 3 round  3   day 5 friendly  day 7 round  4
 *   week  3   day 1 friendly  day 3 round  5   day 5 friendly  day 7 round  6
 *   week  4   day 1 friendly  day 3 round  7   day 5 friendly  day 7 round  8
 *   week  5   day 1 friendly  day 3 round  9   day 5 friendly  day 7 round 10   | mid-season window OPENS at end of week
 *   week  6   day 1 friendly  day 3 friendly  day 5 friendly  day 7 friendly     | mid-season window CLOSES; no league
 *   week  7   day 1 friendly  day 3 round 11   day 5 friendly  day 7 round 12
 *   week  8   day 1 friendly  day 3 round 13   day 5 friendly  day 7 round 14
 *   week  9   day 1 friendly  day 3 round 15   day 5 friendly  day 7 round 16
 *   week 10   day 1 friendly  day 3 round 17   day 5 friendly  day 7 round 18
 *   week 11   day 1 friendly  day 3 playoff or friendly  day 5 friendly  day 7 friendly   | window OPENS at start of week
 *   week 12   day 1 friendly  day 3 friendly  day 5 friendly  day 7 friendly               | window CLOSES at end of week
 * </pre>
 *
 * <p>Ten clubs play each other twice: eighteen league matches, two a week for nine weeks.
 *
 * <p><b>Weeks 6 and 12 have no league football at all</b> (owner rule 2026-09-27). They are not gaps
 * in the calendar — they are weeks given over to something else, played day by day: national-team
 * qualifiers in week 6, and the World Cup in week 12. Neither is built yet, so both currently offer
 * four ordinary friendly slots. They are named in the code so the weeks are recognisable as
 * deliberate when those competitions arrive, rather than looking like a scheduling mistake.
 *
 * <p>A week is a container of seven days with four football moments in it — <b>day 1, day 3, day 5 and day 7</b>.
 * That is how the season shape is written down rather than by weekday, so moving a fixture does not
 * move the season.
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
    /**
     * The mid-season window opens at the end of week 5 and closes in week 6.
     *
     * <p>It used to open after round 9, because round 10 was the second slot of week 6. Round 10
     * moved into week 5, so the window now opens once the first half is complete — which is what
     * "mid-season window" always meant, and is also the only reading that leaves week 6 free for
     * national-team football.
     */
    public static final int MID_WINDOW_OPEN = 5;
    public static final int MID_WINDOW_CLOSE = 6;

    /** The end-of-season window opens at the start of week 11 and closes at the end of week 12. */
    public static final int END_WINDOW_OPEN = 11;
    public static final int END_WINDOW_CLOSE = 12;

    /** The last league match is in week 10. */
    public static final int LEAGUE_END_WEEK = 10;

    /** Playoffs are week 11: the clubs in them play that instead of a friendly. */
    public static final int PLAYOFF_WEEK = 11;

    /**
     * Week 6 is midseason: no league, and no fixtures to speak of.
     *
     * <p>Reserved for <b>national-team qualifiers, played day by day</b> (owner rule 2026-09-27).
     * Those are not built yet, so for now the four slots are ordinary friendly opportunities. Named
     * here so the week is recognisable as something other than "a gap in the calendar" the day the
     * qualifiers land.
     */
    public static final int MIDSEASON_WEEK = 6;

    /**
     * Week 12 is the World Cup, day by day: no league.
     *
     * <p>Not built yet either. It is the end of the season and the end-of-season transfer window
     * closes during it, so it is the last chance to move a player.
     */
    public static final int BREAK_WEEK = 12;

    /** League rounds a ten-club double round-robin takes. */
    public static final int LEAGUE_ROUNDS = 18;

    /**
     * Every week has four match slots: day 1, day 3, day 5 and day 7.
     *
     * <p>Expressed as <b>day 1, day 3, day 5 and day 7 of the week</b> (owner rule 2026-10-06) rather than as
     * weekdays. Naming slots after weekdays would couple the season shape to the day a fixture
     * happens to be played, so the model uses day numbers instead.
     * The shape is what everything else
     * — fixtures, windows, the viewer — is written against. A week is a container of seven days with
     * four football moments in it, and saying so keeps that true when the fixtures move.
     */
    public static final int SLOT_ONE_DAY = 1;
    public static final int SLOT_TWO_DAY = 3;
    public static final int SLOT_THREE_DAY = 5;
    public static final int SLOT_FOUR_DAY = 7;
    public static final int SLOTS_PER_WEEK = 4;

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
         * <p>Only week 11's day-3 slot: the playoff clubs are busy, everyone else may play.
         */
        FRIENDLY_IF_NOT_IN_PLAYOFF
    }

    /** One slot of one week, and what fills it. */
    public record WeekSlot(int week, int slot, SlotKind kind, int leagueRound) {

        /** Day number represented by this weekly slot. */
        public int day() {
            return SeasonCalendar.dayForSlot(slot);
        }

        /** Whether a club could ask for a friendly in this slot. */
        public boolean friendlyCapable() {
            return kind == SlotKind.FRIENDLY || kind == SlotKind.FRIENDLY_IF_NOT_IN_PLAYOFF;
        }

        /** Whether this slot is a scheduled fixture rather than an open one. */
        public boolean scheduled() {
            return kind == SlotKind.LEAGUE || kind == SlotKind.PLAYOFF;
        }
    }

    /**
     * The seven-day template every country shares (owner, 2026-09-28).
     *
     * <p>Reached through here rather than used directly, so the two tables are checked against each
     * other once, at class load. They describe the same week from two angles — this file says which
     * <i>league round</i> is in week 7, {@link WeekTemplate} says which <i>kind of day</i> day 3 is —
     * and nothing forces them to agree except this assertion.
     */
    public static java.util.List<WeekTemplate.DaySlot> dayTemplate() {
        return WeekTemplate.all();
    }

    /** One day of the shared template, or null outside 1-7. */
    public static WeekTemplate.DaySlot day(int dayNumber) {
        return WeekTemplate.day(dayNumber);
    }

    /**
     * Fails fast if the two tables disagree about one of the four match days.
     *
     * <p>The failure this prevents is quiet and expensive. If {@link WeekTemplate} were edited to put
     * the cup on day 3, every fixture in the game would still be generated into the day-3 league slot
     * while the schedule screen told managers the cup was on tonight. Nothing would fail; the two
     * would simply describe different weeks.
     */
    private static void assertSlotsMatchTemplate() {
        for (int slot = 1; slot <= SLOTS_PER_WEEK; slot++) {
            int day = dayForSlot(slot);
            WeekTemplate.DaySlot templateDay = WeekTemplate.day(day);
            if (templateDay == null || !templateDay.matchDay()) {
                throw new IllegalStateException("SeasonCalendar slot " + slot + " is day " + day
                        + ", which the week template does not treat as a match day");
            }
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
        assertSlotsMatchTemplate();

        // One row per week, four columns: day 1, day 3, day 5 and day 7. This is the owner's table, and it is
        // the only place the season shape is written down.
        SlotSpec[][] table = {
                {friendly(), league(1), friendly(), league(2)},                          // week 1
                {friendly(), league(3), friendly(), league(4)},                          // week 2
                {friendly(), league(5), friendly(), league(6)},                          // week 3
                {friendly(), league(7), friendly(), league(8)},                          // week 4
                // Week 5 closes the league's first half with two rounds, back to back. Round 10
                // used to sit in week 6's second slot; the owner moved it here so that week 6 is
                // free for national-team qualifiers, day by day.
                {friendly(), league(9), friendly(), league(10)},                         // week 5
                // Week 6: no league at all. Midseason, for national-team qualifiers to be added.
                {friendly(), friendly(), friendly(), friendly()},                        // week 6
                {friendly(), league(11), friendly(), league(12)},                        // week 7
                {friendly(), league(13), friendly(), league(14)},                        // week 8
                {friendly(), league(15), friendly(), league(16)},                        // week 9
                {friendly(), league(17), friendly(), league(18)},                        // week 10
                // Week 11: the playoff takes Thursday, so only the clubs not in it may play then.
                {friendly(), playoffOrFriendly(), friendly(), friendly()},                // week 11
                {friendly(), friendly(), friendly(), friendly()},                        // week 12
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

    /** The slot for a week and slot number (1 = day 1, 2 = day 3, 3 = day 5, 4 = day 7). */
    public static WeekSlot slot(int week, int slotNumber) {
        if (week < 1 || week > WEEKS_PER_SEASON || slotNumber < 1 || slotNumber > SLOTS_PER_WEEK) {
            return null;
        }
        return SLOTS[week - 1][slotNumber - 1];
    }

    /** All four slots of a week, in order. */
    public static List<WeekSlot> slots(int week) {
        if (week < 1 || week > WEEKS_PER_SEASON) return List.of();
        return new ArrayList<>(Arrays.asList(SLOTS[week - 1]));
    }

    /** The day represented by a slot number, or -1 for invalid input. */
    public static int dayForSlot(int slotNumber) {
        return switch (slotNumber) {
            case 1 -> SLOT_ONE_DAY;
            case 2 -> SLOT_TWO_DAY;
            case 3 -> SLOT_THREE_DAY;
            case 4 -> SLOT_FOUR_DAY;
            default -> -1;
        };
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
        // These two weeks are not empty in the calendar, they are weeks given over to something
        // else. Saying "0 matches, 2 friendly slots available" would be technically true and would
        // read as a bug, so they are named.
        if (week == MIDSEASON_WEEK) {
            return "Midseason, no league — national-team qualifiers";
        }
        if (week == BREAK_WEEK) return "World Cup, no league — window closes tonight";
        if (isMidSeasonWindow(week)) {
            return week == MID_WINDOW_OPEN
                    ? "Mid-season window opens tonight"
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
     * <p>Needed because rounds are spread across a week's four slots rather than one slot per week.
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
