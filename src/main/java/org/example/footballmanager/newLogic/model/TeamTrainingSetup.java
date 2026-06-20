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

    private LocalDateTime updatedAt;
}