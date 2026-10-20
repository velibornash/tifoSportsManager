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

import java.time.Instant;

/**
 * One line in a club's accounts for one week.
 *
 * <p>An append-only ledger rather than a running balance. The reason is the same reason football
 * clubs keep one: a manager who cannot see <em>why</em> the money moved cannot fix it, and a
 * single number on {@code Team.budget} cannot answer "which week did the wage bill double, and
 * what happened that week".
 *
 * <p>Amounts are stored signed — income positive, cost negative — so the sum over a category is the
 * net without needing to re-read {@link FinanceCategory#isIncome()}.
 */
@Data
@Entity(name = "FinanceLedgerEntry")
public class FinanceLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private Team team;

    private Integer seasonYear;
    private Integer weekNumber;

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private FinanceCategory category;

    /** Signed: positive is income, negative is a cost. */
    private Double amount;

    /** What caused this line, e.g. "Attendance 24,300 / avg EUR 19.40". */
    @Column(length = 500)
    private String note;

    private Instant createdAt = Instant.now();

    public FinanceLedgerEntry() { }

    public static FinanceLedgerEntry of(Team team, Integer seasonYear, Integer week,
                                        FinanceCategory category, double amount, String note) {
        FinanceLedgerEntry e = new FinanceLedgerEntry();
        e.team = team;
        e.seasonYear = seasonYear;
        e.weekNumber = week;
        e.category = category;
        e.amount = category.sign(amount);
        e.note = note;
        return e;
    }
}
