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
 * A club advertising a free slot for a friendly (owner, 2026-10-06).
 *
 * <h2>An ad, not a request</h2>
 *
 * <p>A {@link FriendlyRequest} names one opponent and waits for that opponent's answer. This names
 * <b>nobody</b>: it says "week 11, day 7, I am free", and whoever wants it takes it. It is the difference
 * the owner described between asking a club and posting the slot for anyone.
 *
 * <h2>It states its season, week and day, and expires when that slot passes</h2>
 *
 * <p>The owner's rule: <i>"once the friendly slot passes, it expires. And since there are several slots
 * in the invitation, it has to say precisely which season/week/day it applies to."</i>
 *
 * <p>So the period is stored, not implied — a slot is not "sometime in week 11", it is week 11 day 7, and
 * a posting for one slot says so on the face of the row. **A week has more than one friendly slot** (the
 * league's two, and week 11 gives one of them away to the playoff), so a posting that named only a week
 * could be read as a claim on the wrong day.
 *
 * <p>Expiry is therefore the moment <b>the slot</b> passes, not the end of the week. A posting for day 3
 * is dead once day 3 is over, and one for day 7 is still live until then — which is the whole reason to
 * store the day.
 */
@Entity(name = "FriendlyOffer")
@Table(name = "friendly_offer",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_friendly_offer_slot",
                columnNames = {"offering_team_id", "season_year", "week_number", "day_number"}))
@Getter
@Setter
public class FriendlyOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The club advertising the slot. Only a human-run club may post one. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "offering_team_id", nullable = false)
    private Team offeringTeam;

    /** A season is twelve weeks counted from 1. There is no calendar year here. */
    @Column(name = "season_year", nullable = false)
    private Integer seasonYear;

    @Column(name = "week_number", nullable = false)
    private Integer weekNumber;

    /** The exact day this posting is about. Not implied by the week. */
    @Column(name = "day_number", nullable = false)
    private Integer dayNumber;

    /** The slot within that week, kept so the fixture's round number can be derived as for a request. */
    @Column(name = "slot", nullable = false)
    private Integer slot;

    @Column(nullable = false)
    private OfferStatus status = OfferStatus.OPEN;

    /** The fixture created when the slot was taken, so a fulfilled posting points at the match. */
    @Column(name = "filled_fixture_id")
    private Long filledFixtureId;

    @Column(name = "created_at")
    private Instant createdAt;

    /** Where a posting is in its life. */
    public enum OfferStatus {
        /** Advertised and still claimable. */
        OPEN,
        /** Taken: a fixture exists. */
        FULFILLED,
        /** Withdrawn by the club that posted it. */
        WITHDRAWN,
        /** The slot passed without being taken. */
        EXPIRED
    }
}