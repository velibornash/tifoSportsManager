package org.example.footballtextmanager.model;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@Entity
public class CSStadium {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private Integer capacity;
    private String location;
    @ManyToOne(fetch = FetchType.LAZY)
    @EqualsAndHashCode.Exclude
    @JsonManagedReference
    private CTeam owner; // opcionalno

    private Double ticketPrice;
    private Double pitchQuality;
    private Integer condition; // 0-100
    private Integer trainingQuality; // utiče na razvoj

    @OneToOne(mappedBy = "csStadium")
    @EqualsAndHashCode.Exclude
    @JsonManagedReference
    private CTeam CTeam;

}