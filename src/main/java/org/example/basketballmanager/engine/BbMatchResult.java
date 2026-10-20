package org.example.basketballmanager.engine;

import lombok.*;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BbMatchResult {
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
    private List<BbPlayerGameStats> homePlayerStats;
    private List<BbPlayerGameStats> awayPlayerStats;
    private List<String> events;
}
