package org.tifo.footballmanager.newLogic.util;

import org.tifo.footballmanager.newLogic.model.GameDay;
import org.tifo.footballmanager.newLogic.model.GameTimeline;
import org.tifo.footballmanager.newLogic.model.WeekTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A fixture's day and its kickoff hour come from the same place (owner, 2026-10-11).
 *
 * <p><b>What this pins.</b> Three writers were each answering "when does this slot play" for
 * themselves. The league read {@link WeekTemplate} and was right. The national cup hardcoded day 5 at
 * 18:00. The fifteen continental cups wrote {@code dayNumber = 1} and then took their <i>date</i> from
 * the national cup's rule — so every one of them reported day 1 and carried a day-5, 18:00 timestamp.
 *
 * <p>That is invisible until it is expensive: the manager is told his tie kicks off at 18:00, he comes
 * back at 18:00, and nothing happens. The job that plays it is not due until 20:00, and the fixture
 * says it was at 18:00, and nothing in the game logs a word about the disagreement.
 *
 * <p>So the kickoff is now one function, {@link GameTimeline#kickoffAt}, and these assertions are about
 * it agreeing with the template and with the constants the writers declare.
 */
class FixtureKickoffMatchesTheCalendarTest {

    private static final LocalDate ANCHOR = LocalDate.of(2026, 1, 5); // season 1, week 1, day 1

    @Test
    @DisplayName("every match day kicks off at the hour the seven-day template gives it")
    void everyMatchDayUsesTheTemplateHour() {
        for (GameDay day : GameDay.values()) {
            LocalDateTime kickoff = GameTimeline.kickoffAt(ANCHOR, 3, day.number());
            WeekTemplate.DayKind kind = day.kind();

            if (kind.kickoff() == null) {
                assertEquals(12, kickoff.getHour(),
                        "day " + day.number() + " has no fixture in the template, so its kickoff is a "
                                + "fallback and nothing should ever schedule on it");
                continue;
            }
            assertEquals(kind.kickoff().getHour(), kickoff.getHour(),
                    "day " + day.number() + " is " + kind + " and the template says "
                            + kind.kickoff().getHour() + ":00, but the fixture is stamped "
                            + kickoff.getHour() + ":00");
            assertEquals(kind.kickoff().getMinute(), kickoff.getMinute(),
                    "day " + day.number() + " is a " + kind.kickoff().getMinute()
                            + "-minute slot and printing \":00\" for it is the 20:45 bug");
        }
    }

    @Test
    @DisplayName("the day-1 international slot is 20:45, not 20:00")
    void theDayOneSlotKeepsItsQuarterPast() {
        LocalDateTime kickoff = GameTimeline.kickoffAt(ANCHOR, 1, GameDay.INTERNATIONAL_DAY);

        assertEquals(20, kickoff.getHour(), "day 1 is the international slot");
        assertEquals(45, kickoff.getMinute(),
                "the calendar says 20:45 and a fixture stamped 20:00 is an hour early");
    }

    @Test
    @DisplayName("a week is seven days on from the week before")
    void weeksAreSevenDaysApart() {
        assertEquals(ANCHOR.plusDays(6), GameTimeline.kickoffAt(ANCHOR, 1, GameDay.LAST).toLocalDate(),
                "week 1 day 7 is six days after the anchor");
        // Day-of-week numbers are positions in the cycle, not dates. Week 1 day 7 is followed by
        // week 2 day 1 — the wrap is the point, and a fixture date that skipped it would put a
        // league round on a day the manager was told nothing was happening.
        assertEquals(GameTimeline.kickoffAt(ANCHOR, 1, GameDay.LAST).toLocalDate().plusDays(1),
                GameTimeline.kickoffAt(ANCHOR, 2, GameDay.FIRST).toLocalDate(),
                "week 2 day 1 must be the day straight after week 1 day 7");
    }

    /**
     * The continental cups declare day 1. If that declaration and the template ever disagree, the
     * draw writes a day the hour cannot come from, and the fixture is unstorable in practice.
     */
    @Test
    @DisplayName("the continental cups' declared day is a real fixture slot")
    void theContinentalCupDayIsAFixtureSlot() {
        int cupDay = InternationalClubCupDraw.CUP_DAY;

        assertNotNull(GameDay.of(cupDay).kickoffHour(),
                "the continental cups play on day " + cupDay + ", which the template gives no kickoff. "
                        + "Every one of the fifteen would be written with a day nothing can schedule on.");
        assertEquals(GameDay.INTERNATIONAL_DAY, cupDay,
                "the cups moved to the day-1 international slot on 2026-10-06; a change here has to be "
                        + "made in the draw class as well or the date and the day column disagree again");
    }

    @Test
    @DisplayName("the national cup plays in the day-5 slot the calendar gives it")
    void theNationalCupKeepsItsOwnDay() {
        LocalDateTime cupKickoff = CupFixtureSeeder.matchDateFor(1, ANCHOR.atStartOfDay());

        assertEquals(ANCHOR.plusDays(4), cupKickoff.toLocalDate(),
                "the national cup is the day-5 competition — four days on from day 1. The continental "
                        + "cups took the day-1 slot instead, which is why they cannot borrow this date rule.");
        assertEquals(18, cupKickoff.getHour(), "day 5 is the cup slot at 18:00");
    }

    /**
     * The defect itself: the day the draw writes into the fixture, and the date it stamps on it, have
     * to describe the same slot.
     *
     * <p>This is the assertion the whole change exists for. The continental cups wrote day 1 and
     * stamped day 5 at 18:00, and because both columns were populated nothing anywhere complained.
     */
    @Test
    @DisplayName("a continental tie's day column and its timestamp are the same slot")
    void aContinentalTieIsStampedOnTheDayItSaysItIs() {
        int declaredDay = InternationalClubCupDraw.CUP_DAY;
        LocalDateTime stamped = InternationalClubCupDraw.kickoffFor(2, ANCHOR.atStartOfDay());

        LocalDateTime theSlotItClaims = GameTimeline.kickoffAt(ANCHOR, 2, declaredDay);

        assertEquals(theSlotItClaims.toLocalDate(), stamped.toLocalDate(),
                "the fixture says day " + declaredDay + " but is dated " + stamped.toLocalDate()
                        + " — the day of the week it claims is not the day it carries");
        assertEquals(theSlotItClaims.getHour(), stamped.getHour(),
                "the fixture says day " + declaredDay + " at "
                        + theSlotItClaims.getHour() + ":00 but is stamped " + stamped.getHour() + ":00");
        assertNotEquals(CupFixtureSeeder.matchDateFor(2, ANCHOR.atStartOfDay()), stamped,
                "the continental cups are still borrowing the national cup's date rule. They play on "
                        + "day " + declaredDay + " and the national cup plays on day 5.");
    }

    @Test
    @DisplayName("a week of fixture dates moves a week, and a day of it moves a day")
    void theWeekOffsetsAreRealWeeks() {
        LocalDateTime week1 = GameTimeline.kickoffAt(ANCHOR, 1, GameDay.LEAGUE_FIRST_DAY);
        LocalDateTime week2 = GameTimeline.kickoffAt(ANCHOR, 2, GameDay.LEAGUE_FIRST_DAY);
        LocalDateTime week5 = GameTimeline.kickoffAt(ANCHOR, 5, GameDay.LEAGUE_FIRST_DAY);

        assertEquals(7, java.time.temporal.ChronoUnit.DAYS.between(week1.toLocalDate(), week2.toLocalDate()),
                "consecutive weeks are seven days apart");
        assertEquals(28, java.time.temporal.ChronoUnit.DAYS.between(week1.toLocalDate(), week5.toLocalDate()),
                "week 5 is four weeks on");
        assertTrue(week1.getHour() == 19,
                "day 3 is the first league slot at 19:00, was " + week1.getHour());
    }
}
