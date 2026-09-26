package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.OfferStatus;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.SquadRole;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.Transfer;
import org.example.footballmanager.newLogic.model.TransferOffer;
import org.example.footballmanager.newLogic.repository.TransferOfferRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Multi-round negotiation for a transfer (Sprint 3.2).
 *
 * <p>Replaces the prose-string offer model. The old one kept offers as
 * {@code "Partizan offered €450000"} in a {@code Set<String>} and parsed them back with
 * {@code indexOf(" offered €")}, deduplicating by a prefix match on the club name and resolving the
 * buyer with {@code findByName}. A club named {@code Partizan} therefore wiped the offers of
 * {@code Partizan United Youth}, and a buyer was identified by a label that is not unique.
 *
 * <h2>Three terms, negotiated separately</h2>
 * <b>Fee</b>, <b>wage</b> and <b>contract length</b> are independent. They move in opposite
 * directions in real deals: the fee is about what the seller gets, the wage and length about what the
 * player gets, and a club that agrees a high fee does not thereby have the player. A single number
 * cannot express a deal where the two sides have agreed the fee and are still stuck on the wage.
 *
 * <h2>The player is a third party</h2>
 * A move can be agreed between two clubs and still fail, because the player refuses the personal
 * terms. {@link PlayerContractService#wageDemand} supplies the number, and an offer that meets the
 * fee but misses the wage is recorded as agreed-but-refused rather than silently completed.
 */
@Service
public class NegotiationService {

    /** Rounds before both sides walk away. Football does not have infinite haggling. */
    public static final int MAX_ROUNDS = 5;

    /** Agent takes 2-5% of the fee. */
    private static final double AGENT_MIN = 0.02;
    private static final double AGENT_MAX = 0.05;

    private final TransferOfferRepository offers;
    private final PlayerContractService contracts;
    private final TransferBudgetService budgets;

    public NegotiationService(TransferOfferRepository offers,
                              PlayerContractService contracts,
                              TransferBudgetService budgets) {
        this.offers = offers;
        this.contracts = contracts;
        this.budgets = budgets;
    }

    // ------------------------------------------------------------------ opening

    /** A club opens the bidding. */
    @Transactional
    public TransferOffer openOffer(Transfer transfer, Team buyer,
                                   double fee, double wage, int contractYears) {
        if (transfer == null || buyer == null) return null;

        // A club cannot bid twice for the same player in the same transfer.
        offers.findByTransferIdAndBuyerTeamIdAndStatusIn(transfer.getId(), buyer.getId(),
                List.of(OfferStatus.OPEN, OfferStatus.COUNTERED)).ifPresent(existing -> {
            throw new IllegalStateException(buyer.getName() + " already has a live offer on this player.");
        });

        TransferOffer offer = new TransferOffer();
        offer.setTransfer(transfer);
        offer.setBuyerTeam(buyer);
        offer.setRound(1);
        applyTerms(offer, transfer, fee, wage, contractYears);
        return offers.save(offer);
    }

    // ------------------------------------------------------------------ countering

    /**
     * The seller counters. Rises the fee and improves the personal terms by a share of the gap, which
     * is what actually happens: the fee is where the seller has leverage, the wage is where they do
     * not.
     */
    @Transactional
    public TransferOffer counter(TransferOffer offer, double fee, double wage, Integer contractYears) {
        if (offer == null || !offer.getStatus().isLive()) return null;
        if (roundOf(offer) >= MAX_ROUNDS) {
            offer.setStatus(OfferStatus.REJECTED);
            offers.save(offer);
            return offer;
        }
        TransferOffer next = new TransferOffer();
        next.setTransfer(offer.getTransfer());
        next.setBuyerTeam(offer.getBuyerTeam());
        next.setRound(roundOf(offer) + 1);
        applyTerms(next, offer.getTransfer(), fee, wage,
                contractYears == null ? offer.getContractYears() : contractYears);

        offer.setStatus(OfferStatus.COUNTERED);
        offers.save(offer);
        return offers.save(next);
    }

    /** The buyer answers a counter: up, down, or walk away. */
    @Transactional
    public TransferOffer counterBack(TransferOffer counter, double fee, double wage, Integer contractYears) {
        return counter(counter, fee, wage, contractYears);
    }

    // ------------------------------------------------------------------ outcomes

    @Transactional
    public TransferOffer accept(TransferOffer offer) {
        if (offer == null) return null;
        offer.setStatus(OfferStatus.ACCEPTED);
        return offers.save(offer);
    }

    @Transactional
    public TransferOffer reject(TransferOffer offer) {
        if (offer == null) return null;
        offer.setStatus(OfferStatus.REJECTED);
        return offers.save(offer);
    }

    @Transactional
    public TransferOffer withdraw(TransferOffer offer) {
        if (offer == null) return null;
        offer.setStatus(OfferStatus.WITHDRAWN);
        return offers.save(offer);
    }

    /**
     * The seller accepts one specific offer and **leaves the others alone**.
     *
     * <p>The old {@code acceptBestOffer} cleared every offer on acceptance, so a seller could not
     * pick — and if they wanted the second-best club's money more, they could not have it. Accepting
     * one bid must not destroy the rest of the auction.
     */
    @Transactional
    public List<TransferOffer> acceptOffer(Long transferId, Long offerId) {
        List<TransferOffer> all = offers.findByTransferIdOrderByRoundAsc(transferId);
        TransferOffer chosen = all.stream()
                .filter(o -> o.getId().equals(offerId))
                .findFirst().orElse(null);
        if (chosen == null) return all;
        if (!chosen.getStatus().isLive()) return all;

        chosen.setStatus(OfferStatus.ACCEPTED);
        offers.save(chosen);
        // Everyone else is told the player has gone, but their own record is preserved.
        for (TransferOffer other : all) {
            if (other.getId().equals(offerId)) continue;
            if (other.getStatus().isLive()) {
                other.setStatus(OfferStatus.REJECTED);
                offers.save(other);
            }
        }
        return offers.findByTransferIdOrderByRoundAsc(transferId);
    }

    /** Rejects one bid without touching the others. */
    @Transactional
    public TransferOffer rejectOffer(Long offerId) {
        TransferOffer offer = offers.findById(offerId).orElse(null);
        return offer == null ? null : reject(offer);
    }

    // ------------------------------------------------------------------ view

    /** The full thread for a transfer, oldest round first. */
    @Transactional(readOnly = true)
    public List<TransferOffer> thread(Long transferId) {
        return offers.findByTransferIdOrderByRoundAsc(transferId);
    }

    /** Live bids on a transfer, highest fee first. */
    @Transactional(readOnly = true)
    public List<TransferOffer> liveOffers(Long transferId) {
        return offers.findByTransferIdAndStatusInOrderByFeeDesc(transferId,
                List.of(OfferStatus.OPEN, OfferStatus.COUNTERED));
    }

    /** Expires anything past its date. Offers do not sit open forever. */
    @Transactional
    public int expireStale(Long transferId) {
        int expired = 0;
        for (TransferOffer o : offers.findByTransferIdOrderByRoundAsc(transferId)) {
            if (o.getStatus().isLive() && o.isExpired()) {
                o.setStatus(OfferStatus.EXPIRED);
                offers.save(o);
                expired++;
            }
        }
        return expired;
    }

    // ------------------------------------------------------------------ terms

    private void applyTerms(TransferOffer offer, Transfer transfer, double fee, double wage, Integer years) {
        double agentFee = agentFeeFor(fee);
        offer.setFee(round2(fee));
        offer.setWage(round2(wage));
        offer.setContractYears(years == null ? 3 : Math.max(1, years));
        offer.setAgentFee(round2(agentFee));
        offer.setStatus(OfferStatus.OPEN);
        offer.defaultExpiry();

        // The player is a third party: a deal can be agreed between two clubs and still fail here.
        Player player = transfer == null ? null : transfer.getPlayer();
        offer.setPlayerObjection(playerObjection(player, wage));
    }

    /**
     * Why the player would refuse these personal terms, or null if he would not.
     *
     * <p>Uses the same wage-demand model as a contract renewal, so a player who wants €9,000 a week
     * does not quietly accept €3,000 because the two clubs agreed a fee.
     */
    private String playerObjection(Player player, double offeredWage) {
        if (player == null || player.getId() == null) return null;
        var demand = contracts.wageDemand(player.getId());
        if (demand.isUnknown()) return null;
        if (offeredWage + 0.5 >= demand.demandedWeeklyWage()) return null;
        return "He is asking for " + round2(demand.demandedWeeklyWage()) + " a week and has been offered "
                + round2(offeredWage) + ". The fee is agreed; the player has not agreed to come.";
    }

    /** Whether the player himself would sign these terms. */
    public boolean playerWouldSign(TransferOffer offer) {
        return offer != null && offer.getPlayerObjection() == null;
    }

    /**
     * Agent fee, 2-5% of the transfer fee.
     *
     * <p>Scales with the size of the deal: a percentage on a small fee would be a rounding error,
     * and a flat rate would make agenting a cheap player pointless.
     */
    private double agentFeeFor(double fee) {
        if (fee <= 0) return 0;
        double share = AGENT_MIN + (AGENT_MAX - AGENT_MIN) * clamp(fee / 20_000_000.0, 0, 1);
        return fee * share;
    }

    private int roundOf(TransferOffer offer) {
        return offer.getRound() == null ? 1 : offer.getRound();
    }

    private double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
