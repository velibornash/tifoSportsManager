package org.example.footballtextmanager.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CSMatchResult {
    private String homeTeamName;
    private String awayTeamName;
    private Long homeTeamId;
    private Long awayTeamId;
    private int homeGoals;
    private int awayGoals;
    private int round;
    private List<CSMatchEvent> events;
    private String summary;
    private String report;
    
    @Builder.Default
    private List<CSPlayerMatchStats> homePlayerStats = new ArrayList<>();
    @Builder.Default
    private List<CSPlayerMatchStats> awayPlayerStats = new ArrayList<>();
    @Builder.Default
    private boolean derby = false;
    
    // Match statistics
    private int homePossession;
    private int awayPossession;
    private double homeXG;
    private double awayXG;
    private int homeShotsOnTarget;
    private int awayShotsOnTarget;
    private int homeShotsOffTarget;
    private int awayShotsOffTarget;
    private int homeCorners;
    private int awayCorners;
    private int homeFouls;
    private int awayFouls;
}
