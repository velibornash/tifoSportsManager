package org.example.footballmanager.newLogic.dto.junior;

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

    /**
     * The academy's growth multiplier, 0.70-1.30, and a plain reading of it (Sprint 5.3).
     *
     * <p>On the payload because a manager is being charged a weekly upkeep for a youth setup and is
     * entitled to know what it is worth. Both inputs — the youth facility and the youth coach — were
     * previously written and never read.
     */
    private double academyQuality = 1.0;
    private String academyQualityLabel;
    private Integer youthFacilityLevel;
    private Integer youthCoachDevelopment;
    private List<JuniorAcademyItemDTO> juniors = new ArrayList<>();
    private List<JuniorAcademyItemDTO> archive = new ArrayList<>();
}
