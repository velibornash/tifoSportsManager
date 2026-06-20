package org.example.basketballmanager.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BbPlayerGameStatsDTO {
    private Long playerId;
    private String playerName;
    private String position;
    private int minutes;
    private int points;
    private int rebounds;
    private int assists;
    private int steals;
    private int blocks;
    private int turnovers;
    private int fouls;
    private int twoPtMade;
    private int twoPtAttempted;
    private int threePtMade;
    private int threePtAttempted;
    private int ftMade;
    private int ftAttempted;
}