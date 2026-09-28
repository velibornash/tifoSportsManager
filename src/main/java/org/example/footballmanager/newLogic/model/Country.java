package org.example.footballmanager.newLogic.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Entity(name = "Country")
@Getter
@Setter
public class Country {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true)
    private String name;
    @Column(nullable = false, unique = true, length = 3)
    private String isoCode;
    private String flagImagePath;
    private String currencyCode;
    private Integer reputation;
    private Integer youthRating;

    @OneToMany(mappedBy = "country", fetch = FetchType.LAZY)
    @JsonIgnore
    private List<Competition> competitions;
    @OneToMany(mappedBy = "country")
    @JsonIgnore
    private List<Team> clubs;
    /**
     * Ignored in JSON deliberately: {@code Team} holds a back-reference to {@code Country},
     * so serialising this pair is a cycle. It stayed hidden while both columns were null in
     * every country; the first real national team turned the country page into truncated JSON
     * that the browser could not parse. Ignored here, at the Country side, so that
     * {@code Team.country} stays serialisable for the pages that already read it.
     */
    @OneToOne
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Team seniorNationalTeam;
    /**
     * Ignored in JSON deliberately: {@code Team} holds a back-reference to {@code Country},
     * so serialising this pair is a cycle. It stayed hidden while both columns were null in
     * every country; the first real national team turned the country page into truncated JSON
     * that the browser could not parse. Ignored here, at the Country side, so that
     * {@code Team.country} stays serialisable for the pages that already read it.
     */
    @OneToOne
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Team u21NationalTeam;
}