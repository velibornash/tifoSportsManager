package org.example.footballmanager.newLogic.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * A player's disciplinary record for one season, in one competition or across the club.
 *
 * <p><b>Two kinds of ban live here, and they are told apart by the competition.</b> A null competition is
 * the club-wide row, and it is where a <b>red card</b> goes: the owner specified that a red card bans the
 * player from the <b>first next official match</b> — everything except a friendly. A competition row holds
 * the <b>yellow accumulation</b>, and the bans it earns are served in that competition only.
 *
 * <p><b>Keyed by season, so the reset is structural.</b> There is no end-of-season sweep to forget to run
 * and no counter that survives into next year: a new season is a new row, and last season's record is a
 * matter of history rather than something that can leak into a fresh campaign.
 *
 * <p><b>Yellows accumulate and the counter resets only when the bans are served.</b> That is what makes all
 * three of the owner's thresholds reachable. Resetting the moment three are reached would mean six and
 * nine could never be hit at all.
 */
@Data
@Entity
@Table(name = "player_discipline",
        uniqueConstraints = {
                // A club-wide row is the one with a null competition, and Postgres treats nulls as distinct
                // in a unique index — so this constraint governs the competition rows only, and the
                // club-wide row is found by query rather than by constraint.
                @UniqueConstraint(name = "uk_discipline_player_season_competition",
                        columnNames = {"player_id", "season", "competition_id"})
        },
        indexes = {
                @Index(name = "ix_discipline_player_season", columnList = "player_id,season")
        })
public class PlayerDiscipline {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    /** Seasons are twelve weeks counted from 1; there is no calendar year anywhere in this game. */
    @Column(name = "season", nullable = false)
    private Integer season;

    /** Null is the club-wide row — where red-card bans live. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "competition_id")
    private Competition competition;

    /** Yellows accumulated since the last completed ban cycle. */
    @Column(name = "yellow_cards", nullable = false)
    private int yellowCards;

    /** Bans earned and not yet served, from a red card or from the yellow accumulation. */
    @Column(name = "bans_owed", nullable = false)
    private int bansOwed;

    /** Bans from the current yellow cycle already served, so the counter knows when to reset. */
    @Column(name = "bans_served", nullable = false)
    private int bansServed;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * How many league matches the owner's thresholds have earned.
     *
     * <p>3 yellows → 1 match, 6 → 2, 9 → 3. Below three, nothing. The bands are inclusive of the higher
     * figure so a player on seven yellows owes two, not one-and-a-bit.
     */
    public static int leagueBansEarnedBy(int yellowCards) {
        if (yellowCards >= 9) return 3;
        if (yellowCards >= 6) return 2;
        if (yellowCards >= 3) return 1;
        return 0;
    }

    /** Yellows still needed before the next ban is earned, or 0 when the current cycle is already served. */
    public int yellowsUntilNextBan() {
        int earned = leagueBansEarnedBy(yellowCards);
        if (bansServed >= earned) {
            // The next band: three more if we are past a completed cycle.
            int nextThreshold = (earned + 1) * 3;
            return Math.max(0, nextThreshold - yellowCards);
        }
        return 0;
    }
}