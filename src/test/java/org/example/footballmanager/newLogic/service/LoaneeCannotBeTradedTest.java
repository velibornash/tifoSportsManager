package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.exception.ApiException;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.GameClock;
import org.example.footballmanager.newLogic.model.Loan;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.SquadRole;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.GameClockRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A player on loan cannot be traded, and every path that moves a player checks (owner, 2026-10-08).
 *
 * <p><b>This class exists because a mutation went uncaught.</b> The guards in {@code TransferService}
 * and {@code PlayerContractService} were written, believed to be covered, and then
 * {@code LoanServiceTest} passed 19/19 with the "cannot list a loanee" guard deleted outright. They had
 * no test. This is the same failure as the P2-6 contract count and the nineteen-year-old band — a guard
 * that everybody assumes is tested because it is obviously necessary.
 *
 * <p><b>Why it matters and is not tidiness.</b> A loanee keeps {@code Player.team} on the club that
 * owns him, so {@code requirePlayerTeam} resolves the <b>lender</b> as the seller. Without the guard a
 * buyer completes the purchase, the fee goes to a club he is not playing for, and the borrowing club
 * carries on fielding a player who has been sold out from under it.
 */
@SpringBootTest
@ActiveProfiles("test")
class LoaneeCannotBeTradedTest {

    @Autowired LoanService loans;
    @Autowired TransferService transfers;
    @Autowired PlayerContractService contracts;
    @Autowired PlayerRepository players;
    @Autowired TeamRepository teams;
    @Autowired CountryRepository countries;
    @Autowired CompetitionRepository competitions;
    @Autowired GameClockRepository clocks;
    @Autowired SeasonService seasons;

    private Team lender;
    private Team borrower;
    private Player loanee;

    @BeforeEach
    void setUp() {
        Country country = country("Loanland", "LND");
        lender = club("Owning FC", country, competition("Top", 1));
        borrower = club("Borrowing FC", country, competition("Bottom", 5));
        loanee = aPlayer(lender, "On loan", 19);

        GameClock clock = seasons.getOrCreateClock();
        clock.setCurrentSeason(1);
        clock.setCurrentWeek(4);
        clocks.save(clock);

        Loan loan = loans.offer(lender.getId(), loanee.getId(), borrower.getId());
        loans.activate(loan.getId());
    }

    private Country country(String name, String iso) {
        for (Country c : countries.findAll()) {
            if (iso.equals(c.getIsoCode())) return c;
        }
        Country c = new Country();
        c.setName(name + "-" + System.nanoTime());
        c.setIsoCode(iso);
        return countries.save(c);
    }

    private Competition competition(String name, int tier) {
        Competition c = new Competition();
        c.setName(name + "-" + System.nanoTime());
        c.setType(CompetitionType.LEAGUE);
        c.setTier(tier);
        return competitions.save(c);
    }

    private Team club(String name, Country country, Competition competition) {
        Team t = new Team();
        t.setName(name + "-" + System.nanoTime());
        t.setBudget(50_000_000.0);
        t.setReputation(55.0);
        t.setCountry(country);
        t.setCompetition(competition);
        t.setHumanControlled(true);
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

    private Player aPlayer(Team club, String name, int age) {
        Player p = new Player();
        p.setName(name + "-" + System.nanoTime());
        p.setTeam(club);
        p.setAge(age);
        p.setPosition(Position.MID);
        p.setPlayerValue(400_000);
        p.setEarnings(2_000);
        p.setMorale(60.0);
        p.setForm(6.0);
        return players.save(p);
    }

    private String codeOf(Runnable action) {
        return assertThrows(ApiException.class, action::run).getCode();
    }

    @Test
    @DisplayName("a loanee cannot be put up for transfer")
    void cannotBeListed() {
        assertEquals("PLAYER_ON_LOAN",
                codeOf(() -> transfers.listPlayerForTransfer(loanee.getId(), 500_000)),
                "without this the buyer's fee goes to the club he is not playing for, and the borrower "
                        + "keeps fielding a player who has been sold");
    }

    @Test
    @DisplayName("a loanee cannot be signed")
    void cannotBeSigned() {
        PlayerContractService.Outcome outcome =
                contracts.sign(borrower.getId(), loanee.getId(), 24, 4_000, SquadRole.ROTATION);

        assertEquals(false, outcome.signed(), "signing him would leave a contract with one club while "
                + "another paid his wage and fielded him");
        assertNotNull(outcome.reason());
        org.junit.jupiter.api.Assertions.assertTrue(outcome.reason().contains("on loan"),
                "and the refusal has to say why: " + outcome.reason());
    }

    @Test
    @DisplayName("the lender can still trade him once the loan has ended")
    void tradableAgainAfterwards() {
        GameClock clock = seasons.getOrCreateClock();
        clock.setCurrentWeek(12);
        clocks.save(clock);
        loans.closeFinishedLoans();

        org.junit.jupiter.api.Assertions.assertFalse(loans.isOnLoan(loanee.getId()),
                "precondition: the loan is over");

        var listing = transfers.listPlayerForTransfer(loanee.getId(), 500_000);
        assertNotNull(listing, "the guard is on the loan and not on the player, so he is sellable again "
                + "the moment it ends");
    }

    @Test
    @DisplayName("an owned player who is not on loan is unaffected by any of this")
    void theGuardDoesNotTouchOrdinaryPlayers() {
        Player ordinary = aPlayer(lender, "Not lent out", 19);

        var listing = transfers.listPlayerForTransfer(ordinary.getId(), 500_000);

        assertNotNull(listing, "a guard that refuses every listing is not a guard, it is an outage");
    }
}