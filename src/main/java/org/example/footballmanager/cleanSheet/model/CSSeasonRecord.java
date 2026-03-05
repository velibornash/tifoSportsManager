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
public class CSSeasonRecord {
    private int seasonYear;
    private String leagueName;
    private String champion;
    private String relegatedTeam;
    private String playoffTeam;
    private String playoffOutcome;
    @Builder.Default
    private List<String> promotedTeams = new ArrayList<>();
}

