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

    /**
     * The mechanism, as numbers rather than as copy (owner, 2026-10-08).
     *
     * <p>The owner asked for the rules to be legible: when juniors arrive, how many, and whether he
     * can be revealed on arrival. Every one of those was previously a sentence in the JavaScript, or
     * worse a hardcoded literal — {@code MAX_ACTIVE_JUNIORS} alone was written into
     * {@code static/js/pages/features/academy.js} four separate times, and the decision window's
     * weeks were open-coded in the same file as {@code week >= 1 && week <= 2}. Changing a rule here
     * would not have changed the screen, which is the failure this block exists to remove.
     *
     * <p>They are read from the service's own constants at request time, so the screen and the rules
     * cannot drift apart.
     */
    private int intakeWeek;
    private int intakeMinCount;
    private int intakeMaxCount;
    private int decisionWeek;
    private int maxActiveJuniors;
}
