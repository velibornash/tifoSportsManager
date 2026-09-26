package org.example.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.model.GameClock;
import org.example.footballmanager.newLogic.model.Loan;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.SeasonCalendar;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.GameClockRepository;
import org.example.footballmanager.newLogic.repository.LoanRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Loans (Sprint 3.4).
 *
 * <p>The standard development pathway, and the answer to a youth cap: a club with fifteen promising
 * teenagers cannot register them all, so it sends some out. That only works if a loan is genuinely
 * temporary — the player keeps his contract, his wage stays with the club that owns him, and he comes
 * back. Modelled as a transfer it would be simpler and permanently wrong.
 *
 * <p>Two rules the owner was explicit about:
 * <ul>
 *   <li><b>A loan in is not exempt from the window.</b> It goes through the same
 *       {@link TransferWindowService} gate as a permanent move. A club cannot sign in April by
 *       calling it a loan.</li>
 *   <li><b>Loan players are squad depth, not registrations.</b> They do not count towards the 25
 *       senior places, which is the entire point of sending one out.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoanService {

    /** Longest a loan may run, in weeks. A season is twelve. */
    public static final int MAX_LOAN_WEEKS = SeasonCalendar.WEEKS_PER_SEASON;

    /** Shortest worth bothering with. */
    public static final int MIN_LOAN_WEEKS = 4;

    private final LoanRepository loans;
    private final PlayerRepository players;
    private final TransferWindowService windows;
    private final PlayerContractService contracts;
    private final GameClockRepository clocks;

    /**
     * Offers a player out on loan.
     *
     * <p>Returns empty with a logged reason for every ordinary refusal, because each one is
     * something a manager does by accident: loaning a player who is already out, loaning him for a
     * season, or loaning him to his own club.
     */
    @Transactional
    public Optional<Loan> offer(Long parentClubId, Long playerId, Long borrowingClubId,
                                int startWeek, int endWeek, double wageContribution,
                                Double buyClause, double loanFee) {
        if (Objects.equals(parentClubId, borrowingClubId)) {
            log.warn("{} cannot loan a player to itself", parentClubId);
            return Optional.empty();
        }
        Player player = players.findById(playerId).orElse(null);
        if (player == null) {
            log.warn("Loan refused: player {} does not exist", playerId);
            return Optional.empty();
        }
        if (endWeek - startWeek + 1 < MIN_LOAN_WEEKS) {
            log.warn("Loan refused for {}: {} weeks is not a loan, it is a mistake",
                    playerId, endWeek - startWeek + 1);
            return Optional.empty();
        }
        if (endWeek - startWeek + 1 > MAX_LOAN_WEEKS) {
            log.warn("Loan refused for {}: {} weeks is longer than a season",
                    playerId, endWeek - startWeek + 1);
            return Optional.empty();
        }

        // A club cannot lend a player it does not own, nor one who is already out on loan.
        if (!Objects.equals(player.getTeam() == null ? null : player.getTeam().getId(), parentClubId)) {
            log.warn("Loan refused for {}: he does not play for the club offering him", playerId);
            return Optional.empty();
        }
        boolean alreadyOut = !loans.findByPlayerIdAndStatusIn(playerId,
                List.of(Loan.LoanStatus.PROPOSED, Loan.LoanStatus.AGREED, Loan.LoanStatus.ACTIVE))
                .isEmpty();
        if (alreadyOut) {
            log.warn("Loan refused for {}: he is already out on loan", playerId);
            return Optional.empty();
        }

        Loan loan = new Loan();
        loan.setPlayerId(playerId);
        loan.setParentClubId(parentClubId);
        loan.setBorrowingClubId(borrowingClubId);
        loan.setSeason(currentSeason());
        loan.setStartWeek(startWeek);
        loan.setEndWeek(endWeek);
        loan.setWageContribution(clamp(wageContribution, 0, 1));
        loan.setBuyClause(buyClause == null || buyClause <= 0 ? null : buyClause);
        loan.setLoanFee(Math.max(0, loanFee));
        loan.setStatus(Loan.LoanStatus.PROPOSED);
        return Optional.of(loans.save(loan));
    }

    /**
     * The borrowing club accepts.
     *
     * <p>The window gate is here rather than in {@link #offer} on purpose: offering costs nothing
     * and a club may line a player up months ahead. <b>Signing</b> him is what needs the window, and
     * that is what happens when the loan starts.
     */
    @Transactional
    public Loan accept(Long loanId) {
        Loan loan = loans.findById(loanId).orElseThrow(
                () -> new IllegalArgumentException("No loan " + loanId));
        if (loan.getStatus() != Loan.LoanStatus.PROPOSED) {
            return loan;
        }
        loan.setStatus(Loan.LoanStatus.AGREED);
        return loans.save(loan);
    }

    /**
     * Starts an agreed loan, if the window allows it that week.
     *
     * <p>Returns the loan either way; the status says whether it started. A refusal is logged with
     * the reason, since "it did not happen" is otherwise very hard to see.
     */
    @Transactional
    public Loan start(Long loanId) {
        Loan loan = loans.findById(loanId).orElseThrow(
                () -> new IllegalArgumentException("No loan " + loanId));
        if (loan.getStatus() != Loan.LoanStatus.AGREED) {
            return loan;
        }

        TransferWindowService.Decision window = windows.decide(TransferWindowService.Kind.PERMANENT);
        if (!window.permitted()) {
            log.warn("Loan {} cannot start: {}", loanId, window.reason());
            return loan;
        }

        loan.setStatus(Loan.LoanStatus.ACTIVE);
        loan.setStartedAt(java.time.Instant.now());
        return loans.save(loan);
    }

    /**
     * The parent club takes him back before the loan ends.
     *
     * <p>Allowed mid-loan, and deliberately not window-gated: a club recalling its own player is not
     * a transfer, and blocking it would strand a player in a squad that no longer wants him.
     */
    @Transactional
    public Loan recall(Long loanId, String reason) {
        Loan loan = loans.findById(loanId).orElseThrow(
                () -> new IllegalArgumentException("No loan " + loanId));
        if (loan.getStatus() != Loan.LoanStatus.ACTIVE) return loan;
        loan.setStatus(Loan.LoanStatus.RECALLED);
        loan.setEndedAt(java.time.Instant.now());
        log.info("Loan {} recalled: {}", loanId, reason == null ? "no reason given" : reason);
        return loans.save(loan);
    }

    /** The borrowing club sends him back early — he is not working out. */
    @Transactional
    public Loan returnEarly(Long loanId) {
        Loan loan = loans.findById(loanId).orElseThrow(
                () -> new IllegalArgumentException("No loan " + loanId));
        if (loan.getStatus() != Loan.LoanStatus.ACTIVE) return loan;
        loan.setStatus(Loan.LoanStatus.RETURNED_EARLY);
        loan.setEndedAt(java.time.Instant.now());
        return loans.save(loan);
    }

    /** Closes out loans whose weeks are done. Run by the weekly tick. */
    @Transactional
    public int closeFinishedLoans() {
        int week = currentWeek();
        List<Loan> finishing = loans.findByStatusAndEndWeekLessThanEqual(Loan.LoanStatus.ACTIVE, week);
        for (Loan loan : finishing) {
            loan.setStatus(Loan.LoanStatus.COMPLETED);
            loan.setEndedAt(java.time.Instant.now());
        }
        if (!finishing.isEmpty()) loans.saveAll(finishing);
        return finishing.size();
    }

    /**
     * Whether this club may register the player as a first-team player.
     *
     * <p>The cap exemption, and the reason a loan exists: a loanee adds depth without costing a
     * registration place. A player on loan is registered by nobody.
     */
    @Transactional(readOnly = true)
    public boolean isRegisteredByNobody(Long playerId) {
        return !loans.findByPlayerIdAndStatusIn(playerId,
                List.of(Loan.LoanStatus.ACTIVE)).isEmpty();
    }

    /** Whether a loan can be turned into a permanent move right now, and at what price. */
    @Transactional(readOnly = true)
    public Optional<Double> buyOption(Long playerId) {
        return loans.findByPlayerIdAndStatusIn(playerId, List.of(Loan.LoanStatus.ACTIVE)).stream()
                .filter(Loan::hasBuyOption)
                .map(Loan::getBuyClause)
                .findFirst();
    }

    /** The wage the borrowing club actually carries, after its contribution. */
    @Transactional(readOnly = true)
    public double wageCarriedBy(Long playerId) {
        return loans.findByPlayerIdAndStatusIn(playerId, List.of(Loan.LoanStatus.ACTIVE)).stream()
                .findFirst()
                .map(loan -> {
                    Player player = players.findById(playerId).orElse(null);
                    double wage = player == null ? 0 : player.getEarnings();
                    return round2(wage * (1 - (loan.getWageContribution() == null ? 0
                            : loan.getWageContribution())));
                })
                .orElse(0.0);
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private int currentSeason() {
        GameClock clock = clocks.findAll().stream().findFirst().orElse(null);
        return clock == null || clock.getCurrentSeason() == null ? 1 : clock.getCurrentSeason();
    }

    private int currentWeek() {
        GameClock clock = clocks.findAll().stream().findFirst().orElse(null);
        return clock == null || clock.getCurrentWeek() == null ? 1 : clock.getCurrentWeek();
    }
}
