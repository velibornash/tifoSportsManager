package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.GameClock;
import org.example.footballmanager.newLogic.repository.GameClockRepository;
import org.example.footballmanager.newLogic.support.GameClocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A refused clock move must leave the clock where it was.
 *
 * <p><b>Found on the live server, 2026-10-19, by accident.</b> A rewind was sent that would put the
 * game before the season it was in. The API answered <b>400 "Season counts from 1, was 0."</b> — and the
 * clock moved anyway, by exactly the number of seconds asked for. A second, larger rewind left the
 * offset at −1,316,799 and the world in a position no endpoint could describe or repair.
 *
 * <p>The cause is two things in one place. The offset was <b>saved first and the position checked
 * afterwards</b>, so the check could only complain about a move it had already made. And
 * {@code GameClockService.moveClock} is {@code @Transactional protected} and <b>self-invoked</b>, so
 * Spring's proxy never sees it and the annotation does nothing — there was no transaction to roll the
 * write back even if the check had come first.
 *
 * <p>An error response that leaves the state changed is worse than no response at all: the caller is
 * told the move was refused and the world moved anyway.
 *
 * <p><b>Why it cannot pass by accident.</b> The offset is read from the database before and after, not
 * from a return value. A test that asserted on the response body would pass against a service that
 * moved the clock and then apologised.
 */
class GameClockRefusedMoveLeavesTheClockAloneTest extends BaseTest {

    @Autowired
    GameClockRepository clocks;

    @Autowired
    GameClockService gameClock;

    @BeforeEach
    @Transactional
    void aClockOnTheFirstDayOfTheFirstSeason() {
        GameClock clock = GameClocks.at(1, 1, 1, 12);
        clock.setId(1L);
        clocks.save(clock);
    }

    @Test
    @Transactional
    @DisplayName("a rewind past the start of the season is refused and the clock does not move")
    void aRefusedRewindDoesNotMoveTheClock() {
        long offsetBefore = offset();

        assertThrows(IllegalArgumentException.class,
                () -> gameClock.shiftOffsetSeconds(-999_999),
                "a rewind that puts the game before its first season must be refused");

        assertEquals(offsetBefore, offset(),
                "the rewind was refused and the clock moved anyway. The API answered 400 and the world "
                        + "moved — which is worse than no answer at all, because the caller believes "
                        + "nothing happened.");
    }

    @Test
    @Transactional
    @DisplayName("a rewind inside the season is allowed and does move the clock")
    void aRefusedMoveIsNotJustRefusingEverything() {
        long offsetBefore = offset();

        gameClock.shiftOffsetSeconds(-3_600);

        assertEquals(offsetBefore - 3_600, offset(),
                "a rewind of one hour inside the season must move the clock by one hour. A guard that "
                        + "refuses every move would pass the test above and break the game.");
    }

    @Test
    @Transactional
    @DisplayName("the clock can still read a position after a refused move")
    void theClockIsStillReadableAfterARefusedMove() {
        assertThrows(IllegalArgumentException.class, () -> gameClock.shiftOffsetSeconds(-999_999));

        assertTrue(gameClock.snapshot().get("seasonNumber") instanceof Integer,
                "the clock must still describe a position after a refused move; if it does not, the "
                        + "world is unrecoverable without a database edit");
    }

    private long offset() {
        return clocks.findById(1L).orElseThrow().getAdvanceOffsetSeconds();
    }
}
