package org.example.footballmanager.newLogic.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * One manager's gameweek, totalled (owner, 2026-10-18).
 *
 * <p><b>Why a team total exists when the score lines already add up to it.</b> The lines carry what each
 * <i>player</i> did; this carries what the <i>team</i> scored, which is the lines with the armband applied
 * and the transfer cost taken off. The cost is the reason the table exists at all: it belongs to the
 * gameweek and not to any player, so there is nowhere on a line to put it. Writing the total here also
 * makes the standings one query instead of an aggregate over every line of every squad.
 *
 * <p>Written once by the scoring pass and never recomputed, like everything else about a gameweek.
 */
@Getter
@Setter
@Entity
@Table(name = "fantasy_team_score",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_fantasy_team_score_team_week",
                columnNames = {"fantasy_team_id", "season_year", "week_number"}),
        indexes = @Index(name = "idx_fantasy_team_score_season",
                columnList = "season_year, week_number"))
public class FantasyTeamScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "fantasy_team_id", nullable = false)
    private FantasyTeam fantasyTeam;

    @Column(name = "season_year", nullable = false)
    private Integer seasonYear;

    @Column(name = "week_number", nullable = false)
    private Integer weekNumber;

    /** The eleven's points, with the captain doubled. What the squad earned before the cost. */
    @Column(name = "points", nullable = false)
    private int points;

    /** What this gameweek's changes cost, in points, already taken off {@link #points}. */
    @Column(name = "transfer_cost", nullable = false)
    private int transferCost;
}
