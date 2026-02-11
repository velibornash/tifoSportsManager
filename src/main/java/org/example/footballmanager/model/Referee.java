package org.example.footballmanager.model;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
public class Referee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private double strictness;  // 0.0 - 10.0, veća vrednost → više kartona
    private double leniency;    // 0.0 - 10.0, manja vrednost → manje tolerancije
    private double experience;  // 0.0 - 10.0, utiče na kontrolu utakmice

}
