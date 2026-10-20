package org.tifo.footballmanager.newLogic.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * A club offering a manager its job (owner, 2026-10-18).
 *
 * <p>An AI club from a higher tier offers a human manager in a lower one the chance to take over. He may
 * accept or decline, and it lapses after {@link #expiresAt}.
 *
 * <p><b>It expires in real hours, not game hours.</b> The game clock is real time plus an offset the admin
 * can move, so a deadline measured in game time can be jumped past with one press of Advance Week — every
 * open offer would expire at once and the manager would never have seen them. Twenty-four real hours is a
 * day the person actually has.
 *
 * <p><b>{@link #fromTeam} is recorded, and it is not derivable later.</b> It is the club he was managing
 * when the offer arrived, which is what the history needs on the day he accepts — by then his
 * {@code User.footballTeam} points at the new club and the old one is only a memory.
 */
@Getter
@Setter
@Entity(name = "JobOffer")
@Table(name = "job_offer",
        indexes = {
                @Index(name = "idx_job_offer_user_status", columnList = "user_id, status"),
                @Index(name = "idx_job_offer_expiry", columnList = "status, expires_at")
        })
public class JobOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The manager being offered the job. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private org.tifo.commonmanager.model.User user;

    /** The club doing the offering. Always an AI club, always a higher tier than {@link #fromTeam}. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "offering_team_id", nullable = false)
    private Team offeringTeam;

    /** The club he was managing when it arrived. Null if he had none. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "from_team_id")
    private Team fromTeam;

    /** Reuses the transfer market's vocabulary: OPEN, ACCEPTED, REJECTED, EXPIRED. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private OfferStatus status = OfferStatus.OPEN;

    @Column(name = "offered_at", nullable = false)
    private Instant offeredAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Column(name = "offered_season")
    private Integer offeredSeason;

    @Column(name = "offered_week")
    private Integer offeredWeek;

    public boolean isOpen() {
        return status == OfferStatus.OPEN;
    }

    /** Whether it has lapsed, judged against real time rather than the game clock. */
    public boolean hasExpired(Instant now) {
        return isOpen() && expiresAt != null && !now.isBefore(expiresAt);
    }
}
