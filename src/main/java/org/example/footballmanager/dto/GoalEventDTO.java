package org.example.footballmanager.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.example.footballmanager.model.event.GoalEvent;

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
        dto.setMinute(g.getMinute());
        dto.setScorerName(g.getScorer() != null ? g.getScorer().getName() : "N/A");
        dto.setAssistantName(g.getAssistant() != null ? g.getAssistant().getName() : null);
        dto.setScoreAfterGoal(g.getScoreAfterGoal());
        return dto;
    }
}