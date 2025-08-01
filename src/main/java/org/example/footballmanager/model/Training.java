package org.example.footballmanager.model;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
public class Training {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    private Player player;

    private String formation; // npr "GK", "DEF", "MID", "ATT"

    private boolean advanced;
}