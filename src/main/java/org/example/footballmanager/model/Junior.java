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

    // kasnije dodamo skilove, poziciju itd.
}