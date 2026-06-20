package org.example.americanfootballmanager.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "af_players")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AfPlayer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    private Position position;

    @Column(name = "jersey_number")
    private Integer jerseyNumber;

    @Column(nullable = false)
    private Boolean injured = false;

    @Column(name = "injury_days_remaining")
    private Integer injuryDaysRemaining = 0;

    @Column(nullable = false)
    private Integer fatigue = 0;

    @Column(name = "skill_stamina", nullable = false)
    private Integer skillStamina;

    @Column(name = "skill_strength", nullable = false)
    private Integer skillStrength;

    @Column(name = "skill_pace", nullable = false)
    private Integer skillPace;

    @Column(name = "skill_playmaking", nullable = false)
    private Integer skillPlaymaking;

    @Column(name = "skill_passing", nullable = false)
    private Integer skillPassing;

    @Column(name = "skill_running", nullable = false)
    private Integer skillRunning;

    @Column(name = "skill_tackling", nullable = false)
    private Integer skillTackling;

    @Column(name = "skill_shooting", nullable = false)
    private Integer skillShooting;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", nullable = false)
    private AfTeam team;

    @Embedded
    private AfPlayerStats stats;

    public enum Position {
        QB, RB, WR, TE, OL, DE, DT, LB, CB, S, K, P
    }

    public int getOverall() {
        return switch (position) {
            case QB -> (skillStamina * 10 + skillStrength * 5 + skillPace * 10 + skillPlaymaking * 25 + skillPassing * 30 + skillRunning * 5 + skillTackling * 5 + skillShooting * 10) / 100;
            case RB -> (skillStamina * 15 + skillStrength * 15 + skillPace * 20 + skillPlaymaking * 5 + skillPassing * 5 + skillRunning * 30 + skillTackling * 5 + skillShooting * 5) / 100;
            case WR -> (skillStamina * 10 + skillStrength * 10 + skillPace * 25 + skillPlaymaking * 15 + skillPassing * 5 + skillRunning * 25 + skillTackling * 5 + skillShooting * 5) / 100;
            case TE -> (skillStamina * 15 + skillStrength * 20 + skillPace * 15 + skillPlaymaking * 10 + skillPassing * 5 + skillRunning * 10 + skillTackling * 15 + skillShooting * 10) / 100;
            case OL -> (skillStamina * 15 + skillStrength * 30 + skillPace * 5 + skillPlaymaking * 15 + skillPassing * 5 + skillRunning * 5 + skillTackling * 20 + skillShooting * 5) / 100;
            case DE -> (skillStamina * 15 + skillStrength * 25 + skillPace * 20 + skillPlaymaking * 5 + skillPassing * 5 + skillRunning * 5 + skillTackling * 25 + skillShooting * 0) / 100;
            case DT -> (skillStamina * 10 + skillStrength * 35 + skillPace * 5 + skillPlaymaking * 5 + skillPassing * 5 + skillRunning * 5 + skillTackling * 30 + skillShooting * 5) / 100;
            case LB -> (skillStamina * 15 + skillStrength * 20 + skillPace * 15 + skillPlaymaking * 10 + skillPassing * 5 + skillRunning * 10 + skillTackling * 25 + skillShooting * 0) / 100;
            case CB -> (skillStamina * 10 + skillStrength * 10 + skillPace * 30 + skillPlaymaking * 10 + skillPassing * 5 + skillRunning * 15 + skillTackling * 20 + skillShooting * 0) / 100;
            case S -> (skillStamina * 15 + skillStrength * 10 + skillPace * 20 + skillPlaymaking * 20 + skillPassing * 5 + skillRunning * 10 + skillTackling * 20 + skillShooting * 0) / 100;
            case K -> (skillStamina * 5 + skillStrength * 10 + skillPace * 5 + skillPlaymaking * 5 + skillPassing * 5 + skillRunning * 5 + skillTackling * 5 + skillShooting * 60) / 100;
            case P -> (skillStamina * 5 + skillStrength * 10 + skillPace * 5 + skillPlaymaking * 5 + skillPassing * 20 + skillRunning * 5 + skillTackling * 5 + skillShooting * 45) / 100;
        };
    }
}
