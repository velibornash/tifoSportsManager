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
 * One pick, in one manager's squad, in one scored gameweek (owner, 2026-10-17).
 *
 * <p><b>This is the snapshot, and it is why scoring cannot be lazy.</b> A squad can be changed between
 * gameweeks, so a week scored from "whatever the squad is now" would let a manager wait until the
 * matches were played and then pick the players who had already scored. Writing a line per pick per week
 * at the moment the week is scored freezes both the points and who was picked to earn them, and a later
 * transfer cannot reach back into an earlier week.
 *
 * <p>{@link #starter} and {@link #captain} are copied here rather than read from the pick, for the same
 * reason: they are what the squad was that week, not what it is now.
 */
@Getter
@Setter
@Entity
@Table(name = "fantasy_score_line",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_fantasy_score_line_team_player_week",
                columnNames = {"fantasy_team_id", "player_id", "season_year", "week_number"}),
        indexes = @Index(name = "idx_fantasy_score_line_team_week",
                columnList = "fantasy_team_id, season_year, week_number"))
public class FantasyScoreLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "fantasy_team_id", nullable = false)
    private FantasyTeam fantasyTeam;

    @ManyToOne(optional = false)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @Column(name = "season_year", nullable = false)
    private Integer seasonYear;

    @Column(name = "week_number", nullable = false)
    private Integer weekNumber;

    /** What the player's gameweek was worth, before the armband. */
    @Column(name = "points", nullable = false)
    private int points;

    /** In the eleven that week. A substitute scores nothing. */
    @Column(name = "starter", nullable = false)
    private boolean starter;

    /** Wore the armband that week, so his points counted double. */
    @Column(name = "captain", nullable = false)
    private boolean captain;
}
