package org.example.footballmanager.newLogic.model;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity(name = "Training")
public class Training {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    private Player player;

    private String formation;

    private boolean advanced;
}