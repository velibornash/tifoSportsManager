package org.example.basketballmanager.model;

import jakarta.persistence.*;
import lombok.*;

@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BbPlayerStats {

    @Column(name = "games_played")
    @Builder.Default
    private Integer gamesPlayed = 0;

    @Column(name = "points_scored")
    @Builder.Default
    private Integer pointsScored = 0;

    @Column(name = "rebounds_total")
    @Builder.Default
    private Integer reboundsTotal = 0;

    @Column(name = "assists_total")
    @Builder.Default
    private Integer assistsTotal = 0;

    @Column(name = "steals_total")
    @Builder.Default
    private Integer stealsTotal = 0;

    @Column(name = "blocks_total")
    @Builder.Default
    private Integer blocksTotal = 0;

    @Column(name = "turnovers_total")
    @Builder.Default
    private Integer turnoversTotal = 0;

    @Column(name = "two_pt_made")
    @Builder.Default
    private Integer twoPtMade = 0;

    @Column(name = "two_pt_attempted")
    @Builder.Default
    private Integer twoPtAttempted = 0;

    @Column(name = "three_pt_made")
    @Builder.Default
    private Integer threePtMade = 0;

    @Column(name = "three_pt_attempted")
    @Builder.Default
    private Integer threePtAttempted = 0;

    @Column(name = "ft_made")
    @Builder.Default
    private Integer ftMade = 0;

    @Column(name = "ft_attempted")
    @Builder.Default
    private Integer ftAttempted = 0;

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