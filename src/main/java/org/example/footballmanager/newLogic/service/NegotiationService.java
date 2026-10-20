package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.OfferStatus;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.SquadRole;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.FinanceCategory;
import org.example.footballmanager.newLogic.model.FinanceLedgerEntry;
import org.example.footballmanager.newLogic.model.GameClock;
import org.example.footballmanager.newLogic.model.Transfer;
import org.example.footballmanager.newLogic.model.TransferStatus;
import org.example.footballmanager.newLogic.model.TransferOffer;
import org.example.footballmanager.newLogic.repository.FinanceLedgerEntryRepository;
import org.example.footballmanager.newLogic.repository.GameClockRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.repository.TransferOfferRepository;
import org.example.footballmanager.newLogic.repository.TransferRepository;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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
    private final TransferWindowService windows;
    private final TransferRepository transfers;
    private final TransferFeeService transferFees;
    private final FinanceLedgerEntryRepository ledgerEntries;
    private final PlayerRepository players;
    private final TeamRepository teams;
    private final GameClockRepository clocks;

    public NegotiationService(TransferOfferRepository offers,
                              PlayerContractService contracts,
                              TransferBudgetService budgets,
                              TransferWindowService windows,
                              TransferRepository transfers,
                              TransferFeeService transferFees,
                              FinanceLedgerEntryRepository ledgerEntries,
                              PlayerRepository players,
                              TeamRepository teams,
                              GameClockRepository clocks) {
        this.offers = offers;
        this.contracts = contracts;
        this.budgets = budgets;
        this.windows = windows;
        this.transfers = transfers;
        this.transferFees = transferFees;
        this.ledgerEntries = ledgerEntries;
        this.players = players;
        this.teams = teams;
        this.clocks = clocks;
    }

    /**
     * Why an offer may not be made right now, or null if it may.
     *
     * <p>Registration days are the point of the feature: a manager needs to know how long they have
     * and what is about to shut, and an out-of-window attempt has to be refused with a reason rather
     * than a 500.
     */
    public TransferWindowService.Decision windowCheck(TransferWindowService.Kind kind) {
        return windows.decide(kind);
    }

    // ------------------------------------------------------------------ opening

    /** A club opens the bidding. */
    @Transactional
    public TransferOffer openOffer(Transfer transfer, Team buyer,
                                   double fee, double wage, int contractYears) {
        if (transfer == null || buyer == null) return null;

        // A permanent move needs the window open. Refused here, with the reason, so the caller gets
        // an explanation rather than a failed write.
        TransferWindowService.Decision window = windowCheck(TransferWindowService.Kind.PERMANENT);
        if (!window.permitted()) {
            throw new TransferWindowClosedException(window.reason());
        }

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
        acceptInternal(transferId, offerId);
        return offers.findByTransferIdOrderByRoundAsc(transferId);
    }

    /**
     * The seller accepts one specific bid, and the caller is told whether the deal settled.
     *
     * <p>{@link #acceptOffer} flips the statuses and settles without reporting whether the
     * settlement happened. A buyer who could no longer afford the fee therefore left the seller with
     * one {@code ACCEPTED} offer, every rival {@code REJECTED}, and no transfer: the auction was
     * destroyed and nothing was bought — the exact outcome {@code acceptOffer}'s own javadoc says
     * must not happen. The statuses are now put back, so a refused deal leaves the bidding open and
     * the seller free to take a different bid.
     *
     * @return whether the transfer completed
     */
    @Transactional
    public boolean settleOffer(Long transferId, Long offerId) {
        return acceptInternal(transferId, offerId);
    }

    /**
     * Accepts one bid, rejects the rivals, and settles — or puts every status back if it cannot.
     *
     * @return whether the transfer completed
     */
    private boolean acceptInternal(Long transferId, Long offerId) {
        List<TransferOffer> all = offers.findByTransferIdOrderByRoundAsc(transferId);
        TransferOffer chosen = all.stream()
                .filter(o -> o.getId().equals(offerId))
                .findFirst().orElse(null);
        if (chosen == null || !chosen.getStatus().isLive()) {
            return false;
        }

        OfferStatus previous = chosen.getStatus();
        List<TransferOffer> rivals = all.stream()
                .filter(o -> !o.getId().equals(chosen.getId()))
                .filter(o -> o.getStatus().isLive())
                .toList();

        chosen.setStatus(OfferStatus.ACCEPTED);
        offers.save(chosen);
        // Everyone else is told the player has gone, but their own record is preserved.
        for (TransferOffer other : rivals) {
            other.setStatus(OfferStatus.REJECTED);
            offers.save(other);
        }

        if (!completeTransfer(transferId, chosen)) {
            log.warn("Transfer {} cannot settle offer {}: refused, so every bid is live again",
                    transferId, chosen.getId());
            chosen.setStatus(previous);
            offers.save(chosen);
            for (TransferOffer other : rivals) {
                other.setStatus(OfferStatus.OPEN);
                offers.save(other);
            }
            return false;
        }
        return true;
    }

    /**
     * Settles a transfer that both sides have agreed.
     *
     * <p>This is the part that was missing, and it is the whole point of the negotiation: accepting
     * an offer used to flip a status and nothing else. The player stayed at the selling club, the
     * buyer was never charged, no contract was written and no ledger line existed — so a transfer
     * market where every deal is agreed and no deal ever happens.
     *
     * <p>Order matters and is deliberate: charge the buyer <i>before</i> moving the player, so a
     * club that cannot pay never ends up holding the asset. The seller's budget and the ledger are
     * the evidence the money moved.
     */
    /**
     * Settles a transfer from the terms on an accepted offer.
     *
     * <p>A thin wrapper over {@link #settle} — the single place a player actually changes clubs.
     */
    @Transactional
    public boolean completeTransfer(Long transferId, TransferOffer accepted) {
        return settle(transferId,
                accepted == null ? null : accepted.getBuyerTeam(),
                accepted == null || accepted.getFee() == null ? 0 : accepted.getFee(),
                accepted == null || accepted.getWage() == null ? 0 : accepted.getWage(),
                accepted == null ? null : accepted.getContractYears());
    }

    /**
     * The one place a player changes clubs and money changes hands.
     *
     * <p>There used to be a second implementation of this in {@code TransferService}, reachable
     * from the purchase endpoints, with its own copy of the budget guard and its own idea of what
     * "completed" means. Two settlement paths on one entity is a double-completion waiting to
     * happen, so both entry points now come through here and the {@code COMPLETED} guard exists once.
     *
     * <p>Order is deliberate: the buyer is charged <b>before</b> the player moves, so a club that
     * cannot pay never ends up holding the asset.
     */
    @Transactional
    public boolean settle(Long transferId, Team buyer, double fee, double wage, Integer years) {
        Transfer transfer = transfers.findById(transferId).orElse(null);
        if (transfer == null) {
            log.warn("Transfer {} cannot be completed: it does not exist", transferId);
            return false;
        }
        if (transfer.getStatus() == TransferStatus.COMPLETED) {
            return false;
        }
        Player player = transfer.getPlayer();
        if (player == null || buyer == null) {
            log.warn("Transfer {} cannot be completed: player={} buyer={}", transferId, player, buyer);
            return false;
        }

        int season = currentSeason();
        int week = currentWeek();

        // 1. The money first. A club that cannot pay must not end up holding the player, so the
        //    affordability check happens before anything moves.
        boolean instalments = transferFees.wouldBeInstalments(fee);
        if (!instalments && fee > 0) {
            TransferBudgetService.Affordability check =
                    budgets.canAfford(buyer.getId(), player.getId());
            if (!check.affordable()) {
                // Said out loud rather than swallowed: a deal that quietly fails to complete is
                // indistinguishable from one that was never agreed.
                log.warn("{} cannot complete the signing of {}: {}", buyer.getName(),
                        player.getName(), check.reason());
                return false;
            }
        }
        double upfront = instalments ? 0 : fee;
        if (upfront > 0) {
            buyer.setBudget(round2((buyer.getBudget() == null ? 0 : buyer.getBudget()) - upfront));
            teams.save(buyer);
            ledgerEntries.save(FinanceLedgerEntry.of(buyer, season, week,
                    FinanceCategory.TRANSFER_FEE_OUT, upfront, "Transfer: " + player.getName()));
        }
        Team seller = transfer.getSellerTeam();
        if (seller != null && upfront > 0) {
            seller.setBudget(round2((seller.getBudget() == null ? 0 : seller.getBudget()) + upfront));
            teams.save(seller);
            ledgerEntries.save(FinanceLedgerEntry.of(seller, season, week,
                    FinanceCategory.TRANSFER_FEE_IN, upfront, "Transfer: " + player.getName()));
        }

        // 2. The instalment schedule, if the fee is too big to pay at once. This records what is
        //    owed; TransferFeeService.collectInstalment pays it down later. The sell-on is zero
        //    because nothing in an offer carries one - see the open question in sprintBacklog.
        transferFees.agree(transfer, fee, 0.0);

        // 3. The player moves, on the agreed money, for the agreed term.
        player.setTeam(buyer);
        // A signing arrives knowing nothing about this club's way of playing (Sprint 4.6), so his
        // growth is held back until he has played his way in. Set here rather than in a weekly sweep
        // because this is the exact moment the fact becomes true, and a sweep would have to guess
        // which players were new.
        player.setFamiliarity((double) SquadEnvironment.NEW_SIGNING_FAMILIARITY);
        player.setEarnings(wage);
        players.save(player);
        if (years != null && years > 0) {
            contracts.assignToClub(player, buyer, season, SquadRole.ROTATION);
        }

        // 4. And the transfer itself is closed out.
        transfer.setBuyerTeam(buyer);
        transfer.setAgreedPrice(fee);
        transfer.setStatus(TransferStatus.COMPLETED);
        transfer.setCompletedAt(java.time.LocalDateTime.now());
        transfers.save(transfer);
        return true;
    }

    /** The season and week ledger lines and contracts are written against. */
    private int currentSeason() {
        GameClock clock = clocks.findAll().stream().findFirst().orElse(null);
        return clock == null || clock.getCurrentSeason() == null ? 1 : clock.getCurrentSeason();
    }

    private int currentWeek() {
        GameClock clock = clocks.findAll().stream().findFirst().orElse(null);
        return clock == null || clock.getCurrentWeek() == null ? 1 : clock.getCurrentWeek();
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

    /**
     * A transfer attempted outside the window.
     *
     * <p>A distinct exception rather than a boolean return, because "the window is shut" is not the
     * same failure as "that player does not exist", and the caller has to be able to say which.
     */
    public static class TransferWindowClosedException extends IllegalStateException {
        public TransferWindowClosedException(String reason) {
            super(reason);
        }
    }
}
