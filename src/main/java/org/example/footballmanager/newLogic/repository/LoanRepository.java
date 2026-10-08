package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.Loan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Loans, and the one query the rest of the application depends on.
 *
 * <p>{@link #findActiveLoanedIn(Long)} is not a convenience. It is the only definition in the codebase
 * of "which players may this club field", and every screen that offers a manager a player to pick reads
 * it through {@code SquadRegistrationService.availablePlayers}. Written in JPQL rather than derived from
 * a collection on {@code Team} because {@code Team.players} is {@code orphanRemoval = true}: a loanee
 * added there would be deleted when the loan ended, and there is nowhere on the entity to hold a
 * borrowed player without becoming a second source of truth that drifts from this table.
 */
@Repository
public interface LoanRepository extends JpaRepository<Loan, Long> {

    List<Loan> findByPlayerId(Long playerId);

    List<Loan> findByParentClubId(Long parentClubId);

    List<Loan> findByBorrowingClubId(Long borrowingClubId);

    /** Loans that are due to finish, so the weekly tick can close them out. */
    List<Loan> findByStatusAndEndWeekLessThanEqual(Loan.LoanStatus status, Integer week);

    List<Loan> findByPlayerIdAndStatusIn(Long playerId, List<Loan.LoanStatus> statuses);

    /**
     * Every player currently on loan <b>into</b> this club, as player rows.
     *
     * <p>Joined rather than fetched-then-mapped: this is called on the match path, where the whole point
     * of the feature is that a loanee is fieldable, and a loanee who is not in the first squad list is a
     * player who silently never plays.
     *
     * @return rows of {@code [player, loan]} so the caller can tell a loanee from an owned player
     */
    @Query("SELECT l.playerId FROM Loan l WHERE l.borrowingClubId = :clubId AND l.status = :status")
    List<Long> findActiveLoanedInPlayerIds(@Param("clubId") Long clubId,
                                           @Param("status") Loan.LoanStatus status);

    /**
     * Every player this club currently has <b>out</b> on loan.
     *
     * <p>The other half of {@link #findActiveLoanedInPlayerIds}, and the one that stops a loaned player
     * being fieldable by the club that owns him. He keeps his {@code Player.team} here, so a squad list
     * built from a plain {@code findByTeamId} still contains him — and he would then be available to both
     * clubs on the same matchday.
     */
    @Query("SELECT l.playerId FROM Loan l WHERE l.parentClubId = :clubId AND l.status = :status")
    List<Long> findActiveLoanedOutPlayerIds(@Param("clubId") Long clubId,
                                            @Param("status") Loan.LoanStatus status);

    /** The same question answered as loans, for a screen that wants the terms and not just the man. */
    List<Loan> findByBorrowingClubIdAndStatus(Long borrowingClubId, Loan.LoanStatus status);

    List<Loan> findByParentClubIdAndStatus(Long parentClubId, Loan.LoanStatus status);

    /** Notices whose week has arrived, so the tick can close loans nobody agreed to end early. */
    List<Loan> findByStatusAndTerminationNoticeWeekLessThanEqual(Loan.LoanStatus status, Integer week);
}