package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Loan;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.GameClockRepository;
import org.example.footballmanager.newLogic.repository.LoanRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loans (Sprint 3.4).
 *
 * <p>A loan is the answer to a youth cap: a club with fifteen teenagers cannot register them all, so
 * it sends some out. That only means anything if the move is genuinely temporary. The rules the
 * owner was explicit about are the ones tested here — a loan in is not exempt from the window, and
 * a loanee is squad depth rather than a registration.
 */
@SpringBootTest
@ActiveProfiles("test")
class LoanServiceTest {

    @Autowired LoanService loans;
    @Autowired LoanRepository loanRepo;
    @Autowired PlayerRepository players;
    @Autowired TeamRepository teams;
    @Autowired GameClockRepository clocks;

    private Team parent;
    private Team borrower;
    private Player prospect;

    @BeforeEach
    void setUp() {
        parent = club("Parent club");
        borrower = club("Borrowing club");
        prospect = aPlayer(parent, "Prospect", 2_000);

        var clock = clocks.findAll().stream().findFirst().orElseThrow();
        clock.setCurrentWeek(TransferWindowService.SUMMER_OPEN);
        clock.setCurrentSeason(1);
        clocks.save(clock);
    }

    private Team club(String name) {
        Team t = new Team();
        t.setName(name + "-" + System.nanoTime());
        t.setBudget(5_000_000.0);
        t.setReputation(55.0);
        Stadium s = new Stadium();
        s.setName(name + " Ground");
        s.setCapacity(20_000);
        s.setTicketPrice(18.0);
        s.setPitchQuality(85.0);
        s.setPitchCondition(85);
        s.setMaintenanceRemaining(0);
        t.setStadium(s);
        return teams.save(t);
    }

    private Player aPlayer(Team club, String name, double wage) {
        Player p = new Player();
        p.setName(name + "-" + System.nanoTime());
        p.setTeam(club);
        p.setAge(19);
        p.setPlayerValue(400_000);
        p.setEarnings(wage);
        p.setMorale(60.0);
        p.setForm(6.0);
        return players.save(p);
    }

    @Test
    @DisplayName("a loan runs without the player leaving the club that owns him")
    void aLoanIsNotATransfer() {
        Optional<Loan> offered = loans.offer(parent.getId(), prospect.getId(), borrower.getId(),
                5, 10, 0.0, 750_000.0, 0);
        assertTrue(offered.isPresent(), "a sensible loan should be offerable");

        Loan loan = loans.accept(offered.get().getId());
        assertEquals(Loan.LoanStatus.AGREED, loan.getStatus());
        loans.start(loan.getId());

        Player reloaded = players.findById(prospect.getId()).orElseThrow();
        assertEquals(parent.getId(), reloaded.getTeam().getId(),
                "his contract and his club never changed - that is what a loan is");
    }

    @Test
    @DisplayName("a loan in does not dodge the transfer window")
    void aLoanIsNotExemptFromTheWindow() {
        var clock = clocks.findAll().stream().findFirst().orElseThrow();
        clock.setCurrentWeek(3);                       // no window in week 3
        clocks.save(clock);

        Loan offered = loans.offer(parent.getId(), prospect.getId(), borrower.getId(),
                5, 10, 0.0, null, 0).orElseThrow();
        loans.accept(offered.getId());

        // Offering and agreeing are free; starting the loan is signing a player, and that is gated.
        Loan started = loans.start(offered.getId());
        assertEquals(Loan.LoanStatus.AGREED, started.getStatus(),
                "a club cannot sign in April by calling it a loan");
    }

    @Test
    @DisplayName("a loanee is registered by nobody, which is the point of him")
    void aLoaneeUsesNoRegistrationPlace() {
        Loan offered = loans.offer(parent.getId(), prospect.getId(), borrower.getId(),
                5, 10, 0.0, null, 0).orElseThrow();
        loans.accept(offered.getId());
        loans.start(offered.getId());

        assertTrue(loans.isRegisteredByNobody(prospect.getId()),
                "a player on loan is registered by nobody, which is why a cap can be worked around");
    }

