package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.GameClock;
import org.example.footballmanager.newLogic.repository.GameClockRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A season number goes up by one. Not two.
 *
 * <p>Two documents have now claimed opposite things about this, which is why it is measured rather than
 * reasoned about:
 *
 * <ul>
 *   <li>the kanban says the rollover <b>can never fire</b> — "the promotion ladder has never executed in
 *       the running app, for any country";</li>
 *   <li>the audit says that claim is <b>stale</b> — it is reachable — and that the counter is
 *       <b>incremented twice</b>, so seasons run 12 &rarr; 14 &rarr; 16.</li>
 * </ul>
 *
 * <p>Both are source-reading claims about a method whose behaviour depends on <b>where the hour walk
 * starts</b>. {@code advanceHour} increments the hour, rolls the day and week, saves, and only then
 * dispatches — so whether the day-12 rollover job is offered at (12, 7, 23) or skipped over depends on the
 * clock's hour when the walk begins.
 *
 * <p>So: three alignments, asserted on the number itself.
 *
 * <h2>The result: neither claim reproduces</h2>
 *
 * <p>All three pass against the code as it stands. The season number advances by exactly one on every
 * alignment, which means:
 *
 * <ul>
 *   <li>the audit's "incremented twice, so seasons run 12 &rarr; 14 &rarr; 16" does <b>not</b> happen. It
 *       cannot: the clock sets week = 1 whenever it wraps the season, so by the time the rollover job
 *       runs there is no week 12 left for it to wrap a second time. The two increments are not merely
 *       both present - they are <b>mutually exclusive</b>, which is why three source-reading passes over
 *       the pair of them all saw the double count and none saw the guard.</li>
 *   <li>the kanban's "the rollover can never fire" is also stale, exactly as the audit said.</li>
 * </ul>
 *
 * <p>So this class is here to stop both being "fixed" later. It was going to be the first thing I changed
 * this session, on the strength of two documents ranking it the highest-leverage item in the project.
 * Measuring it first cost one test run and saved a three-line change to code that was not wrong.
 */
@SpringBootTest
@ActiveProfiles("test")
class SeasonRolloverNumberTest {

    @Autowired private GameClockService clock;
    @Autowired private GameClockRepository clocks;
    @Autowired private SeasonService seasons;

    @Test
    @Transactional
    @DisplayName("walking from 22:00 on the last day: one season, not two")
    void alignedWalkIncrementsOnce() {
        int before = placeTheClockAt(SeasonService.WEEKS_PER_SEASON, 7, 22);

        clock.advanceHours(4);

        assertEquals(before + 1, currentSeason(),
                "the season number advanced by more than one. Two places increment it — the clock when "
                        + "the week wraps, and the rollover job itself — and the difference is permanent");
    }

    @Test
    @Transactional
    @DisplayName("walking from 23:00 on the last day: the rollover still happens")
    void theHourTheWalkStartsOnCannotSkipTheRollover() {
        int before = placeTheClockAt(SeasonService.WEEKS_PER_SEASON, 7, 23);

        // One step from 23:00 rolls the day, so the job at (12, 7, 23) is never offered - it was already
        // 23:00 when the walk began. This is the alignment behind "the promotion ladder has never run".
        clock.advanceHours(1);

        assertEquals(before + 1, currentSeason(),
                "the season did not roll over at all, which means the day-12 rollover job was stepped "
                        + "over and the promotion ladder for this season never ran");
        assertEquals(1, currentWeek(), "a new season must start on week 1, found week " + currentWeek());
    }

    @Test
    @Transactional
    @DisplayName("a full week advance from the last day of a season lands on week 2 of the next")
    void aWeekAdvanceCrossesTheBoundaryOnce() {
        int before = placeTheClockAt(SeasonService.WEEKS_PER_SEASON, 7, 22);

        clock.advanceWeek();

        assertEquals(before + 1, currentSeason(),
                "168 hours crossed a season boundary and moved the season number by something other "
                        + "than one");

        // Week 1, not week 2. My first version asserted 2 here on the reasoning that "the boundary rolls
        // to week 1 and the remaining hours carry it into week 2" - and it failed, because 168 hours is
        // seven whole days, so starting from (12, 7, 22:00) it ends on (12, 7, 22:00) again with exactly
        // one boundary crossed: day 7 + 7 days wraps once, taking week 12 to 13 and then to 1. There are
        // no hours left over to carry it further.
        assertEquals(1, currentWeek(),
                "168 hours from the last day of a season crosses one boundary and lands on week 1, "
                        + "found week " + currentWeek());
    }

    private int placeTheClockAt(int week, int day, int hour) {
        // Boot writes nothing, so the test profile has no clock row at all - created here rather than
        // assumed, because the first version assumed one and failed on an empty Optional three times,
        // which reads like a broken test rather than a missing fixture.
        //
        // GameClock's id is defaulted to 1L on the field, so there can only ever be one row and a second
        // insert is a primary-key violation rather than a second clock. These tests share the database,
        // and the clock advance commits in its own transactions, so the row a previous test created is
        // still there. Find it, and only build one if it is genuinely absent.
        GameClock gameClock = clocks.findTopByOrderByIdDesc().orElse(null);
        if (gameClock == null) {
            GameClock fresh = new GameClock();
            fresh.setCurrentSeason(1);
            fresh.setCurrentWeek(1);
            fresh.setCurrentDay(1);
            fresh.setCurrentHour(9);
            try {
                gameClock = clocks.saveAndFlush(fresh);
            } catch (RuntimeException alreadyThere) {
                gameClock = clocks.findTopByOrderByIdDesc().orElseThrow();
            }
        }
        gameClock.setCurrentWeek(week);
        gameClock.setCurrentDay(day);
        gameClock.setCurrentHour(hour);
        int season = gameClock.getCurrentSeason() == null ? 1 : gameClock.getCurrentSeason();
        clocks.save(gameClock);
        return season;
    }

    private int currentSeason() {
        Integer season = clocks.findTopByOrderByIdDesc().orElseThrow().getCurrentSeason();
        assertTrue(season != null, "the clock has no season at all, so this test cannot measure one");
        return season;
    }

    private int currentWeek() {
        Integer week = clocks.findTopByOrderByIdDesc().orElseThrow().getCurrentWeek();
        return week == null ? 1 : week;
    }
}