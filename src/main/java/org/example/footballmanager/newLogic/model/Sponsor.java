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
 * A club sponsor (Sprint 2.3).
 *
 * <p>The Finances page used to display three invented sponsors whose values were derived from the
 * club's own budget — income that existed only in the browser. This makes sponsorship a real
 * contract with a term and an expiry, so losing a sponsor is an event.
 */
@Data
@Entity(name = "Sponsor")
public class Sponsor {

    /** Only a top-flight club attracts a title sponsor. */
    public enum Tier { TITLE, MAJOR, MINOR }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private Team team;

    private String name;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Tier tier;

    /** Total value over the whole contract, in euros. */
    private Double annualValue;

    private Integer startSeason;
    private Integer endSeason;

    /**
     * Bonus paid on cup success, as a fraction of the annual value. Real contracts have this, and it
     * is why winning a cup is worth more than a mid-table finish.
     */
    private Double performanceBonusClause;

    public boolean isActive(int currentSeason) {
        return startSeason != null && endSeason != null
                && currentSeason >= startSeason && currentSeason <= endSeason;
    }

    /** The weekly income this sponsor actually pays right now. */
    public double weeklyIncome(int currentSeason) {
        if (!isActive(currentSeason) || annualValue == null) return 0;
        return annualValue / 52.0;
    }
}
