package org.example.americanfootballmanager.dto;

import lombok.*;
import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AfMatchDTO {
    private Long id;
    private Long homeTeamId;
    private String homeTeamName;
    private String homeTeamShortName;
    private String homeTeamColor;
    private Long awayTeamId;
    private String awayTeamName;
    private String awayTeamShortName;
    private String awayTeamColor;
    private Long leagueId;
    private Integer seasonYear;
    private Integer roundNumber;
    private LocalDateTime matchDate;
    private Boolean played;
    private Integer homeScore;
    private Integer awayScore;
    private String homeQuarterScores;
    private String awayQuarterScores;
    private List<String> events;
    private List<AfPlayerGameStatsDTO> homePlayerStats;
    private List<AfPlayerGameStatsDTO> awayPlayerStats;
}
