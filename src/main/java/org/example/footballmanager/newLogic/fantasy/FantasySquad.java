package org.example.footballmanager.newLogic.fantasy;

import org.example.footballmanager.newLogic.model.Position;

import java.util.List;

/**
 * A manager's fantasy squad, read back against the current pool.
 *
 * <p>Prices here are <b>current</b>, not what was paid — {@link FantasySquadPick#purchasePrice} is what was
 * paid. Both are carried because they answer different questions: what the squad is worth now, and what it
 * cost. A price that moved is only visible with the two side by side.
 */
public record FantasySquad(Long teamId, Long countryId, int seasonYear, List<FantasySquadPick> picks,
                           int changes, int transferCost) {

    /** What the squad would cost at today's prices. */
    public double currentPrice() {
        double total = 0.0;
        for (FantasySquadPick pick : picks) {
            total += pick.player().price();
        }
        return round(total);
    }

    /** What this manager actually paid for it. */
    public double purchasePrice() {
        double total = 0.0;
        for (FantasySquadPick pick : picks) {
            total += pick.purchasePrice();
        }
        return round(total);
    }

    /** What is left of the budget at today's prices. */
    public double remaining() {
        return round(FantasyPricing.BUDGET - currentPrice());
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    /**
     * One player in the squad, with his current price and what he cost.
     *
     * @param purchasePrice what the manager paid. Kept because a later price rise is a gain only if the
     *        price paid is still known.
     */
    public record FantasySquadPick(FantasyPoolPlayer player,
                                   boolean starter,
                                   boolean captain,
                                   Integer benchOrder,
                                   double purchasePrice) {
    }
}
