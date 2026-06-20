package org.example.footballtextmanager.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Entity
@Getter
@Setter
public class CSCountry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true)
    private String name;
    @Column(nullable = false, unique = true, length = 3)
    private String isoCode; // SRB, ENG, GER...
    private String flagImagePath;
    private String currencyCode; // RSD, EUR...
    // Reputacija države (bitno za međunarodna mesta)
    private Integer reputation;
    // Kvalitet generisanja juniora
    private Integer youthRating;
    // --- RELACIJE ---
    @OneToMany(mappedBy = "csCountry", fetch = FetchType.LAZY)
    @JsonIgnore
    private List<CSCompetition> CSCompetitions;
    @OneToMany(mappedBy = "csCountry")
    @JsonIgnore
    private List<CTeam> clubs;
    @OneToOne
    private CTeam seniorNationalCTeam;
    @OneToOne
    private CTeam u21NationalCTeam;
}
