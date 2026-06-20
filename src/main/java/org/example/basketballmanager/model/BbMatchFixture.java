package org.example.basketballmanager.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "bb_match_fixtures")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BbMatchFixture {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "home_team_id")
    private BbTeam homeTeam;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "away_team_id")
    private BbTeam awayTeam;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "competition_id")
    private org.example.commonmanager.model.CommonCompetition competition;

    @Column(name = "season_year")
    private Integer seasonYear;

    @Column(name = "round_number")
    private Integer roundNumber;

    @Column(name = "week_number")
    private Integer weekNumber;

    @Column(name = "match_date")
    private LocalDateTime matchDate;

    private Boolean played = false;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "played_match_id")
    private BbMatch playedMatch;
}
