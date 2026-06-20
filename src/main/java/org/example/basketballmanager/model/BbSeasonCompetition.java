package org.example.basketballmanager.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.example.commonmanager.model.CommonCompetition;
import org.example.commonmanager.model.CommonSeason;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "bb_season_competitions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BbSeasonCompetition {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "competition_id")
    private CommonCompetition competition;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "season_id")
    private CommonSeason season;

    @OneToMany(mappedBy = "seasonCompetition", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    @Builder.Default
    private List<BbCompetitionEntry> entries = new ArrayList<>();

    private Boolean finished = false;
}
