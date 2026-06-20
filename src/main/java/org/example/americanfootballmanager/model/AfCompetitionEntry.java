package org.example.americanfootballmanager.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "af_competition_entries")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AfCompetitionEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "season_competition_id")
    private AfSeasonCompetition seasonCompetition;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "team_id")
    private AfTeam team;

    private Integer points = 0;

    @Column(name = "points_scored")
    private Integer pointsScored = 0;

    @Column(name = "points_conceded")
    private Integer pointsConceded = 0;

    @Column(name = "point_diff")
    private Integer pointDiff = 0;

    private Integer position;

    private Integer wins = 0;

    private Integer losses = 0;
}
