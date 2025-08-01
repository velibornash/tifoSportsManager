package org.example.footballmanager.model;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
public class Season {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "seasonYear")
    private Integer seasonYear;

    private String description;
}