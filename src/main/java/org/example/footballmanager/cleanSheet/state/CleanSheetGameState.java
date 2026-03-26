package org.example.footballmanager.cleanSheet.state;

import lombok.Data;
import org.example.footballmanager.cleanSheet.model.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Data
public class CleanSheetGameState {

    private Long userId;
    private int seasonYear;
    private int currentRound = 1;
    private int consecutiveLosses = 0;
    private int consecutiveWins = 0;
    private String leagueName;

    // Korisnikov tim
    private CSTeam userTeam;
    private List<CSPlayer> roster = new ArrayList<>();
    private CSTactics tactics = CSTactics.builder().build();

    // Svi timovi u ligi (ukljucujuci korisnikov)
    private List<CSTeam> allTeams = new ArrayList<>();
    // Igraci svih timova: teamId -> lista igraca
    private Map<Long, List<CSPlayer>> allTeamRosters = new HashMap<>();

    // Liga
    private List<CSTableEntry> leagueTable = new ArrayList<>();
    private List<CSFixture> schedule = new ArrayList<>();

    // Istorija
    private List<CSMatchResult> matchHistory = new ArrayList<>();
    private List<CSInboxMessage> inbox = new ArrayList<>();
    private List<CSSeasonRecord> seasonHistory = new ArrayList<>();
    private String internationalCompetitionName;
    private int internationalMatchday = 1;
    private List<CSTableEntry> internationalTable = new ArrayList<>();
    private List<CSInternationalWindow> internationalWindows = new ArrayList<>();
    private List<CSTransferListing> transferMarket = new ArrayList<>();
    private String affiliateClubName;
    private String affiliateClubCountry;
    private String affiliateClubNote;
    private double lastRoundIncome;
    private double lastRoundExpenses;
    private double weeklyWageBill;
    private String boardObjectiveTitle;
    private String boardObjectiveText;
    private String boardReviewTitle;
    private String boardReviewText;
    private List<String> notableNews = new ArrayList<>();
    
    // Club atmosphere and stats tracking
    private CSClubMood clubMood = CSClubMood.builder().build();
    private CSSeasonStats seasonStats = CSSeasonStats.builder().build();

    public void addInboxMessage(String type, String text) {
        if (text == null || text.isBlank()) return;
        inbox.add(CSInboxMessage.builder()
                .type(type)
                .text(text)
                .timestamp(java.time.LocalDateTime.now().toString())
                .read(false)
                .build());
    }

    public void markInboxMessageRead(int index) {
        if (index < 0 || index >= inbox.size()) return;
        inbox.get(index).setRead(true);
    }

    public int getTotalRounds() {
        return schedule.stream()
                .mapToInt(CSFixture::getRound)
                .max()
                .orElse(0);
    }

    public boolean isSeasonOver() {
        return currentRound > getTotalRounds();
    }
    
