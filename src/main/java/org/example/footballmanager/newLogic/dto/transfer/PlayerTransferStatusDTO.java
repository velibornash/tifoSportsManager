package org.example.footballmanager.newLogic.dto.transfer;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class PlayerTransferStatusDTO {
    private Long playerId;
    private Long currentTeamId;
    private String currentTeamName;
    private boolean listed;
    private String status;
    private Double askingPrice;
    private Double agreedPrice;
    private LocalDateTime listedAt;
    private LocalDateTime completedAt;
    private Long sellerTeamId;
    private String sellerTeamName;
    private Long buyerTeamId;
    private String buyerTeamName;
    private List<String> interestedTeams = new ArrayList<>();
    private boolean ownedByViewer;
    private boolean canList;
    private boolean canRemove;
    private boolean canBuyListed;
    private boolean canDirectBuy;
    private boolean canAcceptOffer;
    private boolean canRejectOffer;
    /** At least one club has made a priced offer (an "X offered EUR Y" entry). */
    private boolean hasPricedOffer;
    /** The owning club can clear all interest/offers without accepting one (Sprint 0.2). */
    private boolean canClearInterest;
    private String summary;
}
