package org.example.footballmanager.newLogic.dto.training;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class TrainingSetupDTO {
    private Long teamId;
    private Integer seasonNumber;
    private Integer weekNumber;
    private Map<String, String> groupSkills;
    private List<AdvancedAssignmentDTO> advancedAssignments = new ArrayList<>();
}

