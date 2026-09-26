package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.Loan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoanRepository extends JpaRepository<Loan, Long> {

    List<Loan> findByPlayerId(Long playerId);

    List<Loan> findByParentClubId(Long parentClubId);

    List<Loan> findByBorrowingClubId(Long borrowingClubId);

    /** Loans that are due to finish, so the weekly tick can close them out. */
    List<Loan> findByStatusAndEndWeekLessThanEqual(Loan.LoanStatus status, Integer week);

    List<Loan> findByPlayerIdAndStatusIn(Long playerId, List<Loan.LoanStatus> statuses);
}
