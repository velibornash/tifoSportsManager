package org.example.footballmanager.zox;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ZoxTeamComparisonDTO {
    private String homeTeamName;
    private String awayTeamName;
    
    private Integer homeTeamRating;
    private Integer awayTeamRating;
    
    private String homeFormation;
    private String awayFormation;
    
    private String homePlayStyle;
    private String awayPlayStyle;
    
    private String homeRecentForm; // "WWDLL" format
    private String awayRecentForm;
    
    private Integer homeAvgGoalsFor;
    private Integer awayAvgGoalsFor;
    private Integer homeAvgGoalsAgainst;
    private Integer awayAvgGoalsAgainst;
    
    private String homeStrengthDescription; // "Very Strong", "Strong", "Average", etc
    private String awayStrengthDescription;
}
