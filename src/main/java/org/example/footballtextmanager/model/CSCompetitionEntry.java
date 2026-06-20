package org.example.footballtextmanager.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
public class CSCompetitionEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private CSSeasonCompetition csSeasonCompetition;

    @ManyToOne(fetch = FetchType.EAGER)
    private CTeam CTeam;

    private Integer points;
    private Integer goalsScored;
    private Integer goalsConceded;
    private Integer position;
    private Integer wins = 0;
    private Integer draws = 0;
    private Integer losses = 0;
}
