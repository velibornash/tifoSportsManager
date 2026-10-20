package org.example.basketballmanager.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BbPlayerStatsDTO {
    private Integer games;
    private Double ppg;
    private Double rpg;
    private Double apg;
    private Double spg;
    private Double bpg;
    private Double topg;
    private Double twoPtPct;
    private Double threePtPct;
    private Double ftPct;
    private Integer points;
    private Integer rebounds;
    private Integer assists;
    private Integer steals;
    private Integer blocks;
    private Integer turnovers;
    private Integer twoPtMade;
    private Integer twoPtAttempted;
    private Integer threePtMade;
    private Integer threePtAttempted;
    private Integer ftMade;
    private Integer ftAttempted;
}
