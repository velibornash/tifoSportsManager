package org.example.footballmanager.newLogic.dto.training;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class PlayerTrainingReportDTO {
    private Long playerId;
    private String playerName;
    private String role;
    private String directTrainingSkill;
    private boolean advancedTraining;
    private List<SkillDeltaDTO> skills = new ArrayList<>();
}

