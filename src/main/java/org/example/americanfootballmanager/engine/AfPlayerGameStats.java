package org.example.americanfootballmanager.engine;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AfPlayerGameStats {
    private Long playerId;
    private String playerName;
    private String position;
    private int minutes;
    private int touchdowns;
    private int fieldGoalsMade;
    private int fieldGoalsAttempted;
    private int tackles;
    private int interceptions;
    private int sacks;
    private int passingYards;
    private int rushingYards;
    private int receivingYards;
    private int passingTouchdowns;
    private int rushingTouchdowns;
    private int receivingTouchdowns;
    private int twoPointConversions;
    private int fumbles;
}
