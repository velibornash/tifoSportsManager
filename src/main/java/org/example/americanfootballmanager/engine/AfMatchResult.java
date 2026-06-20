package org.example.americanfootballmanager.engine;

import lombok.*;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AfMatchResult {
    private Long homeTeamId;
    private String homeTeamName;
    private Long awayTeamId;
    private String awayTeamName;
    private int homeScore;
    private int awayScore;
    private String homeQuarterScores;
    private String awayQuarterScores;
    private int homeFouls;
    private int awayFouls;
    private List<AfPlayerGameStats> homePlayerStats;
    private List<AfPlayerGameStats> awayPlayerStats;
    private List<String> events;
}
