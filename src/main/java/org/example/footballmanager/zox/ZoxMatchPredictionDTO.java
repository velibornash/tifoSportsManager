package org.example.footballmanager.zox;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ZoxMatchPredictionDTO {
    private Double homeWinProbability;
    private Double drawProbability;
    private Double awayWinProbability;
    
    private Double expectedHomeGoals;
    private Double expectedAwayGoals;
    
    private String mostLikelyResult; // "HOME_WIN", "DRAW", "AWAY_WIN"
    
    // Additional analysis
    private String analysis;
    private Integer confidence; // 0-100 confidence level
}
