package org.example.footballmanager.newLogic.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class GoalKickEventDTO extends MatchEventDTO {
    private String goalkeeperName;
}
