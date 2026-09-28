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
}