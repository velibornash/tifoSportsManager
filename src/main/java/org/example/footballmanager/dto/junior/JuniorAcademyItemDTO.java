package org.example.footballmanager.dto.junior;

import lombok.Data;

@Data
public class JuniorAcademyItemDTO {
    private Long id;
    private String name;
    private int age;
    private double talent;
    private int academySkill;
    private double academySkillExact;
    private double lastWeeklyDelta;
    private String status;
    private int arrivalSeasonNumber;
    private int arrivalWeekNumber;
    private Long promotedPlayerId;
    private boolean archived;
}
