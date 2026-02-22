package org.example.footballmanager.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Entity
@Getter
@Setter
public class Competition {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private String name;
    // LEAGUE ili CUP
    @Enumerated(EnumType.STRING)
    private CompetitionType type;
    // NATIONAL ili INTERNATIONAL
    @Enumerated(EnumType.STRING)
    private CompetitionScope scope;
    // CLUB ili NATIONAL_TEAM
    @Enumerated(EnumType.STRING)
    private CompetitionTeamType teamType;
    // Ako je NATIONAL competition
    @ManyToOne(fetch = FetchType.EAGER)
    @JsonBackReference
    private Country country;
    // Tier u nacionalnoj piramidi (null za kup ili international)
    private Integer tier;
    // Redni broj paralelne lige unutar istog tier-a
    // (npr Tier 3 ima 4 lige)
    private Integer divisionLevel;
    private Integer teamsPerCompetition;
    private Boolean hasPlayoff;
    private Boolean hasPlayout;
    private Integer promotionSpots;
    private Integer relegationSpots;
    private Integer reputationWeight; // Koliko utiče na reputaciju
    private Boolean hasSeeding;
    private Integer seededTeamsCount;
    @OneToMany(mappedBy = "competition", fetch = FetchType.LAZY)
    @JsonIgnore
    private List<SeasonCompetition> seasons;
}
