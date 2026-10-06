package org.example.footballmanager.newLogic.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * The coin a group was settled by, once, and kept (owner, 2026-10-06).
 *
 * <p>The owner: a group tie is broken by <b>points, goal difference, goals scored, zreb</b>. The first
 * three are arithmetic on the results. The fourth is a draw, and a draw that is re-rolled every time
 * the standings are read is not a draw — it is a table that reorders itself while nobody is watching.
 *
 * <p>So the seed is written once per group, the first time the group needs it, and read from here
 * afterwards. That makes the draw <b>replayable</b>: the same group, asked a month later, gives the
 * same answer, which is what "decided by a coin" has to mean for the result to be worth anything.
 *
 * <p>Stored rather than derived on every read, deliberately. It could be derived from the competition
 * and season alone, and that would be one table fewer — but then the record of the draw is a piece of
 * arithmetic, and a stored seed can be shown on the screen and audited against. The cost is that this
 * row and the standings can disagree if one is written without the other, so both are written in the
 * same place.
 */
@Entity(name = "NationalGroupTieBreak")
@Table(name = "national_group_tie_break",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_national_group_tie_break",
                columnNames = {"competition_id", "season_year", "group_code"}))
@Getter
@Setter
public class NationalGroupTieBreak {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "competition_id", nullable = false)
    private Competition competition;

    /** A season is twelve weeks counted from 1. There is no calendar year here. */
    @Column(name = "season_year", nullable = false)
    private Integer seasonYear;

    /** The group this coin settled, matching {@link MatchFixture#getGroupCode()}. */
    @Column(name = "group_code", nullable = false)
    private String groupCode;

    /** The seed. Reusing it is what makes the draw a fact rather than a re-roll. */
    @Column(name = "seed", nullable = false)
    private Long seed;

    @Column(name = "drawn_at")
    private Instant drawnAt;
}