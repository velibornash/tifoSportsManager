package org.example.footballmanager.newLogic.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Table(name = "season_competition",
        indexes = {
                @Index(name = "ix_season_competition_comp_season", columnList = "competition_id,season_year"),
        })
@Entity
@Getter
@Setter
public class SeasonCompetition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Integer seasonYear;

    @ManyToOne
    private Competition competition;

    @OneToMany(mappedBy = "seasonCompetition", fetch = FetchType.LAZY)
    @JsonIgnore   // ← sprečava SeasonCompetition → CompetitionEntry → SeasonCompetition ciklus
    private List<CompetitionEntry> entries;

    private Boolean finished;
}
