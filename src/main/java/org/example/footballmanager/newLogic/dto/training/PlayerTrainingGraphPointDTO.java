package org.example.footballmanager.newLogic.dto.training;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PlayerTrainingGraphPointDTO {
    private Integer seasonNumber;
    private Integer weekNumber;
    private String skill;
    private double value;
    private int integerValue;
    private String role;
    private String directTrainingSkill;
    private boolean advancedTraining;
}
