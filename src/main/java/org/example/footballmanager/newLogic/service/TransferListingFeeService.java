package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.exception.ApiException;
import org.example.footballmanager.newLogic.model.FinanceCategory;
import org.example.footballmanager.newLogic.model.FinanceLedgerEntry;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.FinanceLedgerEntryRepository;
import org.example.footballmanager.newLogic.repository.GameClockRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The fee for putting a player on the transfer list (P2-4).
 *
 * <p>There was <b>no fee at all</b>: listing was free, so a club could list its entire squad at a
 * million euros each, pay nothing, and leave the market to sort it out. A percentage of the asking
 * price is the anti-spam tax the genre uses, and it is self-scaling in the right direction — a
 * pittance listing costs a pittance, so the fee cannot stop a manager selling a journeyman, and it
 * cannot be worth paying on a speculative mega-listing either.
 *
 * <p><b>Human clubs only, by owner decision.</b> AI clubs self-list weekly
 * ({@code TransferService.maybeCreateAiListing}) and academy graduations list automatically. Charging
 * those would drain the AI economy across 14,880 clubs for a mechanic that exists to stop a
 * <em>manager</em> spamming the market. {@link Team#isHumanControlled()} is the flag, and it is
 * maintained by PyramidBuilder, DatabaseInitializer and RegistrationService rather than inferred.
 *
 * <p>Own class rather than a helper on {@code TransferService}: the rate, the rounding, the
 * affordability refusal and the ledger line are one decision, and {@code TransferService} is already
 * the largest file in the package.
 */
@Service
public class TransferListingFeeService {

    /** 2.5% of the asking price — the rate the competitive analysis benchmarks against. */
    public static final double RATE = 0.025;

    /** Below this the fee is not worth a ledger line. */
    private static final double MINIMUM_FEE = 0.01;

    private final TeamRepository teams;
    private final FinanceLedgerEntryRepository ledgerEntries;
    private final GameClockRepository clocks;

    public TransferListingFeeService(TeamRepository teams,
                                     FinanceLedgerEntryRepository ledgerEntries,
                                     GameClockRepository clocks) {
        this.teams = teams;
        this.ledgerEntries = ledgerEntries;
        this.clocks = clocks;
    }

    /** What listing at this asking price costs. Zero for an AI club. */
    public double feeFor(Team seller, double askingPrice) {
        if (seller == null || !seller.isHumanControlled()) {
            return 0.0;
        }
        double fee = round2(Math.max(0.0, askingPrice) * RATE);
        return fee < MINIMUM_FEE ? 0.0 : fee;
    }

    /**
     * Charges the fee, once, for a genuine new listing.
     *
     * @param relisting {@code true} when the player is already actively listed and this call is only
     *                  re-setting the price. That is not a new listing and must not be charged twice,
     *                  or a manager could relist on a loop for free — the flag existed and was unused.
     * @return the fee actually charged, for the caller's message
     */
    public double charge(Team seller, Player player, double askingPrice, boolean relisting) {
        if (relisting) {
            return 0.0;
        }
        double fee = feeFor(seller, askingPrice);
        if (fee <= 0) {
            return 0.0;
        }

        double budget = seller.getBudget() == null ? 0.0 : seller.getBudget();
        if (budget + 0.0001 < fee) {
            // Refused rather than allowed to go negative. A club that cannot fund the fee must not
            // be able to list at all, or the fee is just a speed bump on honest managers and nothing
            // at all on the dishonest ones.
            throw new ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_BUDGET_FOR_LISTING_FEE",
                    "Listing this player costs EUR " + Math.round(fee) + " and your club has EUR "
                            + Math.round(budget) + ".");
        }

        seller.setBudget(round2(budget - fee));
        teams.save(seller);
        ledgerEntries.save(FinanceLedgerEntry.of(seller, currentSeason(), currentWeek(),
                FinanceCategory.LISTING_FEE, fee, "Listed " + player.getName()));
        return fee;
    }

    private Integer currentSeason() {
        return clocks.findAll().stream().findFirst()
                .map(c -> c.getCurrentSeason() == null ? 1 : c.getCurrentSeason())
                .orElse(1);
    }

    private Integer currentWeek() {
        return clocks.findAll().stream().findFirst()
                .map(c -> c.getCurrentWeek() == null ? 1 : c.getCurrentWeek())
                .orElse(1);
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}