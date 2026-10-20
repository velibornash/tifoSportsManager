package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.FeeStructure;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.Transfer;
import org.example.footballmanager.newLogic.repository.FeeStructureRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Instalments and sell-on clauses (Sprint 3.2, items 6 and 7).
 *
 * <p>Two things the transfer market actually does that the game had neither of.
 *
 * <h2>Instalments</h2>
 * Nobody pays €40m on the day. A fee is spread across the contract, and until it is paid the
 * selling club still carries the risk — if the player is injured in year two, the club that sold
 * him has already spent the money and is owed the rest. That is why the wage bill and the
 * outstanding balance are both real numbers, and why a club selling a star on instalments is
 * exposed in a way a club selling him outright is not.
 *
 * <h2>Sell-on clauses</h2>
 * The selling club keeps a share of the player's next fee. Capped at 50%, because above that a
 * club can never sell anyone and the market dies. This is how a club funds itself after a sale, and
 * it is why selling a teenager can be worth more in total than keeping him.
 */
@Service
public class TransferFeeService {

    /** Above this a fee is negotiated in instalments rather than paid on the day. */
    public static final double INSTALMENT_THRESHOLD = 2_000_000.0;

    /** Nobody's sell-on clause exceeds this. */
    public static final double MAX_SELL_ON = 0.50;

    /** An instalment schedule runs at most this many months. */
    private static final int MAX_INSTALMENT_MONTHS = 48;

    private final FeeStructureRepository fees;
    private final TeamRepository teams;

    public TransferFeeService(FeeStructureRepository fees, TeamRepository teams) {
        this.fees = fees;
        this.teams = teams;
    }

    /**
     * Builds the fee structure for a completed deal.
     *
     * <p>A small fee is paid outright; a large one is split. The split is not arbitrary: the
     * bigger the fee, the less of it changes hands on the day, and the club taking the money is the
     * one carrying the risk.
     */
    @Transactional
    public FeeStructure agree(Transfer transfer, double totalFee, double sellOnPercentage) {
        FeeStructure existing = fees.findByTransferId(transfer.getId()).orElse(null);

        // Re-processing a deal must never reset the schedule. This used to overwrite the whole
        // structure, which silently wiped any outstanding balance — a club that was owed a year of
        // instalments would find the debt gone because something re-saved the deal. The first
        // agreed terms stand; only a deal that is fully settled may be re-agreed.
        if (existing != null && existing.hasOutstanding()) {
            return existing;
        }

        FeeStructure f = existing != null ? existing : new FeeStructure();
        f.setTransfer(transfer);
        f.setTotalFee(round2(totalFee));
        f.setSellOnPercentage(clamp(sellOnPercentage, 0, MAX_SELL_ON));

        if (totalFee < INSTALMENT_THRESHOLD) {
            f.setUpfront(round2(totalFee));
            f.setInstalments(0);
            f.setOutstanding(0.0);
        } else {
            // Bigger fee, longer schedule, smaller proportion on the day.
            double upfrontShare = totalFee > 20_000_000 ? 0.40
                    : totalFee > 10_000_000 ? 0.50 : 0.60;
            double upfront = totalFee * upfrontShare;
            int months = (int) Math.min(MAX_INSTALMENT_MONTHS,
                    Math.max(6, Math.round(totalFee / 500_000.0)));
            f.setUpfront(round2(upfront));
            f.setInstalments(months);
            f.setOutstanding(round2(totalFee - upfront));
        }
        return fees.save(f);
    }

    /** The agreed fee schedule for a transfer, or null if there is none. */
    @Transactional(readOnly = true)
    public FeeStructure findStructureFor(Long transferId) {
        if (transferId == null) return null;
        return fees.findByTransferId(transferId).orElse(null);
    }

    /** Collects one month's instalment into the selling club's budget. */
    @Transactional
    public double collectInstalment(Long transferId) {
        FeeStructure f = fees.findByTransferId(transferId).orElse(null);
        if (f == null || !f.hasOutstanding()) return 0;

        double payment = Math.min(f.monthlyInstalment(), f.getOutstanding());
        f.setOutstanding(round2(f.getOutstanding() - payment));
        fees.save(f);

        Team seller = f.getTransfer() == null ? null : f.getTransfer().getSellerTeam();
        if (seller != null) {
            seller.setBudget(round2((seller.getBudget() == null ? 0 : seller.getBudget()) + payment));
            teams.save(seller);
        }
        return payment;
    }

    /**
     * What a selling club receives from a later sale of a player it sold on a sell-on clause.
     *
     * @return the club's share, or 0 if no clause applies
     */
    @Transactional
    public double sellOnShare(Long previousTransferId, double nextFee) {
        FeeStructure previous = fees.findByTransferId(previousTransferId).orElse(null);
        if (previous == null || previous.getSellOnPercentage() == null) return 0;
        double share = nextFee * previous.getSellOnPercentage();

        Team seller = previous.getTransfer() == null ? null : previous.getTransfer().getSellerTeam();
        if (seller != null) {
            seller.setBudget(round2((seller.getBudget() == null ? 0 : seller.getBudget()) + share));
            teams.save(seller);
        }
        return round2(share);
    }

    /** Every deal still owing money, for the finance screen. */
    @Transactional(readOnly = true)
    public List<FeeStructure> outstanding() {
        return fees.findByOutstandingGreaterThan(0.5);
    }

    /** Whether a fee at this level would be paid in instalments. */
    public boolean wouldBeInstalments(double fee) {
        return fee >= INSTALMENT_THRESHOLD;
    }

    private double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
