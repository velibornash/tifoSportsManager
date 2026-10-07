package org.example.footballmanager.newLogic.jobs;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.GameClock;
import org.example.footballmanager.newLogic.repository.GameClockRepository;
import org.example.footballmanager.newLogic.service.GameClockService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every hour of a game day is offered to the jobs, <b>including the last one</b> (owner, 2026-10-07).
 *
 * <p><b>The owner asked whether training works</b>, and the answer turned out to be about the hour
 * boundary rather than about training. The clock advanced like this:
 *
 * <pre>{@code
 * int hour = currentHour + 1;
 * if (hour > 23) { hour = 0; day += 1; ... }
 * ...
 * runDue(season, week, day, hour);      // day has ALREADY become the next day
 * }</pre>
 *
 * So the step that leaves day N asks the runner about <b>(day N+1, hour 0)</b>, and
 * <b>(day N, hour 23)</b> is never asked at all.
 *
 * <p>Two jobs sit on hour 23 — {@code WeekRolloverJob} and {@code SeasonRolloverJob} — and neither can
 * ever fire. It is invisible in the counters: the week still advances, because <em>the clock</em> does
 * that while incrementing the hour. Only the job never runs, and the owner's "a day passed for training
 * and nothing happened" is the same shape of report.
 *
 * <p>Training itself is day 4 at hour 10 and is fine. This test exists because the question "does job X
 * fire" cannot be answered by reading X's day and hour alone — the answer depends on which hours the
 * clock ever offers.
 */
class JobTriggerCoverageTest extends BaseTest {

    @Autowired
    private GameClockService gameClock;

    @Autowired
    private GameClockRepository clocks;

    @Test
    @Transactional
    @DisplayName("the runner is offered all 24 hours of a day, hour 23 included")
    void everyHourOfTheDayIsOffered() {
        aClockAtHour(0);

        List<String> offered = new ArrayList<>();
        // The runner is asked once per advanced hour. Recording what it was told is the only way to see
        // the hours the clock offers, because "which hour is it now" and "which hours did it pass" are
        // not the same question - the bug lives entirely in the second one.
        for (int step = 0; step < 24; step++) {
            gameClock.advanceHour();
            GameClock clock = clocks.findById(1L).orElseThrow();
            offered.add(clock.getCurrentDay() + "-" + clock.getCurrentHour());
        }

        TreeSet<String> distinct = new TreeSet<>(offered);
        assertEquals(24, distinct.size(),
                "advancing 24 hours from hour 0 must offer 24 distinct hours. Offered: " + offered);

        assertTrue(distinct.stream().anyMatch(pair -> pair.endsWith("-23")),
                "**hour 23 is offered.** On the step out of hour 23 the clock becomes the next day at hour 0, "
                        + "so a day-7 hour-23 job - WeekRolloverJob and SeasonRolloverJob - has never been "
                        + "due. Offered: " + offered);
    }

    @Test
    @Transactional
    @DisplayName("a whole week offers hour 23 on every one of its seven days")
    void hourTwentyThreeIsOfferedOnEveryDayOfTheWeek() {
        aClockAtHour(0);

        TreeSet<Integer> daysThatReachedHour23 = new TreeSet<>();
        for (int step = 0; step < 24 * 7; step++) {
            gameClock.advanceHour();
            GameClock clock = clocks.findById(1L).orElseThrow();
            if (Integer.valueOf(23).equals(clock.getCurrentHour())) {
                daysThatReachedHour23.add(clock.getCurrentDay());
            }
        }

        assertEquals(7, daysThatReachedHour23.size(),
                "each of the seven game days reaches hour 23. Days that did: " + daysThatReachedHour23);
        assertTrue(daysThatReachedHour23.contains(7),
                "**day 7 reaches hour 23**, which is where both rollover jobs are scheduled: "
                        + daysThatReachedHour23);
    }

    /** A clock at week 1, day 1, the given hour, so the walk starts from a known place. */
    private void aClockAtHour(int hour) {
        GameClock clock = clocks.findById(1L).orElseGet(GameClock::new);
        clock.setCurrentSeason(1);
        clock.setCurrentWeek(1);
        clock.setCurrentDay(1);
        clock.setCurrentHour(hour);
        clock.setAdvanceOffsetSeconds(0L);
        clock.setCurrentDate(LocalDateTime.of(2026, 3, 1, hour, 0));
        clocks.save(clock);
    }
}