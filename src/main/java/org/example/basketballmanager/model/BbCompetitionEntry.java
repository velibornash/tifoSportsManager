package org.example.basketballmanager.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "bb_competition_entries")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BbCompetitionEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "season_competition_id")
    private BbSeasonCompetition seasonCompetition;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "team_id")
    private BbTeam team;

    @Builder.Default
    private Integer points = 0;

    @Column(name = "points_scored")
    @Builder.Default
    private Integer pointsScored = 0;

    @Column(name = "points_conceded")
    @Builder.Default
    private Integer pointsConceded = 0;

    @Column(name = "point_diff")
    @Builder.Default
    private Integer pointDiff = 0;

    private Integer position;

    @Builder.Default
    private Integer wins = 0;

    @Builder.Default
    private Integer losses = 0;
}
