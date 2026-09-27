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

    /**
     * The exact talent, <b>revealed by promoting him</b> (Sprint 5.2).
     *
     * <p>Null for a viewer without a PLUS subscription — the reveal is the paid moment, so a
     * non-subscriber gets the skills and keeps the ceiling secret.
     *
     * <p>This field was <b>missing entirely</b>, which is why the promotion screen could not show the
     * talent it was named for: the rule had been implemented on {@code JuniorAcademyItemDTO} and the
     * reveal screen reads this DQR instead. Same shape of bug as the unwired {@code PlusFeatureService}
     * — the rule exists on the path nobody looks at.
     */
    private Double talent;
    private int totalSkillBudget;
    private int remainingAfterFill;
    private Map<String, Integer> allocatedSkills = new LinkedHashMap<>();
    private List<String> allocationSequence = new ArrayList<>();
}
