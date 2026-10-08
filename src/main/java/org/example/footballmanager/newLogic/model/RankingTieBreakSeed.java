package org.example.footballmanager.newLogic.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * The coin a ranking ladder is settled by, once, and kept (owner, 2026-10-08).
 *
 * <p>The owner, on equal totals: distinct positions, never a shared rank, never alphabetical. Two clubs
 * on 1,240 points are not tied in football — the ledger has run out of things to separate them — so the
 * only honest answer left is a draw, and the draw has to be the <b>same</b> every time the ladder is read.
 * A coin re-rolled per read is a table that reorders itself while nobody is watching, which is the same
 * defect {@link NationalGroupTieBreak} exists to prevent for a group table.
 *
 * <p>Stored rather than derived on every read, for the same reason and with the same trade: a derived seed
 * would be one table fewer, but then the record of the draw is a piece of arithmetic, and a stored one can
 * be shown and audited. The seed is written the first time a ladder needs it, so a fresh install and a
 * restored backup land on the same coin.
 *
 * <p>Cleared by Reset DB like everything else: the reset keeps three tables by name and takes the rest
 * from the catalogue, so a ladder's coin from a previous world does not survive into the next one.
 */
@Entity(name = "RankingTieBreakSeed")
@Table(name = "ranking_tie_break_seed",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_ranking_tie_break",
                columnNames = {"scope", "season_year", "subject_key"}))
@Getter
@Setter
public class RankingTieBreakSeed {

    /** Which ladder this coin settles. */
    public enum Scope {
        /** The national ranking — one coin for the whole world list. */
        COUNTRY,
        /** A country's club ladder — one coin per country. */
        CLUB
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false)
    private Scope scope;

    /** A season is twelve weeks counted from 1. There is no calendar year here. */
    @Column(name = "season_year", nullable = false)
    private Integer seasonYear;

    /**
     * What else the coin belongs to: the country id for a club ladder, empty for the national one.
     *
     * <p>Empty rather than null because it is part of a unique constraint and a null in a unique index is
     * not equal to another null in every database this code runs in.
     */
    @Column(name = "subject_key", nullable = false)
    private String subjectKey = "";

    /** The seed. Reusing it is what makes the draw a fact rather than a re-roll. */
    @Column(name = "seed", nullable = false)
    private Long seed;

    @Column(name = "drawn_at")
    private Instant drawnAt;
}