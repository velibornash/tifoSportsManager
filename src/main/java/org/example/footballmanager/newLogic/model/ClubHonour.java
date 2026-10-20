package org.example.footballmanager.newLogic.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * One medal a club actually won (P2-TROPHY-1, the owner added the Clubs page medal row).
 *
 * <p><b>What the owner asked for:</b> on the Club page, in Milestones, a row of trophies — a medal
 * icon in a determined colour (gold, silver, bronze), and beneath it which competition and which season
 * (e.g. "Superliga Tier 1, season 1" or "Masters Cup, season 3").
 *
 * <p><b>Derived, not guessed.</b> Trophies were not stored anywhere; they were only derivable from
 * results. This record is written by
 * {@link org.example.footballmanager.newLogic.service.HonourService} from the same results — final
 * positions for leagues and the final / third-place fixtures for cups — so the medal colour is never
 * something nobody told the code about.
 */
@Entity
@Table(name = "club_honour",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_honour_club_comp_season_medal",
                columnNames = {"team_id", "competition_name", "season_year", "medal"}))
public class ClubHonour {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    /** The competition's display name when it was won, frozen so a rename does not rewrite history. */
    @Column(name = "competition_name", nullable = false)
    private String competitionName;

    @Column(name = "season_year", nullable = false)
    private int seasonYear;

    @Enumerated(EnumType.STRING)
    @Column(name = "medal", nullable = false)
    private Medal medal;

    protected ClubHonour() {}

    public ClubHonour(Team team, String competitionName, int seasonYear, Medal medal) {
        this.team = team;
        this.competitionName = competitionName;
        this.seasonYear = seasonYear;
        this.medal = medal;
    }

    public enum Medal { GOLD, SILVER, BRONZE }

    public Long getId() { return id; }
    public Team getTeam() { return team; }
    public String getCompetitionName() { return competitionName; }
    public int getSeasonYear() { return seasonYear; }
    public Medal getMedal() { return medal; }
}
