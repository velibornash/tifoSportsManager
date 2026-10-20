package org.example.footballmanager.newLogic.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class MatchEndedDTO extends MatchEventDTO {
    private String homeTeamName;
    private String awayTeamName;
    private int homeGoals;
    private int awayGoals;
    private String type = "matchEnded";
}