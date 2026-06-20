package org.example.americanfootballmanager.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "af_match_fixtures")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AfMatchFixture {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "home_team_id")
    private AfTeam homeTeam;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "away_team_id")
    private AfTeam awayTeam;

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
    private AfMatch playedMatch;
}
