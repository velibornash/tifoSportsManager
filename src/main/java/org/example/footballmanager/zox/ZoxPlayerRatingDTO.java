package org.example.footballmanager.zox;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ZoxPlayerRatingDTO {
    private Long playerId;
    private String name;
    private String position;
    private Integer squadNumber;
    
    // Rating scores (0-10)
    private Double overallRating;
    private Double attackRating;
    private Double defenseRating;
    private Double passAccuracy;
    private Double shotAccuracy;
    
    // Expected Goals (xG)
    private Double expectedGoals;
    private Double expectedAssists;
    
    // Match stats
    private Integer passes;
    private Integer successfulPasses;
    private Integer tackles;
    private Integer fouls;
    private Integer yellowCards;
    private Integer redCards;
    
    // Involvement
    private Integer shotsOnTarget;
    private Integer shotsOffTarget;
    private Integer dribbles;
    private Integer interceptions;
    
    private String status; // "active", "substituted", "injured", "suspended"
}
