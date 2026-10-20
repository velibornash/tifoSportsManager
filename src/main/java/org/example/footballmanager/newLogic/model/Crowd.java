package org.example.footballmanager.newLogic.model;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
public class Crowd {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private int size;        // broj gledalaca
    private double enthusiasm; // 0.0 - 10.0, koliko navijači utiču na tim
    private double noiseLevel; // 0.0 - 10.0, utiče na golmana i igrače

}