    /**
     * Update season stats after a match result
     */
    public void updateSeasonStats(CSMatchResult result) {
        if (result == null || seasonStats == null) return;
        
        Long userTeamId = userTeam != null ? userTeam.getId() : null;
        if (userTeamId == null) return;
        
        boolean userHome = Objects.equals(result.getHomeTeamId(), userTeamId);
        int goalsFor = userHome ? result.getHomeGoals() : result.getAwayGoals();
        int goalsAgainst = userHome ? result.getAwayGoals() : result.getHomeGoals();
        int margin = goalsFor - goalsAgainst;
        
        // Determine result type
        boolean isWin = goalsFor > goalsAgainst;
        boolean isDraw = goalsFor == goalsAgainst;
        boolean isLoss = goalsFor < goalsAgainst;
        
        // Update streaks
        if (isWin) {
            seasonStats.setCurrentWinStreak(seasonStats.getCurrentWinStreak() + 1);
            seasonStats.setCurrentUnbeatenStreak(seasonStats.getCurrentUnbeatenStreak() + 1);
            seasonStats.setCurrentLossStreak(0);
            seasonStats.setCurrentNoWinStreak(0);
            consecutiveWins++;
            consecutiveLosses = 0;
        } else if (isDraw) {
            seasonStats.setCurrentWinStreak(0);
            seasonStats.setCurrentUnbeatenStreak(seasonStats.getCurrentUnbeatenStreak() + 1);
            seasonStats.setCurrentLossStreak(0);
            seasonStats.setCurrentNoWinStreak(seasonStats.getCurrentNoWinStreak() + 1);
            consecutiveWins = 0;
            consecutiveLosses = 0;
        } else {
            seasonStats.setCurrentWinStreak(0);
            seasonStats.setCurrentUnbeatenStreak(0);
            seasonStats.setCurrentLossStreak(seasonStats.getCurrentLossStreak() + 1);
            seasonStats.setCurrentNoWinStreak(seasonStats.getCurrentNoWinStreak() + 1);
            consecutiveWins = 0;
            consecutiveLosses++;
        }
        
        // Update longest streaks
        if (seasonStats.getCurrentWinStreak() > seasonStats.getLongestWinStreak()) {
            seasonStats.setLongestWinStreak(seasonStats.getCurrentWinStreak());
        }
        if (seasonStats.getCurrentUnbeatenStreak() > seasonStats.getLongestUnbeatenStreak()) {
            seasonStats.setLongestUnbeatenStreak(seasonStats.getCurrentUnbeatenStreak());
        }
        if (seasonStats.getCurrentLossStreak() > seasonStats.getLongestLossStreak()) {
            seasonStats.setLongestLossStreak(seasonStats.getCurrentLossStreak());
        }
        
        // Update home/away stats
        if (userHome) {
            if (isWin) seasonStats.setHomeWins(seasonStats.getHomeWins() + 1);
            else if (isDraw) seasonStats.setHomeDraws(seasonStats.getHomeDraws() + 1);
            else seasonStats.setHomeLosses(seasonStats.getHomeLosses() + 1);
        } else {
            if (isWin) seasonStats.setAwayWins(seasonStats.getAwayWins() + 1);
            else if (isDraw) seasonStats.setAwayDraws(seasonStats.getAwayDraws() + 1);
            else seasonStats.setAwayLosses(seasonStats.getAwayLosses() + 1);
        }
        
        // Update goal records
        if (isWin && margin > seasonStats.getBiggestWinMargin()) {
            seasonStats.setBiggestWinMargin(margin);
            seasonStats.setBiggestWinMatch(result.getSummary());
        }
        if (isLoss && Math.abs(margin) > seasonStats.getBiggestLossMargin()) {
            seasonStats.setBiggestLossMargin(Math.abs(margin));
            seasonStats.setBiggestLossMatch(result.getSummary());
        }
        if (goalsFor > seasonStats.getMostGoalsScoredInMatch()) {
            seasonStats.setMostGoalsScoredInMatch(goalsFor);
            seasonStats.setMostGoalsMatch(result.getSummary());
        }
        
        // Clean sheets and failed to score
        if (goalsAgainst == 0) {
            seasonStats.setCleanSheets(seasonStats.getCleanSheets() + 1);
        }
        if (goalsFor == 0) {
            seasonStats.setFailedToScore(seasonStats.getFailedToScore() + 1);
        }
        
        // Goals by period (estimate based on events)
        if (result.getEvents() != null) {
            String userTeamName = userHome ? result.getHomeTeamName() : result.getAwayTeamName();
            String opponentName = userHome ? result.getAwayTeamName() : result.getHomeTeamName();
            
            for (CSMatchEvent event : result.getEvents()) {
                if (event.getEventType() == CSEventType.GOAL) {
                    boolean isUserGoal = Objects.equals(userTeamName, event.getTeamName());
                    int minute = event.getMinute();
                    
                    if (isUserGoal) {
                        if (minute <= 45) {
                            seasonStats.setGoalsFirstHalf(seasonStats.getGoalsFirstHalf() + 1);
                        } else {
                            seasonStats.setGoalsSecondHalf(seasonStats.getGoalsSecondHalf() + 1);
                        }
                        if (minute >= 75) {
                            seasonStats.setLateGoalsScored(seasonStats.getLateGoalsScored() + 1);
                        }
                    } else {
                        if (minute <= 45) {
                            seasonStats.setGoalsConcededFirstHalf(seasonStats.getGoalsConcededFirstHalf() + 1);
                        } else {
                            seasonStats.setGoalsConcededSecondHalf(seasonStats.getGoalsConcededSecondHalf() + 1);
                        }
                        if (minute >= 75) {
                            seasonStats.setLateGoalsConceded(seasonStats.getLateGoalsConceded() + 1);
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Update club mood based on recent results and position
     */
    public void updateClubMood(CSMatchResult result, int leaguePosition, int totalTeams) {
        if (clubMood == null) clubMood = CSClubMood.builder().build();
        
        Long userTeamId = userTeam != null ? userTeam.getId() : null;
        if (userTeamId == null || result == null) return;
        
        boolean userHome = Objects.equals(result.getHomeTeamId(), userTeamId);
        int goalsFor = userHome ? result.getHomeGoals() : result.getAwayGoals();
        int goalsAgainst = userHome ? result.getAwayGoals() : result.getHomeGoals();
        
        boolean isWin = goalsFor > goalsAgainst;
        boolean isLoss = goalsFor < goalsAgainst;
        int margin = Math.abs(goalsFor - goalsAgainst);
        
        // Fan mood changes
        int fanChange = 0;
        if (isWin) {
            fanChange = 5 + (margin >= 3 ? 8 : margin >= 2 ? 4 : 0);
            if (userHome) fanChange += 2; // Home wins matter more to fans
        } else if (isLoss) {
            fanChange = -7 - (margin >= 3 ? 10 : margin >= 2 ? 5 : 0);
            if (userHome) fanChange -= 3; // Home losses hurt more
        } else {
            fanChange = userHome ? -2 : 1; // Draws at home disappoint, away draws are okay
        }
        clubMood.setFanMood(clamp(clubMood.getFanMood() + fanChange, 0, 100));
        
        // Board confidence changes
        int boardChange = 0;
        if (isWin) {
            boardChange = 3 + (margin >= 3 ? 4 : 0);
        } else if (isLoss) {
            boardChange = -5 - (margin >= 3 ? 6 : 0);
            if (consecutiveLosses >= 3) boardChange -= 5;
        }
        // Position affects board confidence
        if (leaguePosition <= 3) boardChange += 2;
        else if (leaguePosition > totalTeams - 3) boardChange -= 3;
        clubMood.setBoardConfidence(clamp(clubMood.getBoardConfidence() + boardChange, 0, 100));
        
        // Media pressure changes
        int mediaChange = 0;
        if (isWin && margin >= 3) mediaChange = 5;
        else if (isLoss && margin >= 3) mediaChange = 10;
        else if (consecutiveLosses >= 3) mediaChange = 8;
        else if (consecutiveWins >= 3) mediaChange = 3;
        else mediaChange = -2; // Pressure naturally decreases
        clubMood.setMediaPressure(clamp(clubMood.getMediaPressure() + mediaChange, 0, 100));
        
        // Squad morale changes
        int moraleChange = 0;
        if (isWin) {
            moraleChange = 4 + (margin >= 3 ? 5 : 0);
        } else if (isLoss) {
            moraleChange = -6 - (margin >= 3 ? 6 : 0);
        } else {
            moraleChange = -1;
        }
        clubMood.setSquadMorale(clamp(clubMood.getSquadMorale() + moraleChange, 0, 100));
    }
    
    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
    
    /**
     * Reset season stats for new season
     */
    public void resetSeasonStats() {
        seasonStats = CSSeasonStats.builder().build();
        consecutiveLosses = 0;
        consecutiveWins = 0;
    }
}
