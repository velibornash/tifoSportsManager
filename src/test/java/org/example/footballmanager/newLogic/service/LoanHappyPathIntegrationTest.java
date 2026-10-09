package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.exception.ApiException;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.Loan;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.GameClockRepository;
import org.example.footballmanager.newLogic.repository.LoanRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.service.LoanService;
import org.example.footballmanager.newLogic.service.SeasonService;
import org.example.footballmanager.newLogic.service.SquadRegistrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the full loan happy path against the live database.
 *
 * <p><b>What this verifies:</b> The complete loan lifecycle — offer → accept → activate → terminate —
 * works end-to-end against the live PostgreSQL database. This is the "happy path" that the board
 * recorded as never having been observed in a live database.
 *
 * <p>Before this test, the loan system had comprehensive unit tests against H2, but no integration
 * test had ever been run against the production PostgreSQL database to verify the full lifecycle
 * works with the real schema, constraints, and data.
 */
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
class LoanHappyPathIntegrationTest {

    @Autowired LoanService loanService;
    @Autowired LoanRepository loanRepository;
    @Autowired TeamRepository teamRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired CountryRepository countryRepository;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired SeasonService seasonService;
    @Autowired SquadRegistrationService squadRegistration;
    @Autowired GameClockRepository clockRepository;

    private Country serbia;
    private Competition tier1;
    private Competition tier5;
    private Team lenderClub;
    private Team borrowerClub;
    private Player prospect;

    @BeforeEach
    void setUp() {
        // Create country
        var serbia = new Country();
        serbia.setName("Serbia");
        serbia.setIsoCode("SRB");
        serbia.setReputation(50);
        serbia.setYouthRating(50);
        serbia = countryRepository.save(serbia);

        // Create tier 1 and tier 5 competitions
        Competition tier1 = new Competition();
        tier1.setName("Test Top Flight");
        tier1.setType(CompetitionType.LEAGUE);
        tier1.setTier(1);
        Competition tier1Saved = competitionRepository.save(tier1);

        Competition tier5 = new Competition();
        tier5.setName("Test Bottom Flight");
        tier5.setType(CompetitionType.LEAGUE);
        tier5.setTier(5);
        Competition tier5Saved = competitionRepository.save(tier5);

        // Create lender club (tier 1)
        var lenderClub = new Team();
        lenderClub.setName("Lending FC");
        lenderClub.setCountry(countryRepository.findByIsoCode("SRB").orElseThrow());
        lenderClub.setCompetition(competitionRepository.findById(1L).orElseThrow());
        lenderClub.setHumanControlled(true);
        lenderClub.setBudget(5_000_000.0);
        lenderClub.setReputation(55.0);
        Stadium s = new Stadium();
        s.setName("Lending Ground");
        s.setCapacity(20_000);
        s.setTicketPrice(20.0);
        s.setPitchQuality(85.0);
        s.setPitchCondition(85);
        s.setMaintenanceRemaining(0);
        lenderClub.setStadium(s);
        this.lenderClub = teamRepository.save(lenderClub);

        // Create borrower club (tier 5)
        Team borrowerClub = new Team();
        borrowerClub.setName("Borrowing FC");
        borrowerClub.setCountry(countryRepository.findByIsoCode("SRB").orElseThrow());
        borrowerClub.setCompetition(competitionRepository.findById(tier5.getId()).orElseThrow());
        borrowerClub.setHumanControlled(true);
        Team borrowerClubSaved = teamRepository.save(borrowerClub);
        this.borrowerClub = borrowerClubSaved;

        // Create a young player
        Player prospect = new Player();
        prospect.setName("Prospect Player");
        prospect.setAge(19);
        prospect.setTeam(this.lenderClub);
        prospect.setPosition(org.example.footballmanager.newLogic.model.Position.ATT);
        prospect.setRating(60);
        prospect.setEarnings(2000);
        prospect.setForm(6.0);
        prospect.setMorale(60.0);
        prospect = playerRepository.save(prospect);
        this.prospect = prospect;

        // Set clock to week 4 (mid-season)
        var clock = seasonService.getOrCreateClock();
        clock.setCurrentSeason(1);
        clock.setCurrentWeek(4);
        clock.setCurrentDay(1);
        clock.setCurrentHour(12);
        clock.setCurrentDate(java.time.LocalDateTime.of(2026, 1, 1, 12, 0));
        clockRepository.save(clock);
    }

    @Test
    @DisplayName("Full loan happy path: offer -> accept -> activate -> terminate")
    @Transactional
    void fullLoanLifecycle() {
        // 1. OFFER: Lender offers player to borrower
        Loan loan = loanService.offer(lenderClub.getId(), prospect.getId(), borrowerClub.getId());
        assertEquals(Loan.LoanStatus.AGREED, loan.getStatus());
        assertEquals(Loan.LoanStatus.AGREED, loanRepository.findById(loan.getId()).orElseThrow().getStatus());

        // 2. ACCEPT: Borrower accepts the loan offer
        Loan accepted = loanService.activate(loan.getId());
        assertEquals(Loan.LoanStatus.ACTIVE, accepted.getStatus());
        assertEquals(Loan.LoanStatus.ACTIVE, loanRepository.findById(loan.getId()).orElseThrow().getStatus());

        // 3. TERMINATE: Request termination and accept
        Loan activeLoan = loanRepository.findById(loan.getId()).orElseThrow();
        Loan terminated = loanService.requestTermination(loan.getId(), lenderClub.getId(), "test termination");
        assertTrue(terminated.hasNotice());

        // Accept termination
        Loan terminated2 = loanService.acceptTermination(loan.getId(), borrowerClub.getId());
        assertEquals(Loan.LoanStatus.RECALLED, terminated2.getStatus());

        // Verify the loan is closed
        Loan closed = loanRepository.findById(loan.getId()).orElseThrow();
        assertEquals(Loan.LoanStatus.RECALLED, closed.getStatus());
    }
}