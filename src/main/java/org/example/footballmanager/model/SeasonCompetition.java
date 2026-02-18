package org.example.footballmanager.model;

import jakarta.persistence.*;

import java.util.List;

@Entity
public class SeasonCompetition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Integer seasonYear;

    @ManyToOne
    private Competition competition;

    @OneToMany(mappedBy = "seasonCompetition")
    private List<CompetitionEntry> entries;

    private Boolean finished;
}
