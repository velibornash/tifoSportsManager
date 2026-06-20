package org.example.basketballmanager.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "bb_player_season_stats")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BbPlayerSeasonStats {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "player_id", nullable = false)
    private BbPlayer player;

    @Column(name = "season_year", nullable = false)
    private Integer seasonYear;

    @Column(name = "competition_id")
    private Long competitionId;

    @Column(name = "team_id")
    private Long teamId;

    private String teamName;

    private int gamesPlayed = 0;
    private int pointsScored = 0;
    private int reboundsTotal = 0;
    private int assistsTotal = 0;
    private int stealsTotal = 0;
    private int blocksTotal = 0;
    private int turnoversTotal = 0;

    private int twoPtMade = 0;
    private int twoPtAttempted = 0;
    private int threePtMade = 0;
    private int threePtAttempted = 0;
    private int ftMade = 0;
    private int ftAttempted = 0;

    public double getPpg() { return gamesPlayed > 0 ? (double) pointsScored / gamesPlayed : 0; }
    public double getRpg() { return gamesPlayed > 0 ? (double) reboundsTotal / gamesPlayed : 0; }
    public double getApg() { return gamesPlayed > 0 ? (double) assistsTotal / gamesPlayed : 0; }
    public double getSpg() { return gamesPlayed > 0 ? (double) stealsTotal / gamesPlayed : 0; }
    public double getBpg() { return gamesPlayed > 0 ? (double) blocksTotal / gamesPlayed : 0; }
    public double getTopg() { return gamesPlayed > 0 ? (double) turnoversTotal / gamesPlayed : 0; }
    public double getTwoPtPct() { return twoPtAttempted > 0 ? (double) twoPtMade / twoPtAttempted * 100 : 0; }
    public double getThreePtPct() { return threePtAttempted > 0 ? (double) threePtMade / threePtAttempted * 100 : 0; }
    public double getFtPct() { return ftAttempted > 0 ? (double) ftMade / ftAttempted * 100 : 0; }
}
