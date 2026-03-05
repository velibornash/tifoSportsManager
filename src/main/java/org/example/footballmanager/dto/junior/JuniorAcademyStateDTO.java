package org.example.footballmanager.dto.junior;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class JuniorAcademyStateDTO {
    private Long teamId;
    private String teamName;
    private int currentSeasonNumber;
    private int currentWeekNumber;
    private int juniorCoachSkill;
    private boolean decisionsOpen;
    private List<JuniorAcademyItemDTO> juniors = new ArrayList<>();
    private List<JuniorAcademyItemDTO> archive = new ArrayList<>();
}
