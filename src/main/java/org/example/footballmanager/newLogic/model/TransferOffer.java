package org.example.footballmanager.newLogic.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import lombok.Data;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * An offer for a player, as a row rather than a sentence (Sprint 3.2).
 *
 * <p>This replaces {@code Transfer.interestedTeams}, a {@code Set<String>} of
 * {@code "Partizan offered €450000"} that was parsed back out with {@code indexOf(" offered €")} and
 * {@code replaceAll}. Two things were broken by that and both were real:
 *
 * <ul>
 *   <li><b>Offers were deduplicated by a prefix match on the club name.</b> A club named
 *       {@code Partizan} wiped the offers of {@code Partizan United Youth} — the strings are
 *       indistinguishable by prefix.</li>
 *   <li><b>The buyer was resolved by name</b>, via {@code teamRepository.findByName(...)}. Two clubs
 *       may share a name, and a lookup that can only ever return one of them is not an identity.</li>
 * </ul>
 *
 * <p>Fee, wage and contract length are three separate columns because they are three separate things
 * to negotiate. An offer is not a number.
 */
@Data
@Entity(name = "TransferOffer")
public class TransferOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private Transfer transfer;

    /** The buying club. This is the identity; the name is only ever a label for display. */
    @ManyToOne(fetch = FetchType.LAZY)
    private Team buyerTeam;

    /** Transfer fee, in euros. */
    private Double fee;

    /** Weekly wage the buyer is offering the player. */
    private Double wage;

    /** Contract length in years. */
    private Integer contractYears;

    /** 1 = opening bid, 2 = seller counter, 3 = buyer counter, and so on. */
    private Integer round = 1;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private OfferStatus status = OfferStatus.OPEN;

    /** Agent fee taken from the transfer fee, 2-5% of the fee. */
    private Double agentFee;

    private Instant createdAt = Instant.now();

    /** Offers do not sit open forever. */
    private Instant expiresAt;

    /**
     * Whether the player will personally accept these terms.
     *
     * <p>Separate from whether the *club* will accept: a player with a high wage demand can refuse a
     * move that meets the fee but not the wage, and that is the most common way a transfer talks
     * itself down at the last minute.
     */
    @Column(length = 400)
    private String playerObjection;

    public TransferOffer() { }

    /** Offers live for a week, which is one negotiation cycle. */
    public void defaultExpiry() {
        this.expiresAt = createdAt.plus(7, ChronoUnit.DAYS);
    }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    /** Total the selling club receives: fee less the agent's cut. */
    public double netToSeller() {
        double f = fee == null ? 0 : fee;
        return f - (agentFee == null ? 0 : agentFee);
    }
}
