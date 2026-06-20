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
}