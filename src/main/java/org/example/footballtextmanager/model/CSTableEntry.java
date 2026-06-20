package org.example.footballtextmanager.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CSTableEntry {
    private Long teamId;
    private String teamName;
    private int points;
    private int wins;
    private int draws;
    private int losses;
    private int goalsScored;
    private int goalsConceded;
    private int played;
    private int position;

    public int getGoalDifference() {
        return goalsScored - goalsConceded;
    }
}
