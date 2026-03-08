package org.example.footballmanager.zox;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ZoxMatchStatsDTO {
    private Long matchId;
    
    // Score
    private Integer homeGoals;
    private Integer awayGoals;
    private String result; // "HOME_WIN", "DRAW", "AWAY_WIN"
    
    // Shots
    private Integer homeShotsOnTarget;
    private Integer awayShotsOnTarget;
    private Integer homeShotsOffTarget;
    private Integer awayShotsOffTarget;
    
    // Expected Goals
    private Double homeExpectedGoals;
    private Double awayExpectedGoals;
    
    // Possession
    private Double homePossession;
    private Double awayPossession;
    
    // Pass stats
    private Integer homePassesCompleted;
    private Integer homeTotalPasses;
    private Integer awayPassesCompleted;
    private Integer awayTotalPasses;
    private Double homePassAccuracy;
    private Double awayPassAccuracy;
    
    // Defensive stats
    private Integer homeTackles;
    private Integer awayTackles;
    private Integer homeInterceptions;
    private Integer awayInterceptions;
    private Integer homeClearances;
    private Integer awayClearances;
    
    // Cards
    private Integer homeYellowCards;
    private Integer awayYellowCards;
    private Integer homeRedCards;
    private Integer awayRedCards;
    
    // Fouls
    private Integer homeFouls;
    private Integer awayFouls;
    private Integer homeOffsides;
    private Integer awayOffsides;
    
    // Set pieces
    private Integer homeCorners;
    private Integer awayCorners;
    private Integer homeFreeKicks;
    private Integer awayFreeKicks;
    
    // Performance metrics
    private Double homeDominance; // Possession + xG weighted
    private Double awayDominance;
}
