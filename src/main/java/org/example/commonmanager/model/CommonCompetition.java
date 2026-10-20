package org.example.commonmanager.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "common_competitions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommonCompetition {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(length = 10)
    private String shortName;

    @Column(length = 2)
    private String countryCode;

    private String sport;

    private Integer tier;

    @Column(name = "division_level")
    private Integer divisionLevel;

    @Column(name = "teams_per_competition")
    private Integer teamsPerCompetition;

    @Column(name = "promotion_spots")
    private Integer promotionSpots;

    @Column(name = "relegation_spots")
    private Integer relegationSpots;

    @Column(name = "points_win")
    @Builder.Default
    private Integer pointsWin = 3;

    @Column(name = "points_draw")
    @Builder.Default
    private Integer pointsDraw = 1;

    @Column(name = "points_loss")
    @Builder.Default
    private Integer pointsLoss = 0;
}
