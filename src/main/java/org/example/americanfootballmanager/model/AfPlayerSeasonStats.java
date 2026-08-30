package org.example.americanfootballmanager.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "af_player_season_stats")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AfPlayerSeasonStats {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "player_id", nullable = false)
    private AfPlayer player;

    @Column(name = "season_year", nullable = false)
    private Integer seasonYear;

    @Column(name = "competition_id")
    private Long competitionId;

    @Column(name = "team_id")
    private Long teamId;

    private String teamName;

    @Builder.Default
    private int gamesPlayed = 0;
    @Builder.Default
    private int touchdowns = 0;
    @Builder.Default
    private int fieldGoalsMade = 0;
    @Builder.Default
    private int fieldGoalsAttempted = 0;
    @Builder.Default
    private int tackles = 0;
    @Builder.Default
    private int interceptions = 0;
    @Builder.Default
    private int sacks = 0;
    @Builder.Default
    private int passingYards = 0;
    @Builder.Default
    private int rushingYards = 0;
    @Builder.Default
    private int receivingYards = 0;
    @Builder.Default
    private int passingTouchdowns = 0;
    @Builder.Default
    private int rushingTouchdowns = 0;
    @Builder.Default
    private int receivingTouchdowns = 0;
    @Builder.Default
    private int twoPointConversions = 0;
    @Builder.Default
    private int fumbles = 0;
}
