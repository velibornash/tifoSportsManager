package org.example.footballmanager.cleanSheet.model;

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
}
