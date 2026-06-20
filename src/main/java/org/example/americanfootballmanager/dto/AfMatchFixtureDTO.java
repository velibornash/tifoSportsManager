package org.example.americanfootballmanager.dto;

import lombok.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AfMatchFixtureDTO {
    private Long id;
    private Long homeTeamId;
    private String homeTeamName;
    private String homeTeamShortName;
    private Long awayTeamId;
    private String awayTeamName;
    private String awayTeamShortName;
    private Long competitionId;
    private Integer seasonYear;
    private Integer roundNumber;
    private LocalDateTime matchDate;
    private Boolean played;
    private Integer homeScore;
    private Integer awayScore;
    private String homeQuarterScores;
    private String awayQuarterScores;
    private Long playedMatchId;
}
