package org.tifo.footballmanager.newLogic.model;

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
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One spell a manager spent at one club (owner, 2026-10-18).
 *
 * <p>The owner: *"manager treba da ima istoriju vodjenja klubova"*. It is what a takeover writes: the club
 * he leaves gets an end date, the club he joins gets a start, and neither row is ever edited again.
 *
 * <p><b>Closed, not updated.</b> A tenure is opened when a manager takes a club and closed when he leaves
 * it, so the history is a list of finished spells rather than a single row that gets rewritten. A row that
 * is rewritten is a row that loses the past, and the past is the whole point.
 *
 * <p><b>Real time and game time, both recorded.</b> The instants say when it happened to the person; the
 * season and week say where it happened in the football. A manager who took over in week 3 of season 1 and
 * left in week 9 should read as exactly that, and a season is twelve weeks counted from 1 — there is no
 * calendar year in this game.
 */
@Getter
@Setter
@Entity(name = "ManagerTenure")
@Table(name = "manager_tenure",
        indexes = @Index(name = "idx_manager_tenure_user", columnList = "user_id, started_at"))
public class ManagerTenure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private org.tifo.commonmanager.model.User user;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    /** Null while he is still there. */
    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "started_season")
    private Integer startedSeason;

    @Column(name = "started_week")
    private Integer startedWeek;

    @Column(name = "ended_season")
    private Integer endedSeason;

    @Column(name = "ended_week")
    private Integer endedWeek;

    /** Why it ended: MOVED for a takeover, and null while it has not. */
    @Column(name = "end_reason", length = 32)
    private String endReason;

    public boolean isOpen() {
        return endedAt == null;
    }
}
