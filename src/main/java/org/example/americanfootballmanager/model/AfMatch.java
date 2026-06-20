package org.example.americanfootballmanager.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "af_matches")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AfMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "home_team_id", nullable = false)
    private AfTeam homeTeam;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "away_team_id", nullable = false)
    private AfTeam awayTeam;

    @Column(name = "competition_id")
    private Long competitionId;

    @Column(name = "season_year", nullable = false)
    private Integer seasonYear;

    @Column(name = "round_number")
    private Integer roundNumber;

    @Column(name = "match_date", nullable = false)
    private LocalDateTime matchDate;

    private Boolean played = false;

    @Column(name = "home_score")
    private Integer homeScore;

    @Column(name = "away_score")
    private Integer awayScore;

    @Column(name = "home_quarter_scores", columnDefinition = "TEXT")
    private String homeQuarterScores;

    @Column(name = "away_quarter_scores", columnDefinition = "TEXT")
    private String awayQuarterScores;

    @Column(name = "events", columnDefinition = "TEXT")
    private String events;

    @Column(name = "home_player_stats", columnDefinition = "TEXT")
    private String homePlayerStats;

    @Column(name = "away_player_stats", columnDefinition = "TEXT")
    private String awayPlayerStats;

    public Integer getHomeScore() { return played ? homeScore : null; }
    public Integer getAwayScore() { return played ? awayScore : null; }
}
