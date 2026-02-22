package org.example.footballmanager.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
public class CompetitionEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private SeasonCompetition seasonCompetition;

    @ManyToOne(fetch = FetchType.EAGER)
    private Team team;

    private Integer points;
    private Integer goalsScored;
    private Integer goalsConceded;
    private Integer position;
    private Integer wins = 0;
    private Integer draws = 0;
    private Integer losses = 0;
}
