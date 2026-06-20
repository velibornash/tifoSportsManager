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

    private int gamesPlayed = 0;
    private int touchdowns = 0;
    private int fieldGoalsMade = 0;
    private int fieldGoalsAttempted = 0;
    private int tackles = 0;
    private int interceptions = 0;
    private int sacks = 0;
    private int passingYards = 0;
    private int rushingYards = 0;
    private int receivingYards = 0;
    private int passingTouchdowns = 0;
    private int rushingTouchdowns = 0;
    private int receivingTouchdowns = 0;
    private int twoPointConversions = 0;
    private int fumbles = 0;
}
