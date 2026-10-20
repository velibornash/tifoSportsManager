package org.example.footballtextmanager.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Entity
@Getter
@Setter
public class CSSeasonCompetition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Integer seasonYear;

    @ManyToOne
    private CSCompetition csCompetition;

    @OneToMany(mappedBy = "csSeasonCompetition", fetch = FetchType.LAZY)
    @JsonIgnore   // ← sprečava CSSeasonCompetition → CSCompetitionEntry → CSSeasonCompetition ciklus
    private List<CSCompetitionEntry> entries;

    private Boolean finished;
}
