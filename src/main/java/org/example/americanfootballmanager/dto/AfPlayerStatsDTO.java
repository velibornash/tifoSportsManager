package org.example.americanfootballmanager.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AfPlayerStatsDTO {
    private Integer games;
    private Integer touchdowns;
    private Integer fieldGoalsMade;
    private Integer fieldGoalsAttempted;
    private Double fgPct;
    private Integer tackles;
    private Integer interceptions;
    private Integer sacks;
    private Integer passingYards;
    private Integer rushingYards;
    private Integer receivingYards;
    private Integer passingTouchdowns;
    private Integer rushingTouchdowns;
    private Integer receivingTouchdowns;
    private Integer twoPointConversions;
    private Integer fumbles;
}
