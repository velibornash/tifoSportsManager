package org.example.footballmanager.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class MatchDetailsDTO {
    private String homeTeamName;
    private String awayTeamName;
    private int homeGoals;
    private int awayGoals;
    private List<PlayerDTO> homePlayers;
    private List<PlayerDTO> awayPlayers;
    private List<GoalEventDTO> goals;  // NOVO
}