package org.example.footballmanager.newLogic.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(indexes = {
        @Index(name = "ix_competition_entry_sc_pos", columnList = "season_competition_id,position"),
        // findBySeasonCompetitionAndTeam runs inside loops during seeding (DatabaseInitializer:1226,1249,1270).
        @Index(name = "ix_competition_entry_team", columnList = "team_id")
})@Getter
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
