package org.example.footballmanager.newLogic.model;

import java.time.LocalTime;
import java.util.List;

/**
 * The seven days of a week, and what each one is for (owner, 2026-09-28).
 *
 * <p>Complementary to {@link SeasonCalendar}, not a replacement for it. That class already holds a
 * per-week table of <b>league rounds</b> in two slots — day 3 and day 7 — and reserves week 6 for
 * national-team qualifiers and week 12 for the World Cup. This holds the <b>shape of a week</b>:
 * what kind of day each of the seven is, and what time a match kicks off.
 *
 * <p>The split is worth being explicit about, because the two could drift. The day template says
 * "day 3 is league, 19:00" and never changes; the week table says "week 7, day 3 is round 11". A
 * reader looking for where a fixture goes finds one place; a reader looking for what a Saturday is
 * finds the other. {@link SeasonCalendar#dayTemplate} is the single seam, and it asserts the two
 * agree.
 *
 * <h2>Kickoff times</h2>
 *
 * <p>The owner's schedule gives a time for every match day. Match <b>fixtures</b> already carry a
 * {@code LocalDateTime}, so this is the <i>template's</i> idea of when a day plays — used by the
 * schedule screen, and available to a fixture generator that has nothing better to go on. It is not
 * applied over an existing fixture's time.
 *
 * <h2>One template, every country</h2>
 *
 * <p>The owner was explicit: every country has the same weekly template. What differs between
 * countries is <b>what has been scheduled into it</b> — a national-team qualifier in week 6, a cup
 * round in week 7, a draw on an otherwise quiet day. That is data, not shape, and belongs in
 * {@link CalendarEvent} rather than in here, so that adding an event never means editing the week.
 */
public final class WeekTemplate {

    private WeekTemplate() {
    }

    /** What a day of the week is for. */
    public enum DayKind {

        /** An international match — a club, or a national team when it is week 6 or 12. */
        INTERNATIONAL(LocalTime.of(20, 45), true),

        /** The weekly finance settlement. Not a match day. */
        FINANCE(null, false),

        /** League football. */
        LEAGUE(LocalTime.of(19, 0), true),

        /** Training. Not a match day. */
        TRAINING(null, false),

        /** A domestic cup round. */
        CUP(LocalTime.of(18, 0), true),

        /** Form and morale update; a junior match is planned for this day, not built yet. */
        MORALE(null, false),

        /** League football, second fixture of the week. */
        LEAGUE_SECOND(LocalTime.of(16, 0), true);

        private final LocalTime kickoff;
        private final boolean matchDay;

        DayKind(LocalTime kickoff, boolean matchDay) {
            this.kickoff = kickoff;
            this.matchDay = matchDay;
        }

        /** The template's kickoff for this kind of day, or null on a day with no match. */
        public LocalTime kickoff() {
            return kickoff;
        }

        /** Whether anything is played at all. */
        public boolean matchDay() {
            return matchDay;
        }
    }

    /**
     * One day: which day of the week, what it is, and when it plays.
     *
     * <p>Deliberately a record with no behaviour beyond {@link #matchDay()}. A day is a fact about
     * the template; anything that needs to decide something belongs in the service that owns the
     * decision, not on a value object.
     */
    public record DaySlot(int day, DayKind kind) {

        public boolean matchDay() {
            return kind.matchDay();
        }

        public LocalTime kickoff() {
            return kind.kickoff();
        }
    }

    /**
     * The week, day 1 to day 7 (owner, 2026-09-28).
     *
     * <p>This is the owner's table, transcribed once:
     * <pre>
     *   day 1  International          20:45
     *   day 2  Weekly finance
     *   day 3  League                19:00
     *   day 4  Training
     *   day 5  Cup                   18:00
     *   day 6  Form / morale         (junior match, eventually)
     *   day 7  League                16:00
     * </pre>
     *
     * <p>Day 3 and day 7 line up with {@link SeasonCalendar}'s two match slots, which is not a
     * coincidence and is checked there.
     */
    public static final List<DaySlot> WEEK = List.of(
            new DaySlot(1, DayKind.INTERNATIONAL),
            new DaySlot(2, DayKind.FINANCE),
            new DaySlot(3, DayKind.LEAGUE),
            new DaySlot(4, DayKind.TRAINING),
            new DaySlot(5, DayKind.CUP),
            new DaySlot(6, DayKind.MORALE),
            new DaySlot(7, DayKind.LEAGUE_SECOND));

    /** The template for one day, or null for a day outside 1–7. */
    public static DaySlot day(int dayNumber) {
        if (dayNumber < 1 || dayNumber > WEEK.size()) {
            return null;
        }
        return WEEK.get(dayNumber - 1);
    }

    /** Every day of the week, in order. */
    public static List<DaySlot> all() {
        return WEEK;
    }
}
