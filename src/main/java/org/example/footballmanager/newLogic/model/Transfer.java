package org.example.footballmanager.newLogic.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity(name = "Transfer")
public class Transfer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    private Player player;

    @ManyToOne(fetch = FetchType.LAZY)
    private Team sellerTeam;

    @ManyToOne(fetch = FetchType.LAZY)
    private Team buyerTeam;

    @Enumerated(EnumType.STRING)
    private TransferStatus status;

    private double askingPrice;
    private Double agreedPrice;
    private LocalDateTime listedAt;
    private LocalDateTime completedAt;

    /**
     * The player's objection to being on the list, or {@link ListingObjection#NONE}.
     *
     * <p><b>This deliberately survives a re-listing.</b> The row is unique per player and is recycled
     * on every listing, so clearing this in the listing path would mean "reject the bids, take him
     * off the list, put him straight back" was a way to wipe a player's objection for free — and the
     * whole mechanic exists to stop a club doing exactly that. It is cleared only by resolving it.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private ListingObjection listingObjection = ListingObjection.NONE;

    /** Said out loud, so a refusal is never just a refusal. */
    @Column(length = 400)
    private String listingObjectionReason;
}