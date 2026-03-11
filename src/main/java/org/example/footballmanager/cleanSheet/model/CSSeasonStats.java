package org.example.footballmanager.cleanSheet.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracks season records and milestones for the user's club.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CSSeasonStats {
    
    // Win/Loss streaks
    @Builder.Default
    private int currentWinStreak = 0;
    @Builder.Default
    private int currentUnbeatenStreak = 0;
    @Builder.Default
    private int currentLossStreak = 0;
    @Builder.Default
    private int currentNoWinStreak = 0;
    
    @Builder.Default
    private int longestWinStreak = 0;
    @Builder.Default
    private int longestUnbeatenStreak = 0;
    @Builder.Default
    private int longestLossStreak = 0;
    
    // Goal records
    @Builder.Default
    private int biggestWinMargin = 0;
    @Builder.Default
    private String biggestWinMatch = "";
    @Builder.Default
    private int biggestLossMargin = 0;
    @Builder.Default
    private String biggestLossMatch = "";
    @Builder.Default
    private int mostGoalsScoredInMatch = 0;
    @Builder.Default
    private String mostGoalsMatch = "";
    
    // Clean sheets
    @Builder.Default
    private int cleanSheets = 0;
    @Builder.Default
    private int failedToScore = 0;
    
    // Home/Away form
    @Builder.Default
    private int homeWins = 0;
    @Builder.Default
    private int homeDraws = 0;
    @Builder.Default
    private int homeLosses = 0;
    @Builder.Default
    private int awayWins = 0;
    @Builder.Default
    private int awayDraws = 0;
    @Builder.Default
    private int awayLosses = 0;
    
    // Goals by period
    @Builder.Default
    private int goalsFirstHalf = 0;
    @Builder.Default
    private int goalsSecondHalf = 0;
    @Builder.Default
    private int goalsConcededFirstHalf = 0;
    @Builder.Default
    private int goalsConcededSecondHalf = 0;
    
    // Late drama
    @Builder.Default
    private int lateGoalsScored = 0;  // 75+ minutes
    @Builder.Default
    private int lateGoalsConceded = 0;
    @Builder.Default
    private int comebacks = 0;  // Won after being behind
    @Builder.Default
    private int collapsedLeads = 0;  // Lost after being ahead
    
    // Player milestones achieved this season
    @Builder.Default
    private List<String> playerMilestones = new ArrayList<>();
    
    /**
     * Get form description based on recent results
     */
    public String getFormDescription() {
        if (currentWinStreak >= 5) return "Unstoppable form";
        if (currentWinStreak >= 3) return "Hot streak";
        if (currentUnbeatenStreak >= 7) return "Rock solid";
        if (currentUnbeatenStreak >= 4) return "Steady progress";
        if (currentLossStreak >= 4) return "Crisis mode";
        if (currentLossStreak >= 2) return "Struggling";
        if (currentNoWinStreak >= 5) return "Stuck in a rut";
        return "Mixed results";
    }
    
    /**
     * Get home form rating (0-100)
     */
    public int getHomeFormRating() {
        int total = homeWins + homeDraws + homeLosses;
        if (total == 0) return 50;
        return (int) ((homeWins * 3.0 + homeDraws) / (total * 3.0) * 100);
    }
    
    /**
     * Get away form rating (0-100)
     */
    public int getAwayFormRating() {
        int total = awayWins + awayDraws + awayLosses;
        if (total == 0) return 50;
        return (int) ((awayWins * 3.0 + awayDraws) / (total * 3.0) * 100);
    }
}
