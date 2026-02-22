package org.example.footballmanager.model;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@Entity
public class Stadium {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private Integer capacity;
    private String location;
    @ManyToOne(fetch = FetchType.LAZY)
    @EqualsAndHashCode.Exclude
    @JsonManagedReference
    private Team owner; // opcionalno

    private Double ticketPrice;
    private Double pitchQuality;
    private Integer condition; // 0-100
    private Integer trainingQuality; // utiče na razvoj

    @OneToOne(mappedBy = "stadium")
    @EqualsAndHashCode.Exclude
    @JsonManagedReference
    private Team team;

}