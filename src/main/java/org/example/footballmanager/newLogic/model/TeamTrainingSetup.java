package org.example.footballmanager.newLogic.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity(name = "TeamTrainingSetup")
@Getter
@Setter
public class TeamTrainingSetup {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private Team team;

    private Integer seasonNumber;
    private Integer weekNumber;

    private String dtSkillGk;
    private String dtSkillDef;
    private String dtSkillMid;
    private String dtSkillAtt;

    @Column(columnDefinition = "TEXT")
    private String advancedAssignmentsJson;

    /**
     * How hard the whole squad works this week (Sprint 4.2).
     *
     * <p>On the weekly setup rather than the club, because intensity is a weekly decision: a squad
     * rests after a European night and pushes again the week after. A player may be overridden -
     * see {@code PlayerTrainingIntensity} - for the case where he should not be treated like the rest.
     *
     * <p>Null means NORMAL. A missing setting must not take out a week of training for three hundred
     * clubs.
     */
    private String trainingIntensity;

    private LocalDateTime updatedAt;
}