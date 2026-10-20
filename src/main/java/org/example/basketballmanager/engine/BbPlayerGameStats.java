package org.example.basketballmanager.engine;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BbPlayerGameStats {
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

    public double twoPtPct() {
        return twoPtAttempted > 0 ? (double) twoPtMade / twoPtAttempted * 100 : 0;
    }

    public double threePtPct() {
        return threePtAttempted > 0 ? (double) threePtMade / threePtAttempted * 100 : 0;
    }

    public double ftPct() {
        return ftAttempted > 0 ? (double) ftMade / ftAttempted * 100 : 0;
    }
}
