package org.example.footballmanager.newLogic.dto.training;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class PlayerTrainingReportDTO {
    /**
     * The percentage of a week's training this player got (owner, 2026-09-27).
     *
     * <p>On the report because a number nobody can explain is a number nobody trusts. The manager
     * should be able to see why a week was thin - talent, the coach, or minutes - and that
     * visibility is deliberately left to the owner to specify, so the inputs are not shown here yet.
     */
    private Double trainingPercent;

    private Long playerId;
    private String playerName;
    private String role;
    private String directTrainingSkill;
    private boolean advancedTraining;
    private List<SkillDeltaDTO> skills = new ArrayList<>();
}

