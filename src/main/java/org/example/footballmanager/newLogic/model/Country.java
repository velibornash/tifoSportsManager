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
    @OneToOne
    private Team seniorNationalTeam;
    @OneToOne
    private Team u21NationalTeam;
}