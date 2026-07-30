package org.example.footballmanager.newLogic.dto.junior;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class JuniorPromotionResultDTO {
    private Long juniorId;
    private Long playerId;
    private String playerName;
    private String position;
    private int totalSkillBudget;
    private int remainingAfterFill;
    private Map<String, Integer> allocatedSkills = new LinkedHashMap<>();
    private List<String> allocationSequence = new ArrayList<>();
}
