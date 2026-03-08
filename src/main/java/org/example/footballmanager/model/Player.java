package org.example.footballmanager.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
public class Player {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Embedded
    private Skills skills;

    private double talent;   // 3.0 top, 9.0 loš

    private int age;
    private double playerValue;
    private double earnings;
    private double height;   // u metrima
    private double weight;   // u kg
    private double form;     // 1.0 - 10.0
    private int rating;      // 1-100

    @Enumerated(EnumType.STRING)
    private Position position;

    private int totalGoals;
    private int totalAssists;
    private Integer squadNumber;
    private boolean injured;
    private Integer injuryDaysRemaining;
    private Integer injurySeasonNumber;
    private Integer injuryWeekNumber;

    @ManyToOne
    @JoinColumn(name = "team_id")
    @JsonBackReference   // ← Ovo je back deo (Player → Team), sprečava ciklus
    private Team team;


    // Helper methods ostaju isti
    public Position getPositionEnum() {
        return position;
    }

    public double getCurrentFatigue() {
        return skills.getFatigue() * 0.7 + (10.0 - form) * 0.3;
    }

    public boolean canReceiveBall() {
        return skills.getStamina() > 0 && getCurrentFatigue() < 8.0;
    }

    public boolean isInjured() {
        return getInjuryDaysRemaining() > 0;
    }

    public int getInjuryDaysRemaining() {
        return injuryDaysRemaining == null ? 0 : injuryDaysRemaining;
    }

}
