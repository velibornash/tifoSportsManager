package org.tifo.footballmanager.newLogic.model;

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
 * What one player's gameweek was worth (owner, 2026-10-17).
 *
 * <p>Written once, when the gameweek is scored, and never recomputed. That is the point of the table: the
 * player-points view and every manager's total are read from here, so a page load does not re-derive
 * thirty thousand match rows and cannot disagree with the number it showed a minute ago.
 *
 * <p>Keyed by player, season and week together, so a season is a new set of rows and last season's points
 * are history. There is no end-of-season sweep to forget to run.
 */
@Getter
@Setter
@Entity
@Table(name = "fantasy_player_score",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_fantasy_player_score_player_week",
                columnNames = {"player_id", "season_year", "week_number"}),
        indexes = @Index(name = "idx_fantasy_player_score_week",
                columnList = "season_year, week_number, points"))
public class FantasyPlayerScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @Column(name = "season_year", nullable = false)
    private Integer seasonYear;

    @Column(name = "week_number", nullable = false)
    private Integer weekNumber;

    @Column(name = "points", nullable = false)
    private int points;
}
