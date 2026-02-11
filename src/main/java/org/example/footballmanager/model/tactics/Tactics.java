package org.example.footballmanager.model.tactics;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
public class Tactics {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name; // npr. "Attacking", "Defensive", "Balanced"

    private double aggression;    // 0.0 - 10.0
    private double defenseLine;   // 0.0 - 10.0
    private double pressing;      // 0.0 - 10.0
    private double possession;    // 0.0 - 10.0
    private double counterAttack; // 0.0 - 10.0
    private double ballControl;   // 0.0 - 10.0

    @ManyToOne(cascade = CascadeType.ALL)
    private Formation formation;
}
