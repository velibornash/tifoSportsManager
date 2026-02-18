package org.example.footballmanager.model;

import jakarta.persistence.*;

@Entity
public class CompetitionEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    private SeasonCompetition seasonCompetition;

    @ManyToOne
    private Team team;

    private Integer points;
    private Integer goalsScored;
    private Integer goalsConceded;
    private Integer position;
}