    @Test
    @DisplayName("a finished loan closes itself, and can be recalled before then")
    void loansEndThemselves() {
        Loan offered = loans.offer(parent.getId(), prospect.getId(), borrower.getId(),
                5, 8, 0.0, null, 0).orElseThrow();
        loans.accept(offered.getId());
        loans.start(offered.getId());

        // Nothing is due yet: the loan runs to week 8 and the calendar is on week 5.
        assertEquals(0, loans.closeFinishedLoans(), "a loan is not finished on the day it starts");
        assertEquals(Loan.LoanStatus.ACTIVE, loanRepo.findById(offered.getId()).orElseThrow().getStatus());

        // Come back to week 9 and it is over.
        var clock = clocks.findAll().stream().findFirst().orElseThrow();
        clock.setCurrentWeek(9);
        clocks.save(clock);

        loans.closeFinishedLoans();
        assertEquals(Loan.LoanStatus.COMPLETED, loanRepo.findById(offered.getId()).orElseThrow().getStatus());

        // A recall mid-loan is allowed: a club pulling back its own player is not a transfer. It
        // needs the clock back in a window, because the recall test starts a second loan.
        var backInWindow = clocks.findAll().stream().findFirst().orElseThrow();
        backInWindow.setCurrentWeek(TransferWindowService.SUMMER_OPEN);
        clocks.save(backInWindow);

        Player second = aPlayer(parent, "Second", 1_500);
        Loan recallable = loans.offer(parent.getId(), second.getId(), borrower.getId(),
                5, 11, 0.0, null, 0).orElseThrow();
        loans.accept(recallable.getId());
        loans.start(recallable.getId());
        assertEquals(Loan.LoanStatus.RECALLED,
                loans.recall(recallable.getId(), "he is needed here").getStatus());
    }

    @Test
    @DisplayName("only a running loan with a clause can be turned into a permanent move")
    void theBuyOptionNeedsBoth() {
        // No clause agreed.
        Loan noClause = loans.offer(parent.getId(), prospect.getId(), borrower.getId(),
                5, 10, 0.0, null, 0).orElseThrow();
        loans.accept(noClause.getId());
        loans.start(noClause.getId());
        assertTrue(loans.buyOption(prospect.getId()).isEmpty(),
                "a club cannot convert a loan that was never optioned");

        // A clause, but the loan has not started.
        Player second = aPlayer(parent, "Optioned", 1_800);
        Loan agreed = loans.offer(parent.getId(), second.getId(), borrower.getId(),
                5, 10, 0.0, 900_000.0, 0).orElseThrow();
        loans.accept(agreed.getId());
        assertTrue(loans.buyOption(second.getId()).isEmpty(),
                "an option nobody has taken up is not an option");
    }

    @Test
    @DisplayName("the borrowing club carries only its share of the wage")
    void wageContributionIsRespected() {
        Player p = aPlayer(parent, "Shared wage", 4_000);
        Loan loan = loans.offer(parent.getId(), p.getId(), borrower.getId(),
                5, 10, 0.25, null, 0).orElseThrow();
        loans.accept(loan.getId());
        loans.start(loan.getId());

        assertEquals(3_000.0, loans.wageCarriedBy(p.getId()), 0.01,
                "a quarter of the wage is the borrowing club's problem, the rest is the parent's");
    }

    @Test
    @DisplayName("silly loans are refused: your own player, too short, too long, already out")
    void theObviousMistakesAreRefused() {
        assertTrue(loans.offer(parent.getId(), prospect.getId(), parent.getId(),
                5, 10, 0.0, null, 0).isEmpty(), "a club cannot loan a player to itself");

        // Each case uses its own player: a loan that is only proposed still counts as the player
        // being out, so reusing one player would make every case after the first fail for the
        // wrong reason.
        assertTrue(loans.offer(parent.getId(), aPlayer(parent, "Minimum", 1_000).getId(),
                borrower.getId(), 5, 8, 0.0, null, 0).isPresent(),
                "four weeks is the shortest loan worth arranging");
        assertTrue(loans.offer(parent.getId(), aPlayer(parent, "Too short", 1_000).getId(),
                borrower.getId(), 5, 7, 0.0, null, 0).isEmpty(), "three weeks is a mis-click");

        assertTrue(loans.offer(parent.getId(), aPlayer(parent, "Too long", 1_000).getId(),
                borrower.getId(), 2, 20, 0.0, null, 0).isEmpty(),
                "longer than a season is not a loan");

        Player someoneElses = aPlayer(borrower, "Not ours", 1_000);
        assertTrue(loans.offer(parent.getId(), someoneElses.getId(), borrower.getId(),
                5, 10, 0.0, null, 0).isEmpty(),
                "a club cannot lend a player who does not play for it");

        Loan first = loans.offer(parent.getId(), prospect.getId(), borrower.getId(),
                5, 10, 0.0, null, 0).orElseThrow();
        loans.accept(first.getId());
        assertTrue(loans.offer(parent.getId(), prospect.getId(), borrower.getId(),
                6, 11, 0.0, null, 0).isEmpty(), "a player already out cannot be loaned again");
    }
}
