package org.example.commonmanager.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "common_game_clock")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommonGameClock {
    @Id
    private Long id;

    @Column(name = "current_season")
    private Integer currentSeason;

    @Column(name = "current_week")
    private Integer currentWeek;

    @Column(name = "current_year")
    private Integer currentYear;

    @Column(length = 20)
    private String sport;
}
