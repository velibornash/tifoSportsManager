package org.example.footballmanager.newLogic.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class MatchStartedDTO extends MatchEventDTO {
    private String homeTeamName;
    private String awayTeamName;
    private String type = "matchStarted";
}