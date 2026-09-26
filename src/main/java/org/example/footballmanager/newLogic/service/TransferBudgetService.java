package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a club can actually spend, and on whom (Sprint 2.5).
 *
 * <p>The transfer budget used to be computed in the browser as
 * {@code max(budget * 0.38, squadValue * 0.04, 50000)} — a formula the player could see and nobody
 * could act on. It is now a **board-granted** allowance derived from the club's financial health,
 * so the constraint is the one the backlog asked for: a club cannot sign a player it cannot afford,
 * and the wage bill is the primary limit rather than a transfer figure.
 *
 * <h2>Why the wage bill dominates</h2>
 * A transfer fee is a one-off; the wages that follow are forever. So affordability is tested against
 * <em>the wage bill after signing</em>, not against the transfer budget alone. A club can afford a
 * €2m striker only if it can also pay him — which is the constraint that makes Football Manager a
 * management game rather than a shopping list.
 */
@Service
public class TransferBudgetService {

    /**
     * A signed player costs his wage every week for the rest of his contract, so the affordability
     * test is dominated by the wage. A fee alone is 25% of the decision.
     */
    private static final double FEE_SHARE_OF_AFFORDABILITY = 0.25;

    /** The board will not take a squad's wage bill past this share of weekly income. */
    private static final double MAX_WAGE_TO_INCOME = 1.15;

    private final TeamRepository teams;
    private final PlayerRepository players;
    private final FinanceLedgerService ledgerService;

    public TransferBudgetService(TeamRepository teams,
                                 PlayerRepository players,
                                 FinanceLedgerService ledgerService) {
        this.teams = teams;
        this.players = players;
        this.ledgerService = ledgerService;
    }

    /**
     * The transfer budget the board has granted, with the reasoning attached.
     *
     * <p>Deliberately capped well below the cash balance. A club with €20m in the bank has not got
     * €20m to spend: the money is committed to wages, facilities and the pitch, and spending it all
     * on one striker is how a club goes bust the season after winning the league.
     */
    @Transactional(readOnly = true)
    public Budget budgetFor(Long teamId) {
        Team club = teams.findById(teamId).orElse(null);
        if (club == null) return Budget.unknown();

        double cash = club.getBudget() == null ? 0 : club.getBudget();
        Map<String, Object> finances = ledgerService.summarise(club);
        double weeklyIncome = asDouble(finances.get("incomeToDate")) / Math.max(1, weeksSettled(finances));
        double wageBill = asDouble(finances.get("squadWageBill"));
        double squadValue = asDouble(finances.get("squadValue"));

        if (weeklyIncome <= 0) {
            // No settled income yet. Rather than invent a number from the cash balance, say so:
            // a club in its first week has not been given anything yet.
            return new Budget(teamId, 0, cash, wageBill, weeklyIncome, squadValue, 0,
                    "No settled income yet, so no transfer budget has been granted.");
        }

        // Annual income, less a year's wages and a reserve, is what the board will consider.
        double annualIncome = weeklyIncome * 52;
        double annualWages = wageBill * 52;
        double headroom = annualIncome - annualWages;
        double reserve = annualIncome * 0.15;
        double allowed = Math.max(0, headroom - reserve) * FEE_SHARE_OF_AFFORDABILITY;

        // Never more than a share of the cash on hand either.
        double cashCap = Math.max(0, cash) * 0.5;
        double granted = Math.min(allowed, cashCap);

        String reason;
        if (granted <= 0) {
            reason = "The wage bill leaves nothing to spend on transfers.";
        } else if (cashCap < allowed) {
            reason = "Limited by cash on hand rather than by annual income.";
        } else {
            reason = "Graded from income left after wages, less a reserve.";
        }

        return new Budget(teamId, round2(granted), cash, round2(wageBill),
                round2(weeklyIncome), squadValue, MAX_WAGE_TO_INCOME, reason);
    }

    /**
     * Whether this club can sign this player, and if not, why not.
     *
     * <p>The answer is what the manager needs to read: not "no", but which of the two limits stopped
     * him — the transfer budget or the wage bill.
     */
    @Transactional(readOnly = true)
    public Affordability canAfford(Long teamId, Long playerId) {
        Budget budget = budgetFor(teamId);
        Player target = players.findById(playerId).orElse(null);
        if (target == null) return Affordability.no("That player does not exist.");
        if (budget.notGranted()) {
            return Affordability.no(budget.reason());
        }

        // There is no stored asking price, so the fee is a share of market value. A quarter of
        // value is the conventional relationship and keeps the number honest and bounded.
        double fee = target.getPlayerValue() * 0.25;
        double wage = target.getEarnings();

        if (fee > budget.granted()) {
            return Affordability.no("The transfer budget is " + round2(fee - budget.granted())
                    + " short of his fee.");
        }

        // The wage is the real test. If signing him pushes the bill past the ceiling, he is
        // unaffordable however much cash is in the bank.
        double newBill = budget.weeklyWageBill() + wage;
        double ceiling = budget.weeklyIncome() * MAX_WAGE_TO_INCOME;
        if (newBill > ceiling) {
            return Affordability.no("His wage would put the squad at " + round2(newBill)
                    + " a week against a ceiling of " + round2(ceiling) + ". The fee is affordable; "
                    + "the wage is not.");
        }

        return Affordability.yes(fee, wage, newBill, ceiling);
    }

    private int weeksSettled(Map<String, Object> finances) {
        Object lines = finances.get("ledgerLines");
        int n = lines instanceof Number x ? x.intValue() : 0;
        return Math.max(1, n / 4);
    }

    private double asDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** What the board has granted, and why. */
    public record Budget(Long teamId, double granted, double cash, double weeklyWageBill,
                         double weeklyIncome, double squadValue, double wageCeiling,
                         String reason) {
        public boolean notGranted() {
            return granted <= 0;
        }

        public static Budget unknown() {
            return new Budget(null, 0, 0, 0, 0, 0, MAX_WAGE_TO_INCOME,
                    "That club does not exist.");
        }
    }

    /** Whether a player can be signed, and if not, which limit stopped it. */
    public record Affordability(boolean affordable, String reason, double fee, double wage,
                                double newWageBill, double wageCeiling) {
        public static Affordability no(String reason) {
            return new Affordability(false, reason, 0, 0, 0, 0);
        }

        public static Affordability yes(double fee, double wage, double newBill, double ceiling) {
            return new Affordability(true, "Affordable.", fee, wage, newBill, ceiling);
        }
    }
}
