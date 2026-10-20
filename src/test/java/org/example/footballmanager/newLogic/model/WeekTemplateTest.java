package org.example.footballmanager.newLogic.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import org.example.footballmanager.newLogic.model.WeekTemplate.DayKind;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The seven-day week and the events laid over it (owner, 2026-09-28).
 *
 * <p>The owner's table, transcribed: day 1 international 20:45, day 2 finance, day 3 league 19:00,
 * day 4 training, day 5 cup 18:00, day 6 form/morale, day 7 league 16:00. Asserted as a whole table
 * rather than field by field, because a schedule is one fact and half of it being right is not half
 * of a working week.
 */
class WeekTemplateTest {

    @Nested
    @DisplayName("the week itself")
    class TheWeek {

        @Test
        @DisplayName("seven days, in order, exactly as the owner specified")
        void theWholeTable() {
            assertEquals(7, WeekTemplate.WEEK.size());

            assertDay(1, DayKind.INTERNATIONAL, LocalTime.of(20, 45));
            assertDay(2, DayKind.FINANCE, null);
            assertDay(3, DayKind.LEAGUE, LocalTime.of(19, 0));
            assertDay(4, DayKind.TRAINING, null);
            assertDay(5, DayKind.CUP, LocalTime.of(18, 0));
            assertDay(6, DayKind.MORALE, null);
            assertDay(7, DayKind.LEAGUE_SECOND, LocalTime.of(16, 0));
        }

        @Test
        @DisplayName("day numbers are 1 to 7, with no gaps")
        void daysAreContiguous() {
            for (int day = 1; day <= 7; day++) {
                assertEquals(day, WeekTemplate.day(day).day());
            }
        }

        @Test
        @DisplayName("a day outside 1-7 is nothing, not an exception")
        void outsideTheWeekIsNull() {
            assertNull(WeekTemplate.day(0));
            assertNull(WeekTemplate.day(8));
            assertNull(WeekTemplate.day(-1));
        }

        @Test
        @DisplayName("four days have a match; three do not")
        void matchDays() {
            List<Integer> matches = new ArrayList<>();
            for (var day : WeekTemplate.all()) {
                if (day.matchDay()) {
                    matches.add(day.day());
                }
            }
            assertEquals(List.of(1, 3, 5, 7), matches,
                    "international, league, cup, league - the other three are finance, training "
                            + "and the morale update");
        }
    }

    @Nested
    @DisplayName("the two tables must agree")
    class TheSeam {

        @Test
        @DisplayName("SeasonCalendar's two match slots land on league days in the template")
        void slotsLandOnLeagueDays() {
            // The whole point of the assertion in SeasonCalendar, tested from the outside. If someone
            // edits WeekTemplate to put the cup on day 3, this fails instead of the game generating
            // league fixtures into a day the schedule screen calls a cup day.
            assertNotNull(SeasonCalendar.dayTemplate());
            assertEquals(7, SeasonCalendar.dayTemplate().size());

            int slotOneDay = SeasonCalendar.SLOT_ONE_DAY;
            int slotTwoDay = SeasonCalendar.SLOT_TWO_DAY;
            assertTrue(WeekTemplate.day(slotOneDay).matchDay(), "slot 1 day must be a match day");
            assertTrue(WeekTemplate.day(slotTwoDay).matchDay(), "slot 2 day must be a match day");
            assertEquals(3, slotOneDay);
            assertEquals(7, slotTwoDay);
        }

        @Test
        @DisplayName("days 3 and 7 are league in the template, which is where the rounds are generated")
        void leagueSitsOnTheGeneratedDays() {
            assertEquals(WeekTemplate.DayKind.LEAGUE, WeekTemplate.day(3).kind());
            assertEquals(WeekTemplate.DayKind.LEAGUE_SECOND, WeekTemplate.day(7).kind());
        }
    }

    @Nested
    @DisplayName("events laid over the template")
    class Events {

        @Test
        @DisplayName("an event does not change the template - it sits on top of it")
        void eventsDoNotMutateTheWeek() {
            int sizeBefore = WeekTemplate.WEEK.size();
            var before = WeekTemplate.day(1);

            List<CalendarEvent> events = List.of(
                    CalendarEvent.at(1, 1, CalendarEvent.EventType.NT_QUALIFYING_DRAW, "NT draw"),
                    CalendarEvent.at(1, 5, CalendarEvent.EventType.CUP_DRAW, "Cup draw"));

            var week = CalendarEvent.weekWith(1, events);

            assertEquals(sizeBefore, WeekTemplate.WEEK.size(), "the template must not grow");
            assertEquals(before, WeekTemplate.day(1), "the template must not change");
            assertEquals(7, week.size(), "a week is still seven days with events on them");
        }

