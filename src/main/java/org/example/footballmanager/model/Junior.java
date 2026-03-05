package org.example.footballmanager.model;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
public class Junior {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private int age;
    private double talent;
    private int academySkill;
    private double academySkillExact;
    private double lastWeeklyDelta;
    private int arrivalSeasonNumber;
    private int arrivalWeekNumber;

    @Enumerated(EnumType.STRING)
    private JuniorStatus status = JuniorStatus.ACTIVE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "promoted_player_id")
    private Player promotedPlayer;
}
