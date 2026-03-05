package org.example.footballmanager.dto;

import lombok.Data;

@Data
public class GoalKickEventDTO extends MatchEventDTO {
    private String goalkeeperName;
}
