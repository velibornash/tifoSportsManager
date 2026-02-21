package org.example.footballmanager.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
public class GameClock {

    @Id
    private Long Id = 1L;
    private Integer currentSeason;
    private SeasonPhase currentPhase;
    @Column(name = "current_game_date")
    private LocalDateTime currentDate;

}
