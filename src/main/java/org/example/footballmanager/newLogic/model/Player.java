package org.example.footballmanager.newLogic.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity(name = "Player")
public class Player {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Embedded
    private Skills skills;

    private double talent;

    private int age;
    private double playerValue;
    private double earnings;
    private double height;
    private double weight;
    private double form;
    private int rating;

    @Enumerated(EnumType.STRING)
    private Position position;

    private int totalGoals;
    private int totalAssists;
    private Integer squadNumber;
    private boolean injured;
    private Integer injuryDaysRemaining;
    private Integer injurySeasonNumber;
    private Integer injuryWeekNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    @JsonBackReference
    private Team team;

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

    // Fluent accessors for MatchSimulator compatibility
    public Long id() { return this.id; }
    public String name() { return this.name; }
    public Position position() { return position; }
    public Skills skills() { return skills; }
    public double fatigue() { return skills != null ? skills.getFatigue() : 0; }
    public int fatigueInt() { return (int) fatigue(); }

    public void addFatigue(int amount) {
        skills.setFatigue(skills.getFatigue() + amount);
    }

    public double movementModifier() {
        return skills.movementModifier((int) getCurrentFatigue());
    }
}