package org.example.footballmanager.newLogic.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity(name = "GameClock")
@Getter
@Setter
public class GameClock {

    @Id
    private Long id = 1L;
    private Integer currentSeason;
    private Integer currentWeek;
    private SeasonPhase currentPhase;
    @Column(name = "current_game_date")
    private LocalDateTime currentDate;

    /**
     * Day within the game's seven-day cycle, 1..7 (owner, 2026-09-28).
     *
     * <p>A <b>game</b> day, not a weekday. It maps positionally onto {@code WeekTemplate.DayKind}:
     * 1 is INTERNATIONAL, 2 FINANCE, 3 LEAGUE, 4 TRAINING, 5 CUP, 6 MORALE, 7 LEAGUE_SECOND.
     *
     * <p>Deliberately not derived from {@link #currentDate}. The template is a game construct, so
     * deriving the day from the real calendar would make the same season behave differently
     * depending on what weekday it was started on - a cup round would land on the wrong day purely
     * because of the start date. The stored value is advanced explicitly by the clock service and is
     * never inferred, so there is no second source to reconcile.
     */
    private Integer currentDay = 1;

    /**
     * Hour of the current game day, 0..23 (owner, 2026-09-28).
     *
     * <p>Needed because "Watch match" must only become active at kickoff, which is an hour, not a
     * day. Advancing an hour is also how a half-finished day is tested.
     */
    private Integer currentHour = 9;

    /**
     * Seconds the owner has advanced the game clock (owner, 2026-09-28).
     *
     * <p>Game time is {@code now() + advanceOffsetSeconds}, which is what makes both halves of the
     * owner's requirement true at once: the clock ticks one game second per real second because only
     * the offset is stored, and {@code advance hour} moves it forward permanently because it adds to
     * the offset instead of overwriting a value the next tick would undo.
     *
     * <p>The alternative - storing the hour directly and rendering from the real clock - cannot work.
     * Advancing writes a number, the next render reads the real time, and the jump is gone within a
     * frame. A Long, not an int: at one hour per advance a 32-bit counter wraps in 2.5 million
     * advances, which is a long time but not impossible on a heavily tested database.
     */
    @jakarta.persistence.Column(name = "advance_offset_seconds")
    private Long advanceOffsetSeconds = 0L;

    public Integer getCurrentDay() {
        return currentDay;
    }

    public void setCurrentDay(Integer currentDay) {
        this.currentDay = currentDay;
    }

    public Integer getCurrentHour() {
        return currentHour;
    }

    public void setCurrentHour(Integer currentHour) {
        this.currentHour = currentHour;
    }

    public Long getAdvanceOffsetSeconds() {
        return advanceOffsetSeconds;
    }

    public void setAdvanceOffsetSeconds(Long advanceOffsetSeconds) {
        this.advanceOffsetSeconds = advanceOffsetSeconds;
    }
}