        @Test
        @DisplayName("an event lands on its day and nowhere else")
        void eventsLandOnTheRightDay() {
            List<CalendarEvent> events = List.of(
                    CalendarEvent.at(2, 1, CalendarEvent.EventType.NT_QUALIFYING_DRAW, "NT draw"),
                    CalendarEvent.at(2, 5, CalendarEvent.EventType.CUP_DRAW, "Cup draw"));

            List<CalendarEvent.DayView> week = CalendarEvent.weekWith(2, events);

            assertEquals(1, week.get(0).extraCount(), "day 1 has the NT draw");
            assertEquals(0, week.get(1).extraCount());
            assertEquals(0, week.get(2).extraCount());
            assertEquals(0, week.get(3).extraCount());
            assertEquals(1, week.get(4).extraCount(), "day 5 has the cup draw");
            assertEquals(0, week.get(5).extraCount());
            assertEquals(0, week.get(6).extraCount());
        }

        @Test
        @DisplayName("an event only shows on the week it belongs to")
        void eventsAreScopedToTheirWeek() {
            List<CalendarEvent> events = List.of(
                    CalendarEvent.at(4, 1, CalendarEvent.EventType.CUP_DRAW, "Week 4 draw"));

            assertEquals(1, CalendarEvent.weekWith(4, events).get(0).extraCount());
            assertEquals(0, CalendarEvent.weekWith(5, events).get(0).extraCount(),
                    "a draw in week 4 must not appear in week 5");
        }

        @Test
        @DisplayName("an empty or absent event list still gives a full week")
        void noEventsIsStillAWeek() {
            assertEquals(7, CalendarEvent.weekWith(1, List.of()).size());
            assertEquals(7, CalendarEvent.weekWith(1, null).size(),
                    "a null list is an absence, not a crash");
            assertTrue(CalendarEvent.weekWith(1, List.of()).stream().noneMatch(d -> d.extraCount() > 0));
        }

        @Test
        @DisplayName("an event can set its own time, and then the template's hour is not used")
        void eventsCanOverrideTheHour() {
            List<CalendarEvent> events = List.of(
                    CalendarEvent.at(1, 3, CalendarEvent.EventType.NATIONAL_MATCH, "Qualifier",
                            LocalTime.of(20, 0)));

            CalendarEvent.DayView day3 = CalendarEvent.weekWith(1, events).get(2);

            assertEquals(LocalTime.of(20, 0), day3.kickoff(), "the event's own time wins");
            assertEquals(LocalTime.of(19, 0), WeekTemplate.day(3).kickoff(),
                    "and the template is untouched");
        }

        @Test
        @DisplayName("events on one day are ordered deterministically")
        void eventsAreOrdered() {
            List<CalendarEvent> shuffled = new ArrayList<>(List.of(
                    CalendarEvent.at(1, 5, CalendarEvent.EventType.OTHER, "b"),
                    CalendarEvent.at(1, 1, CalendarEvent.EventType.OTHER, "a"),
                    CalendarEvent.at(1, 3, CalendarEvent.EventType.OTHER, "c")));

            List<CalendarEvent> inWeek = CalendarEvent.eventsIn(1, shuffled);

            assertEquals(List.of("a", "c", "b"), inWeek.stream().map(CalendarEvent::label).toList(),
                    "a schedule that reshuffles itself between two reads is not a schedule");
        }

        @Test
        @DisplayName("an event outside the season or the week is refused at construction")
        void outOfRangeIsRefused() {
            assertThrows(IllegalArgumentException.class, () -> CalendarEvent.at(13, 1,
                    CalendarEvent.EventType.OTHER, "next season"));
            assertThrows(IllegalArgumentException.class, () -> CalendarEvent.at(0, 1,
                    CalendarEvent.EventType.OTHER, "before the season"));
            assertThrows(IllegalArgumentException.class, () -> CalendarEvent.at(1, 8,
                    CalendarEvent.EventType.OTHER, "day 8"));
            assertThrows(IllegalArgumentException.class, () -> CalendarEvent.at(1, 0,
                    CalendarEvent.EventType.OTHER, "day 0"));
        }

        @Test
        @DisplayName("an event with no type is refused")
        void typeIsRequired() {
            assertThrows(NullPointerException.class,
                    () -> CalendarEvent.at(1, 1, null, "nameless"));
        }

        @Test
        @DisplayName("a day view is a snapshot - the list cannot be edited through it")
        void dayViewIsImmutable() {
            CalendarEvent.DayView view = CalendarEvent.weekWith(1,
                    List.of(CalendarEvent.at(1, 1, CalendarEvent.EventType.OTHER, "x"))).get(0);

            assertThrows(UnsupportedOperationException.class, () -> view.events().add(
                    CalendarEvent.at(1, 1, CalendarEvent.EventType.OTHER, "sneaky")));
        }
    }

    /** Asserts a day's number, kind and kickoff together, so a shuffled table cannot pass. */
    private static void assertDay(int day, WeekTemplate.DayKind kind, LocalTime kickoff) {
        WeekTemplate.DaySlot actual = WeekTemplate.day(day);
        assertNotNull(actual, "day " + day + " is missing");
        assertEquals(day, actual.day(), "wrong day number");
        assertEquals(kind, actual.kind(), "wrong kind on day " + day);
        assertEquals(kickoff, actual.kickoff(), "wrong kickoff on day " + day);
        assertEquals(kickoff != null, actual.matchDay(),
                "a day with a kickoff must be a match day, and one without must not");
    }
}
