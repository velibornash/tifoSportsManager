package org.example.footballmanager.newLogic.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import lombok.Data;

/**
 * How a large fee is paid, and who gets a slice of the next one (Sprint 3.2, items 6 and 7).
 *
 * <p>Two real features of the transfer market that the game had neither of:
 *
 * <ul>
 *   <li><b>Instalments.</b> Nobody pays €40m on the day. A fee is spread over the contract, and
 *       until it is paid the selling club still has a financial interest in the player — which is why
 *       a club that sells a player on instalments is exposed if he is injured in year two.</li>
 *   <li><b>Sell-on clause.</b> The club that sold a player keeps a share of the next transfer fee.
 *       It is how a club funds itself after a sale, and it is why selling a teenager can be worth
 *       more than keeping him.</li>
 * </ul>
 */
@Data
@Entity(name = "FeeStructure")
public class FeeStructure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private Transfer transfer;

    /** Total agreed fee, in euros. */
    private Double totalFee;

    /** How much of it is paid on completion. The rest is deferred. */
    private Double upfront;

    /** Number of monthly instalments for the remainder. */
    private Integer instalments;

    /** Instalment still outstanding. */
    private Double outstanding;

    /**
     * Share of any future transfer fee owed back to the selling club, 0 to 0.5.
     *
     * <p>Capped at 50% because a clause above that stops clubs from ever selling anyone.
     */
    private Double sellOnPercentage;

    /** True while instalments are still being paid. */
    public boolean hasOutstanding() {
        return outstanding != null && outstanding > 0.5;
    }

    public double monthlyInstalment() {
        if (instalments == null || instalments <= 0 || totalFee == null || upfront == null) return 0;
        double remaining = Math.max(0, totalFee - upfront);
        return remaining / instalments;
    }
}
