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
 * A player temporarily at another club (Sprint 3.4, finished 2026-10-08).
 *
 * <p><b>What it is for</b> (owner): a player who is still too young for the tier he plays in goes down
 * to a weaker club, gets minutes, and comes back at the end of the season.
 *
 * <p><b>His {@code Player.team} does not move.</b> He stays registered with the club that owns him and
 * that is what makes a loan a loan rather than a transfer in everything except the football: the owning
 * club's wage bill is computed from {@code findByTeamId}, so the lender keeps paying him without a line
 * changing, and the trainer is run per club, so the lender's coaches are the ones who work on him. The
 * borrowing club gets him through the {@code loan} row, which is the single source of truth for who is
 * out on loan and where.
 *
 * <p>That is the whole design, and it was chosen over moving the player between the two clubs: moving
 * him means every consumer of {@code Team.players} has to decide whether it wants owned players or the
 * ones this club can field, and the two consumers that matter most — the wage bill and the trainer —
 * want the opposite answer to the match engine. Keeping him where he is makes the lender's side correct
 * by construction instead of by remembering a filter, and reduces a loan to one row's status changing.
 *
 * <p><b>He must not go into {@code Team.players} of the borrowing club.</b> That collection is mapped
 * {@code orphanRemoval = true}, so removing him from it would delete the player outright. See
 * {@code SquadRegistrationService.availablePlayers} for where the union actually lives.
 *
 * <p>A loan is not a transfer: the owning club keeps his contract and pays his wage, and he returns when
 * the loan ends. Modelling it as a transfer would be simpler and wrong in a way that matters — the
 * owning club's asset would leave permanently, the wage bill would move, and there would be no way back.
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

    // ── The termination notice (owner, 2026-10-08) ────────────────────────────────────────────
    // Either club may ask for the loan to end. If the other agrees it ends at once; if not, it ends
    // when the notice runs out. Both clubs are protected by the same seven days, because a recall that
    // takes effect immediately is the borrowing club's problem: it has already built the week around a
    // player who is no longer there, and there is no matchday left in which to replace him.
    //
    // Stored as the season week the loan will be closed on rather than as a timestamp, because the
    // game's clock is a (season, week, day) triple and a wall-clock Instant cannot answer "is it a week
    // yet". See LoanService.NOTICE_WEEKS for why it is a week rather than a day count.

    /** The club that asked for the loan to end, or null while no notice is outstanding. */
    @Column(name = "termination_requested_by_club_id")
    private Long terminationRequestedByClubId;

    /** The season week at whose end-of-week tick the loan closes if the notice is not answered. */
    @Column(name = "termination_notice_week")
    private Integer terminationNoticeWeek;

    /** Whether the other club has agreed to end it now. */
    @Column(name = "termination_accepted")
    private Boolean terminationAccepted = Boolean.FALSE;

    /** Why, in the words of whoever asked. */
    @Column(name = "termination_reason")
    private String terminationReason;

    /** Whether a termination request is outstanding and unanswered. */
    public boolean hasNotice() {
        return terminationRequestedByClubId != null
                && !Boolean.TRUE.equals(terminationAccepted);
    }

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
