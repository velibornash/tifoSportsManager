package org.example.footballmanager.newLogic.dto.training;

import lombok.Data;

@Data
public class SkillDeltaDTO {
    private String skill;
    private double before;
    private double after;
    private double decimalChange;
    private int beforeInt;
    private int afterInt;
    private int integerChange;
}
