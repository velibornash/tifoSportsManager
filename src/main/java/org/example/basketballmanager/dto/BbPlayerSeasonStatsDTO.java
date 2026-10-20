package org.example.basketballmanager.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BbPlayerSeasonStatsDTO {
    private Long id;
    private Integer seasonYear;
    private Long competitionId;
    private Long teamId;
    private String teamName;
    private String competitionName;
    private Integer gamesPlayed;
    private Integer pointsScored;
    private Double ppg;
    private Integer reboundsTotal;
    private Double rpg;
    private Integer assistsTotal;
    private Double apg;
    private Integer stealsTotal;
    private Double spg;
    private Integer blocksTotal;
    private Double bpg;
    private Integer turnoversTotal;
    private Double topg;
    private Integer twoPtMade;
    private Integer twoPtAttempted;
    private Double twoPtPct;
    private Integer threePtMade;
    private Integer threePtAttempted;
    private Double threePtPct;
    private Integer ftMade;
    private Integer ftAttempted;
    private Double ftPct;
}
