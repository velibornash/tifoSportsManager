package org.example.footballtextmanager.model;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
public class CSJunior {
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
    private Boolean archived = false;

    @Enumerated(EnumType.STRING)
    private CSJuniorStatus status = CSJuniorStatus.ACTIVE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private CTeam CTeam;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "promoted_player_id")
    private CPlayer promotedCPlayer;
}
