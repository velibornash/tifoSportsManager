package org.example.footballmanager.newLogic.dto.transfer;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class TransferDTO {
    private Long id;
    private Long playerId;
    private String playerName;
    private String position;
    private Integer age;
    private Integer rating;
    private Double playerValue;
    private Long sellerTeamId;
    private String sellerTeamName;
    private Long buyerTeamId;
    private String buyerTeamName;
    private Double askingPrice;
    private Double agreedPrice;
    private String status;
    private LocalDateTime listedAt;
    private LocalDateTime completedAt;
    /** Human-readable "who is interested" lines, for the market board. Not actionable. */
    private List<String> interestedTeams = new ArrayList<>();
    /**
     * The live bids on this player, with their ids. The seller accepts one of these by id — see
     * {@code POST /transfers/accept-offer/{playerId}/{offerId}}.
     */
    private List<TransferOfferDTO> offers = new ArrayList<>();
    private boolean ownedByViewer;
    private boolean buyableByViewer;
    private boolean removalAllowed;
    private boolean canAcceptOffer;
    private boolean canRejectOffer;
    /** At least one club has made a priced offer (an "X offered EUR Y" entry). */
    private boolean hasPricedOffer;
    /** The owning club can clear all interest/offers without accepting one (Sprint 0.2). */
    private boolean canClearInterest;
    private Boolean offerAccepted;
    private String actionMessage;
}
