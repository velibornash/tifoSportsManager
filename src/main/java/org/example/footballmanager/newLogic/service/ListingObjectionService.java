package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.exception.ApiException;
import org.example.footballmanager.newLogic.model.FinanceCategory;
import org.example.footballmanager.newLogic.model.FinanceLedgerEntry;
import org.example.footballmanager.newLogic.model.GameClock;
import org.example.footballmanager.newLogic.model.ListingObjection;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.PlayerContract;
import org.example.footballmanager.newLogic.model.SquadRole;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.Transfer;
import org.example.footballmanager.newLogic.repository.FinanceLedgerEntryRepository;
import org.example.footballmanager.newLogic.repository.GameClockRepository;
import org.example.footballmanager.newLogic.repository.PlayerContractRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * A player can refuse to be put on the transfer list (P2-3).
 *
 * <p>The anti-speculation mechanic. A club used to be able to put anybody it liked on the list at any
 * price, and the player's position in that decision was not modelled at all — so listing a squad en
 * masse cost nothing and meant nothing. Here a player may <b>object</b>, the club <b>may not</b>
 * delist him or accept a bid while the objection stands, and the club resolves it either by
 * <b>withdrawing the listing</b> or by <b>paying compensation</b> to clear it and carry on selling.
 * That is the decision the mechanic exists to create: keep the player, or pay to let him go.
 *
 * <p>Two pieces of dead code became load-bearing rather than being rewritten:
 * {@link SquadRole#reluctanceToSell()} and the {@link PlayerContractService#wageDemand} model, which
 * already answered "is he being paid what he thinks he is worth" for contract renewals and for
 * transfer offers.
 *
 * <p><b>Where the objection does and does not bite.</b> It blocks <em>the selling club</em>:
 * delisting and accepting a bid are the acts of moving a player. It does not block a rival club
 * bidding, because that is a different labour question between a different pair of clubs, and
 * blocking it would make the mechanic unreachable in a multiplayer market.
 */
@Service
public class ListingObjectionService {

    /** What it costs a club to overrule a player, as a share of his asking price. */
    public static final double COMPENSATION_RATE = 0.05;

    /** A player at least this far below his own wage demand has a grievance, not a preference. */
    private static final double WAGE_GRIEVANCE_RATIO = 0.85;

    private final TeamRepository teams;
    private final FinanceLedgerEntryRepository ledgerEntries;
    private final GameClockRepository clocks;
    private final PlayerContractRepository contracts;
    private final PlayerContractService contractService;

    public ListingObjectionService(TeamRepository teams,
                                   FinanceLedgerEntryRepository ledgerEntries,
                                   GameClockRepository clocks,
                                   PlayerContractRepository contracts,
                                   PlayerContractService contractService) {
        this.teams = teams;
        this.ledgerEntries = ledgerEntries;
        this.clocks = clocks;
        this.contracts = contracts;
        this.contractService = contractService;
    }

    /**
     * How likely this player is to object to being listed, 0 to 1.
     *
     * <p>Built from two things the model already knows. How much the club values him
     * ({@link SquadRole#reluctanceToSell()} — a STAR at 0.92 against a YOUTH at 0.12, so a club does
     * not get a rebellion every time it lists a teenager), and whether he is being paid what he
     * thinks he is worth, which is the grievance that actually produces this in a dressing room.
     */
    public double objectionLikelihood(Player player) {
        if (player == null || player.getId() == null) {
            return 0.0;
        }
        SquadRole role = roleOf(player);
        double reluctance = role.reluctanceToSell();

        // Underpaid is the sharp end. A player on or above his own demand does not object over money,
        // so the grievance term drops out and only how much the club wants him remains.
        double grievance = 0.0;
        PlayerContractService.RenewalDemand demand = contractService.wageDemand(player.getId());
        if (!demand.isUnknown() && demand.currentWeeklyWage() > 0) {
            double paid = demand.currentWeeklyWage() / demand.demandedWeeklyWage();
            if (paid < WAGE_GRIEVANCE_RATIO) {
                grievance = Math.min(1.0, (WAGE_GRIEVANCE_RATIO - paid) / WAGE_GRIEVANCE_RATIO);
            }
        }

        double chance = reluctance * 0.55 + grievance * 0.45;
        return Math.max(0.0, Math.min(1.0, chance));
    }

    /**
     * Records an objection if one is warranted. Called from the listing path.
     *
     * @param roll a value in 0..1, passed in rather than drawn here so the caller owns the
     *             randomness and a test can pin it
     * @return the objection now on the listing
     */
    public ListingObjection raiseIfWarranted(Transfer transfer, double roll) {
        if (transfer == null || transfer.getPlayer() == null) {
            return ListingObjection.NONE;
        }
        Player player = transfer.getPlayer();
        // Already objected: re-pricing or relisting does not make a player change his mind, and
        // overwriting the standing reason would let a club relabel a refusal.
        if (transfer.getListingObjection() != null && transfer.getListingObjection().isBlocking()) {
            return transfer.getListingObjection();
        }
        if (roll >= objectionLikelihood(player)) {
            return ListingObjection.NONE;
        }

        ListingObjection objection = reasonFor(player);
        transfer.setListingObjection(objection);
        transfer.setListingObjectionReason(objection.label());
        return objection;
    }

    /** The reason, which follows from what is actually wrong with the player's situation. */
    private ListingObjection reasonFor(Player player) {
        PlayerContractService.RenewalDemand demand = contractService.wageDemand(player.getId());
        if (!demand.isUnknown() && demand.currentWeeklyWage() > 0
                && demand.currentWeeklyWage() < demand.demandedWeeklyWage() * WAGE_GRIEVANCE_RATIO) {
            return ListingObjection.WAGE_DISPUTE;
        }
        if (roleOf(player).reluctanceToSell() >= SquadRole.STARTER.reluctanceToSell()) {
            return ListingObjection.DOES_NOT_WANT_TO_LEAVE;
        }
        return ListingObjection.UNHAPPY_TO_BE_LISTED;
    }

    /** Refuses the call, with the reason, if the player is objecting. */
    public void requireResolved(Transfer transfer, String action) {
        ListingObjection objection = transfer == null ? ListingObjection.NONE : transfer.getListingObjection();
        if (objection == null || objection.isNone()) {
            return;
        }
        throw new ApiException(HttpStatus.CONFLICT, "PLAYER_OBJECTION_OPEN",
                "Cannot " + action + " while the player is objecting. "
                        + transfer.getListingObjectionReason()
                        + " Withdraw the listing, or pay compensation to overrule him.");
    }

    /** What it costs to overrule the player, rather than keep him. */
    public double compensationFor(Transfer transfer) {
        if (transfer == null || transfer.getListingObjection() == null
                || transfer.getListingObjection().isNone()) {
            return 0.0;
        }
        return round2(Math.max(0.0, transfer.getAskingPrice()) * COMPENSATION_RATE);
    }

    /**
     * Pays the player to stop objecting, so the club can carry on selling him.
     *
     * @return the compensation paid
     */
    public double payCompensation(Team seller, Transfer transfer) {
        double compensation = compensationFor(transfer);
        if (compensation <= 0) {
            return 0.0;
        }
        double budget = seller.getBudget() == null ? 0.0 : seller.getBudget();
        if (budget + 0.0001 < compensation) {
            throw new ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_BUDGET_FOR_COMPENSATION",
                    "Overruling this player costs EUR " + Math.round(compensation)
                            + " and your club has EUR " + Math.round(budget) + ".");
        }
        seller.setBudget(round2(budget - compensation));
        teams.save(seller);
        ledgerEntries.save(FinanceLedgerEntry.of(seller, currentSeason(), currentWeek(),
                FinanceCategory.LISTING_FEE, compensation,
                "Compensation to " + transfer.getPlayer().getName() + " to clear his objection"));

        clear(transfer);
        return compensation;
    }

    /** The objection is settled. The player's grievance was addressed, whatever the club chose. */
    public void clear(Transfer transfer) {
        transfer.setListingObjection(ListingObjection.NONE);
        transfer.setListingObjectionReason(null);
    }

    private SquadRole roleOf(Player player) {
        PlayerContract contract = contracts.findByPlayerId(player.getId()).orElse(null);
        if (contract != null && contract.getSquadRole() != null) {
            return contract.getSquadRole();
        }
        return switch (player.getPosition() == null ? org.example.footballmanager.newLogic.model.Position.MID
                : player.getPosition()) {
            case GK, DEF -> SquadRole.STARTER;
            case ATT -> SquadRole.ROTATION;
            case WNG -> SquadRole.PROSPECT;
            case MID -> SquadRole.STARTER;
        };
    }

    private Integer currentSeason() {
        return clocks.findAll().stream().findFirst()
                .map(GameClock::getCurrentSeason).orElse(1);
    }

    private Integer currentWeek() {
        return clocks.findAll().stream().findFirst()
                .map(GameClock::getCurrentWeek).orElse(1);
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}