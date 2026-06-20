package org.example.footballmanager.newLogic.model;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@Entity(name = "Stadium")
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
    private Team owner;

    private Double ticketPrice;
    private Double pitchQuality;
    private Integer condition;
    private Integer trainingQuality;

    @OneToOne(mappedBy = "stadium")
    @EqualsAndHashCode.Exclude
    @JsonManagedReference
    private Team team;
}