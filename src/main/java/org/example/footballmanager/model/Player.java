package org.example.footballmanager.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

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

    @ManyToOne(cascade = CascadeType.PERSIST)
    @JoinColumn(name = "team_id")
    private Team team;

    // ========================
    // Helper methods
    // ========================

    public Position getPositionEnum() {
        return position; // sada je već enum
    }

    /**
     * Trenutni umor igrača = Fatigue skill + form
     */
    public double getCurrentFatigue() {
        return skills.getFatigue() * 0.7 + (10.0 - form) * 0.3;
    }

    /**
     * Provera da li igrač može da primi loptu
     */
    public boolean canReceiveBall() {
        return skills.getStamina() > 0 && getCurrentFatigue() < 8.0;
    }
}
