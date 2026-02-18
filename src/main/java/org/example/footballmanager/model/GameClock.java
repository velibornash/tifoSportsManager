package org.example.footballmanager.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.LocalDateTime;

@Entity
public class GameClock {

    @Id
    private Long id = 1L;
    private Integer currentSeason;
    private SeasonPhase currentPhase;
    @Column(name = "current_game_date")
    private LocalDateTime currentDate;

}
