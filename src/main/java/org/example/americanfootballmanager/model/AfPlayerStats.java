package org.example.americanfootballmanager.model;

import jakarta.persistence.*;
import lombok.*;

@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AfPlayerStats {

    @Column(name = "games_played")
    @Builder.Default
    private Integer gamesPlayed = 0;

    @Column(name = "touchdowns")
    @Builder.Default
    private Integer touchdowns = 0;

    @Column(name = "field_goals_made")
    @Builder.Default
    private Integer fieldGoalsMade = 0;

    @Column(name = "field_goals_attempted")
    @Builder.Default
    private Integer fieldGoalsAttempted = 0;

    @Column(name = "tackles")
    @Builder.Default
    private Integer tackles = 0;

    @Column(name = "interceptions")
    @Builder.Default
    private Integer interceptions = 0;

    @Column(name = "sacks")
    @Builder.Default
    private Integer sacks = 0;

    @Column(name = "passing_yards")
    @Builder.Default
    private Integer passingYards = 0;

    @Column(name = "rushing_yards")
    @Builder.Default
    private Integer rushingYards = 0;

    @Column(name = "receiving_yards")
    @Builder.Default
    private Integer receivingYards = 0;

    @Column(name = "passing_touchdowns")
    @Builder.Default
    private Integer passingTouchdowns = 0;

    @Column(name = "rushing_touchdowns")
    @Builder.Default
    private Integer rushingTouchdowns = 0;

    @Column(name = "receiving_touchdowns")
    @Builder.Default
    private Integer receivingTouchdowns = 0;

    @Column(name = "two_point_conversions")
    @Builder.Default
    private Integer twoPointConversions = 0;

    @Column(name = "fumbles")
    @Builder.Default
    private Integer fumbles = 0;
}
