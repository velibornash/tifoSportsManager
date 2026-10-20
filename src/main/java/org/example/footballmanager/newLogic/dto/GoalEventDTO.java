package org.example.footballmanager.newLogic.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.example.footballmanager.newLogic.model.event.GoalEvent;

@Data
@EqualsAndHashCode(callSuper = true)
public class GoalEventDTO extends MatchEventDTO {
    private String scorerName;
    private String assistantName;
    private String teamName;
    private String scoreAfterGoal;
    private boolean scored;

    public static GoalEventDTO fromGoalEvent(GoalEvent g) {
        GoalEventDTO dto = new GoalEventDTO();
        dto.setMinute(g.minute());
        dto.setTeamName(g.teamSide() != null ? g.teamSide() : "N/A");
        dto.setScorerName(g.scorerName() != null ? g.scorerName() : "N/A");
        dto.setAssistantName(g.assistantName() != null ? g.assistantName() : null);
        dto.setScoreAfterGoal(g.homeScoreAfter() + "-" + g.awayScoreAfter());
        dto.setScored(true);
        return dto;
    }
}
