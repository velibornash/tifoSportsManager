package org.tifo.footballmanager.newLogic.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.tifo.commonmanager.model.User;

import java.time.LocalDateTime;

/**
 * One manager's fantasy entry in one country's league, for one season (owner, 2026-10-17).
 *
 * <p><b>Scoped to a country, not to a club.</b> The owner's decision: the fantasy league is per country
 * ("zasad neka bude per country"), so a manager joins Serbia's league and picks from Serbia's whole
 * pyramid. He does not have to own a club there, and nothing about his club affects his entry.
 *
 * <p><b>Keyed by season, so the reset is structural.</b> A new season is a new row and last season's
 * entry is history, the same way {@code PlayerDiscipline} is keyed. There is no end-of-season sweep that
 * can be forgotten: a fantasy season is twelve gameweeks and then it is over.
 *
 * <p>The unique constraint is on the manager, the country and the season together, so a manager has one
 * entry per league per season and cannot enter twice. A leaderboard is a fraud magnet and this is the
 * cheapest place to close it.
 */
@Getter
@Setter
@Entity
@Table(name = "fantasy_team",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_fantasy_team_user_country_season",
                columnNames = {"user_id", "country_id", "season_year"}),
        indexes = @Index(name = "idx_fantasy_team_country_season", columnList = "country_id, season_year"))
public class FantasyTeam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(optional = false)
    @JoinColumn(name = "country_id", nullable = false)
    private Country country;

    @Column(name = "season_year", nullable = false)
    private Integer seasonYear;

    /**
     * The first gameweek this entry scores in (owner, 2026-10-17).
     *
     * <p>A manager can join at any time, but <b>he cannot join into a gameweek that has already kicked
     * off.</b> If he saves his first squad while the current week is still open he scores from that week;
     * if he saves it while the week is locked — the matches are played or already computed — he scores from
     * the next one. Without this a manager could arrive after the football, pick the players who had
     * already scored, and be awarded their points.
     */
    @Column(name = "scores_from_week", nullable = false)
    private Integer scoresFromWeek;

    /**
     * The squad this manager started the current gameweek with, as a comma-separated list of player ids
     * (owner, 2026-10-18).
     *
     * <p><b>A change is counted against this, not against the last save.</b> Counting against the last save
     * makes an undo cost twice, and a manager who tries something and changes his mind would be punished
     * for it. Fifteen ids in one column rather than fifteen rows in a table: the baseline is read and
     * written whole, never queried by player, and a table would be a fourth join for something that is
     * always used in one piece.
     *
     * <p>{@link #baselineWeek} says which gameweek the list belongs to, so it is replaced when the week
     * rolls over and never has to be cleared by a sweep that can be forgotten.
     */
    @Column(name = "baseline_players", length = 512)
    private String baselinePlayers;

    /** The gameweek {@link #baselinePlayers} was captured for. */
    @Column(name = "baseline_week")
    private Integer baselineWeek;

    /**
     * What the current gameweek's changes cost, in points. Zero for the first free change.
     *
     * <p><b>The default is in the column definition on purpose.</b> Hibernate's {@code ddl-auto=update}
     * emits {@code add column … not null} with no default, and PostgreSQL refuses that on a table that
     * already has rows. Hibernate then logs a warning and carries on, so the column is never created, the
     * application starts perfectly, and the failure only appears the first time somebody saves a squad —
     * which is how this was found. With a default the statement succeeds on a populated table and existing
     * rows get the honest value: no cost recorded.
     */
    @Column(name = "transfer_cost", nullable = false, columnDefinition = "integer not null default 0")
    private int transferCost;

    /** The gameweek {@link #transferCost} was charged for. */
    @Column(name = "transfer_cost_week")
    private Integer transferCostWeek;

    @Column(name = "joined_at")
    private LocalDateTime joinedAt;

    @PrePersist
    void onCreate() {
        if (joinedAt == null) {
            joinedAt = LocalDateTime.now();
        }
    }
}
