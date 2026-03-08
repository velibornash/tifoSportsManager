package org.example.footballmanager.zox;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ZoxMatchPreviewDTO {
    private Long matchId;
    private Long homeTeamId;
    private Long awayTeamId;
    private String homeTeamName;
    private String awayTeamName;
    private String homeTeamLogo;
    private String awayTeamLogo;
    
    // Pre-match info
    private String homeFormation;
    private String awayFormation;
    private String homePlayStyle;
    private String awayPlayStyle;
    
    // Prediction
    private Double homeWinProbability;
    private Double drawProbability;
    private Double awayWinProbability;
    private String expectedResult;
    private Double expectedHomeGoals;
    private Double expectedAwayGoals;
    
    // Team strength indicators
    private Integer homeTeamRating;
    private Integer awayTeamRating;
    private String homeRecentForm; // W/D/L string
    private String awayRecentForm;
    
    // Player lineups with ratings
    private List<ZoxPlayerRatingDTO> homeLineup;
    private List<ZoxPlayerRatingDTO> awayLineup;
    private List<ZoxPlayerRatingDTO> homeSubstitutes;
    private List<ZoxPlayerRatingDTO> awaySubstitutes;
    
    // Key matchups
    private Map<String, String> keyMatchups; // position -> "Player1 vs Player2"
    
    // Injury/suspension info
    private List<String> homeAbsentees;
    private List<String> awayAbsentees;
    
    private String analysisText;
}
