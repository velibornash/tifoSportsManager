package org.example.footballmanager.newLogic.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * A player temporarily at another club (Sprint 3.4).
 *
 * <p>A loan is not a transfer. The player keeps his contract with the club that owns him, is
 * registered by nobody, and returns when the loan ends. Modelling it as a transfer would be simpler
 * and wrong in a way that matters: the owning club's asset would leave permanently, the wage bill
 * would move, and there would be no way back.
 *
 * <p>The owner was explicit that <b>a loan in is not exempt from the transfer window</b>, so this
 * goes through the same gate as a permanent move. The only differences are that there is usually no
 * fee and that someone has to agree to take the player on.
 */
@Entity
@Getter
@Setter
public class Loan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    /** The club that owns him and keeps his contract. */
    @Column(name = "parent_club_id", nullable = false)
    private Long parentClubId;

    /** The club taking him on. */
    @Column(name = "borrowing_club_id", nullable = false)
    private Long borrowingClubId;

    private Integer season;

    private Integer startWeek;
    private Integer endWeek;

    /**
     * What the borrowing club pays toward the player's wage, as a share of it.
     *
     * <p>0 means the parent club pays the lot, which is the usual arrangement for a young player
     * sent out for minutes.
     */
    private Double wageContribution = 0.0;

    /** Optional: a fee to make the move permanent later. Null means no clause was agreed. */
    private Double buyClause;

    /** What the parent club was willing to accept as a fee for the loan itself. */
    private Double loanFee;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LoanStatus status = LoanStatus.PROPOSED;

    private Instant startedAt = Instant.now();
    private Instant endedAt;

    public enum LoanStatus {
        /** Offered by one club, not yet accepted. */
        PROPOSED,
        /** Agreed, waiting for the window. */
        AGREED,
        /** Running. */
        ACTIVE,
        /** Finished normally. */
        COMPLETED,
        /** The parent club pulled him back early. */
        RECALLED,
        /** The borrowing club sent him back early. */
        RETURNED_EARLY,
        /** Called off before it started. */
        CANCELLED
    }

    /** Whether the loan is running right now. */
    public boolean isRunning(int week) {
        return status == LoanStatus.ACTIVE
                && startWeek != null && endWeek != null
                && week >= startWeek && week <= endWeek;
    }

    /**
     * Whether a permanent move is now available, and for how much.
     *
     * <p>Only a running loan can be converted, and only if a buy clause was agreed. A club cannot
     * convert a loan it has not got.
     */
    public boolean hasBuyOption() {
        return buyClause != null && buyClause > 0;
    }

    public String describe() {
        return "Loan weeks " + startWeek + "-" + endWeek + " — " + status.name().toLowerCase();
    }
}
