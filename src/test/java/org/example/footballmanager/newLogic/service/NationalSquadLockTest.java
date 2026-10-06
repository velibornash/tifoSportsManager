package org.example.footballmanager.newLogic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-10 — the tournament squad lock: week 12, day 1, 10:00 (owner, 2026-10-06).
 *
 * <p>The owner's two rules in one predicate. The easy half is "the squad may be changed at any time"
 * during qualifying; the half that goes wrong in practice is the boundary, where an hour is either
 * 09:59 or 10:00 and nobody has an opinion until a selector is refused at 09:55 and allowed at 10:05.
 *
 * <p>Pure arithmetic with no Spring and no database, because that is the only shape in which every
 * boundary can be asserted at once. The write paths that consult it are
 * {@code NationalTeamService.addToSquad} and {@code removeFromSquad}.
 */
class NationalSquadLockTest {

    @Test
    @DisplayName("the whole first half of the season is unlocked - the squad changes at any time")
    void qualifyingLeavesTheSquadOpen() {
        for (int week = 1; week <= 11; week++) {
            for (int day = 1; day <= 7; day++) {
                for (int hour = 0; hour < 24; hour++) {
                    assertFalse(NationalTeamService.isSquadLocked(week, day, hour),
                            "week " + week + " day " + day + " " + hour + ":00 is qualifying, "
                                    + "and the owner said the squad may be changed at any time");
                }
            }
        }
    }

    @Test
    @DisplayName("week 12 day 1 is locked from 10:00, and open before it")
    void theBoundaryIsTenOClockOnTheFirstDay() {
        assertFalse(NationalTeamService.isSquadLocked(12, 1, 0), "the day starts unlocked");
        assertFalse(NationalTeamService.isSquadLocked(12, 1, 9), "09:00 is before the owner's hour");
        assertTrue(NationalTeamService.isSquadLocked(12, 1, 10),
                "10:00 is the owner's hour and the lock is inclusive of it");
        assertTrue(NationalTeamService.isSquadLocked(12, 1, 11));
        assertTrue(NationalTeamService.isSquadLocked(12, 1, 23), "and it stays locked for the rest of the day");
    }

    @Test
    @DisplayName("every day after the first is locked, whatever the hour")
    void theRestOfTournamentWeekIsLocked() {
        for (int day = 2; day <= 7; day++) {
            assertTrue(NationalTeamService.isSquadLocked(12, day, 0),
                    "day " + day + " is past the lock, even at midnight: a manager cannot "
                            + "un-freeze a squad by rolling the clock back to 00:00");
        }
    }

    @Test
    @DisplayName("a week past twelve is still locked - the tournament does not unfreeze itself")
    void theLockOutlastsTheWeek() {
        assertTrue(NationalTeamService.isSquadLocked(13, 1, 12), "the lock has no expiry");
        assertTrue(NationalTeamService.isSquadLocked(20, 4, 9));
    }

    /**
     * The safe direction, stated as a test.
     *
     * <p>A clock that cannot be read must not silently take a manager's ability to pick a team, and it
     * must not throw inside a squad edit either. Unlocked is the answer because the lock is a deadline
     * rather than a trigger that can be reached by accident.
     */
    @Test
    @DisplayName("an unreadable clock leaves the squad editable rather than throwing or freezing it")
    void aMissingClockDoesNotFreezeOrCrash() {
        assertFalse(NationalTeamService.isSquadLocked(null, null, null));
        assertFalse(NationalTeamService.isSquadLocked(null, 1, 10));
        assertFalse(NationalTeamService.isSquadLocked(12, null, 10));
        assertFalse(NationalTeamService.isSquadLocked(12, 1, null));
    }
}