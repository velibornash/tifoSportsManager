package org.tifo.footballmanager.newLogic.dto.training;

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

    /**
     * How hard the whole squad works this week: LIGHT, NORMAL or VERY_HARD.
     *
     * <p>It was written to the setup from the beginning and read by the training, but it had no
     * field here and no control on the screen, so a manager could not set it at all. It is the lever
     * over a player in a bad run - a light week doubles how fast his form comes back.
     */
    private String trainingIntensity;
}

