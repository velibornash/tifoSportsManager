package org.example.footballmanager.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Entity
@Getter
@Setter
public class Country {

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

    @OneToMany(mappedBy = "country")
    private List<Competition> competitions;

    @OneToMany(mappedBy = "country")
    private List<Team> clubs;

    @OneToOne
    private Team seniorNationalTeam;

    @OneToOne
    private Team u21NationalTeam;
}
