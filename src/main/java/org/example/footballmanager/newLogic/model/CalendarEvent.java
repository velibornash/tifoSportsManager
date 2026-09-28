package org.example.footballmanager.newLogic.model;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Something that has been scheduled into a day of the weekly template (owner, 2026-09-28).
 *
 * <p>The owner asked for a shared weekly template with room to add things when they happen — the
 * national-team and U-21 qualifying draws, the cup draw, a playoff. The important property is that
 * <b>adding an event does not edit the week</b>: the template in {@link WeekTemplate} is fixed and
 * shared by every country, and events are data laid over it. A draw happening in week 2 is a fact
 * about that week, not a change to what week 2 looks like everywhere.
 *
 * <p>That separation is what keeps the template shareable. If an event were a mutation of the
 * template, then a draw in one country would alter the week every other country sees.
 *
 * <h2>Why it is not persisted here</h2>
 *
 * <p>This is the shape only. A draw is something the engine decides and must survive a restart, so the
 * storage is a service's problem and deliberately not a static calendar's. What lives here is the
 * vocabulary, so that whoever builds the draw cannot invent a different one.
 */
public record CalendarEvent(
        int week,
        int day,
        EventType type,
        String label,
        LocalTime kickoff) implements Comparable<CalendarEvent> {

    /** What kind of thing is happening. Kept small on purpose. */
    public enum EventType {
        /** The national-team qualifying draw. */
        NT_QUALIFYING_DRAW,
        /** The U-21 qualifying draw. */
        U21_QUALIFYING_DRAW,
        /** The domestic cup draw. */
        CUP_DRAW,
        /** A playoff. */
        PLAYOFF,
        /** A national-team match, for when a qualifier is scheduled on an ordinary day. */
        NATIONAL_MATCH,
        /** Anything the engine schedules that does not fit above. */
        OTHER
    }

    public CalendarEvent {
        if (week < 1 || week > SeasonCalendar.WEEKS_PER_SEASON) {
            throw new IllegalArgumentException("Week " + week + " is outside a "
                    + SeasonCalendar.WEEKS_PER_SEASON + "-week season");
        }
        if (day < 1 || day > WeekTemplate.WEEK.size()) {
            throw new IllegalArgumentException("Day " + day + " is outside a 7-day week");
        }
        type = Objects.requireNonNull(type, "type");
    }

    /** An event at the template's own kickoff time for that day. */
    public static CalendarEvent at(int week, int day, EventType type, String label) {
        WeekTemplate.DaySlot slot = WeekTemplate.day(day);
        return new CalendarEvent(week, day, type, label, slot == null ? null : slot.kickoff());
    }

    /** An event at a time of its own, for something that does not start at the template's hour. */
    public static CalendarEvent at(int week, int day, EventType type, String label, LocalTime kickoff) {
        return new CalendarEvent(week, day, type, label, kickoff);
    }

    @Override
    public int compareTo(CalendarEvent other) {
        int byWeek = Integer.compare(this.week, other.week);
        if (byWeek != 0) {
            return byWeek;
        }
        return Integer.compare(this.day, other.day);
    }

    /**
     * A week with its events laid over the shared template.
     *
     * <p>Pure, and that is the point: it reads the fixed template and the day's events and returns a
     * displayable week. Nothing is stored, nothing is mutated, and the same call gives the same
     * answer — so the schedule screen is reproducible and can be compared against the template in a
     * test without a database.
     */
    public static List<DayView> weekWith(int week, List<CalendarEvent> events) {
        List<CalendarEvent> forWeek = new ArrayList<>();
        if (events != null) {
            forWeek.addAll(events.stream()
                    .filter(e -> e.week() == week)
                    .sorted()
                    .toList());
        }

        List<DayView> out = new ArrayList<>(WeekTemplate.WEEK.size());
        for (WeekTemplate.DaySlot day : WeekTemplate.WEEK) {
            List<CalendarEvent> onDay = forWeek.stream()
                    .filter(e -> e.day() == day.day())
                    .toList();
            out.add(new DayView(day, onDay));
        }
        return out;
    }

    /**
     * A day as shown on the schedule: the template's own content, plus anything scheduled into it.
     *
     * @param extraCount how many events sit on this day, so a screen can show "+2 more" without
     *                   the template having to know what a draw is
     */
    public record DayView(WeekTemplate.DaySlot slot, List<CalendarEvent> events) {

        public DayView {
            events = List.copyOf(events);
        }

        public int day() {
            return slot.day();
        }

        public boolean hasMatch() {
            return slot.matchDay();
        }

        /** The template's kickoff, unless an event on this day set its own. */
        public LocalTime kickoff() {
            return events.isEmpty() ? slot.kickoff() : events.get(0).kickoff();
        }

        public int extraCount() {
            return events.size();
        }
    }

    /** Events for one week, in day order, for a screen that lists rather than lays out. */
    public static List<CalendarEvent> eventsIn(int week, List<CalendarEvent> events) {
        if (events == null) {
            return List.of();
        }
        return events.stream()
                .filter(e -> e.week() == week)
                .sorted(Comparator.naturalOrder())
                .toList();
    }
}
