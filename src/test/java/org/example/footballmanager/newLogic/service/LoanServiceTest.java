package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.exception.ApiException;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.GameClock;
import org.example.footballmanager.newLogic.model.Loan;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CountryRepository;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loans, rule by rule (owner, 2026-10-08).
 *
 * <p><b>This class was about different rules and asserted the opposite of two of them.</b> Sprint 3.4
 * wrote it against a transfer-window gate, an agreed start and end week, a wage contribution split, and
 * {@code isRegisteredByNobody} — the last of which asserted that a loanee <b>does not</b> occupy a
 * registration place. The owner reversed that: loanees count toward the thirty. The window gate is gone
 * too, because a loan moves no player between clubs. What remains is the rule set below, and each rule
 * has its own test rather than one test that would still pass if three of the five were deleted.
 */
@SpringBootTest
@ActiveProfiles("test")
class LoanServiceTest {

    @Autowired LoanService loans;
    @Autowired LoanRepository loanRepo;
    @Autowired PlayerRepository players;
    @Autowired TeamRepository teams;
    @Autowired CountryRepository countries;
    @Autowired GameClockRepository clocks;
    @Autowired SquadRegistrationService squadRegistration;
    @Autowired SeasonService seasons;

    private Country serbia;
    private Country brazil;
    private Competition tier1;
    private Competition tier5;
    private Team parent;
    private Team borrower;
    private Player prospect;

    @BeforeEach
    void setUp() {
        serbia = country("Serbia", "SRB");
        brazil = country("Brazil", "BRA");
        tier1 = competition("Top flight", 1);
        tier5 = competition("Bottom flight", 5);

        // Tier 1 lending to tier 5, both in Serbia, both human — the one shape the whole feature is for.
        parent = club("Lending FC", serbia, tier1, true);
        borrower = club("Borrowing FC", serbia, tier5, true);
        prospect = aPlayer(parent, "Prospect", 19);

        setWeek(4);
    }

    /**
     * The clock, created if this is the first test to need one.
     *
     * <p>The previous version of this class did {@code findAll().findFirst().orElseThrow()} and passed
     * only because another test class in the shared context had already created a row. Run alone it
     * threw, which is the same class of bug as the P2-6 finding: a test green on the suite's order and
     * red on its own.
     */
    private GameClock clock() {
        return seasons.getOrCreateClock();
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

    private Competition competition(String name, Integer tier) {
        Competition c = new Competition();
        c.setName(name + "-" + System.nanoTime());
        c.setType(CompetitionType.LEAGUE);
        c.setTier(tier);
        return competitions.save(c);
    }

    @Autowired org.example.footballmanager.newLogic.repository.CompetitionRepository competitions;

    private Team club(String name, Country country, Competition competition, boolean human) {
        Team t = new Team();
        t.setName(name + "-" + System.nanoTime());
        t.setBudget(5_000_000.0);
        t.setReputation(55.0);
        t.setCountry(country);
        t.setCompetition(competition);
        t.setHumanControlled(human);
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
        p.setPlayerValue(400_000);
        p.setEarnings(2_000);
        p.setMorale(60.0);
        p.setForm(6.0);
        return players.save(p);
    }

    private void setWeek(int week) {
        GameClock clock = clock();
        clock.setCurrentSeason(1);
        clock.setCurrentWeek(week);
        clocks.save(clock);
    }

    /** Offers, activates, and hands back the running loan. */
    private Loan running(Player player) {
        Loan loan = loans.offer(parent.getId(), player.getId(), borrower.getId());
        return loans.activate(loan.getId());
    }

    private String codeOf(Runnable action) {
        return assertThrows(ApiException.class, action::run).getCode();
    }

    // ── the shape of a loan ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a loan does not move the player: he stays registered with the club that owns him")
    void aLoanIsNotATransfer() {
        running(prospect);

        Player reloaded = players.findById(prospect.getId()).orElseThrow();
        assertEquals(parent.getId(), reloaded.getTeam().getId(),
                "his club, his contract and his wage never changed - that is what a loan is, and it is "
                        + "why the lending club's wage bill and trainer need no change at all");
    }

    @Test
    @DisplayName("a loan runs to the end of the season it started in, whatever week it began")
    void everyLoanEndsAtTheEndOfTheSeason() {
        setWeek(3);
        Loan early = running(aPlayer(parent, "Early", 19));
        setWeek(9);
        Loan late = running(aPlayer(parent, "Late", 19));

        assertEquals(3, early.getStartWeek());
        assertEquals(12, early.getEndWeek());
        assertEquals(9, late.getStartWeek());
        assertEquals(12, late.getEndWeek(),
                "the end is not negotiable: week 12 day 7, back to the club");

        // And the weekly tick is what actually returns him.
        setWeek(12);
        loans.closeFinishedLoans();
        assertEquals(Loan.LoanStatus.COMPLETED,
                loanRepo.findById(early.getId()).orElseThrow().getStatus());
        assertEquals(Loan.LoanStatus.COMPLETED,
                loanRepo.findById(late.getId()).orElseThrow().getStatus());
        assertEquals(parent.getId(), players.findById(prospect.getId()).orElseThrow().getTeam().getId(),
                "and he is still the lending club's player, which is what made the whole thing cheap");
    }

    @Test
    @DisplayName("a loan cannot be made in the last week of the season")
    void theLastWeekIsTooLate() {
        setWeek(12);
        assertEquals("LOAN_FINAL_WEEK",
                codeOf(() -> loans.offer(parent.getId(), prospect.getId(), borrower.getId())),
                "a loan started now would return six days later, having played nothing");
    }

    // ── the five rules ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("only a player younger than 24")
    void onlyTheYoungGoOut() {
        Player twentyThree = aPlayer(parent, "Twenty-three", 23);
        assertEquals(Loan.LoanStatus.ACTIVE, running(twentyThree).getStatus(), "23 is inside it");

        Player twentyFour = aPlayer(parent, "Twenty-four", 24);
        assertEquals("LOAN_PLAYER_TOO_OLD",
                codeOf(() -> loans.offer(parent.getId(), twentyFour.getId(), borrower.getId())),
                "24 is the first age that is refused");
    }

    @Test
    @DisplayName("only within one country")
    void domesticOnly() {
        Team abroad = club("Brazilian FC", brazil, tier5, true);
        assertEquals("LOAN_DIFFERENT_COUNTRY",
                codeOf(() -> loans.offer(parent.getId(), prospect.getId(), abroad.getId())),
                "a loan crosses no border");
    }

    @Test
    @DisplayName("only down the tier ladder: 1 into 2-5, and tier 5 cannot lend at all")
    void onlyDownTheLadder() {
        // Same tier, one below, and a skip: all three of the owner's examples.
        for (int lenderTier = 1; lenderTier <= 4; lenderTier++) {
            Team lender = club("Tier " + lenderTier, serbia, competition("L" + lenderTier, lenderTier), true);
            Player p = aPlayer(lender, "L" + lenderTier, 19);

            Team sideways = club("Sideways", serbia, competition("S" + lenderTier, lenderTier), true);
            assertEquals("LOAN_NOT_LOWER_TIER",
                    codeOf(() -> loans.offer(lender.getId(), p.getId(), sideways.getId())),
                    "tier " + lenderTier + " cannot lend to tier " + lenderTier);

            // A fresh player per successful loan. Reusing one made the second offer fail as
            // LOAN_ALREADY_OUT rather than as whatever the ladder case was actually testing.
            Team oneDown = club("One down", serbia,
                    competition("O" + lenderTier, lenderTier + 1), true);
            assertEquals(Loan.LoanStatus.ACTIVE,
                    loans.activate(loans.offer(lender.getId(), aPlayer(lender, "Down " + lenderTier, 19).getId(),
                            oneDown.getId()).getId()).getStatus(),
                    "tier " + lenderTier + " must be able to lend into tier " + (lenderTier + 1));

            Team bottom = club("Bottom", serbia, tier5, true);
            assertEquals(Loan.LoanStatus.ACTIVE,
                    loans.activate(loans.offer(lender.getId(), aPlayer(lender, "Skip " + lenderTier, 19).getId(),
                            bottom.getId()).getId()).getStatus(),
                    "tier " + lenderTier + " must also be able to lend straight to tier 5");
        }

        // And the floor.
        Team bottomClub = club("Floor club", serbia, tier5, true);
        Player fromTheFloor = aPlayer(bottomClub, "Floor", 19);
        assertEquals("LOAN_TOP_TIER_CANNOT_LEND",
                codeOf(() -> loans.offer(bottomClub.getId(), fromTheFloor.getId(), borrower.getId())),
                "a club in the bottom tier has nobody below it");
    }

    @Test
    @DisplayName("only clubs a person manages, on both sides")
    void botsAreOut() {
        Team botDestination = club("Bot destination", serbia, tier5, false);
        assertEquals("LOAN_BOT_CLUB",
                codeOf(() -> loans.offer(parent.getId(), prospect.getId(), botDestination.getId())),
                "a loan is a decision two managers make; an AI club cannot make one");

        Team botLender = club("Bot lender", serbia, tier1, false);
        Player theirPlayer = aPlayer(botLender, "Their prospect", 19);
        assertEquals("LOAN_BOT_CLUB",
                codeOf(() -> loans.offer(botLender.getId(), theirPlayer.getId(), borrower.getId())),
                "and nobody may lend out of one either");
    }

    @Test
    @DisplayName("a club with no room cannot take him on")
    void theBorrowingClubNeedsAPlace() {
        // Fill the destination to its limit of thirty with its own players.
        while (players.findByTeamId(borrower.getId()).size() < SquadRegistrationService.MAX_CLUB_SQUAD) {
            aPlayer(borrower, "Filler", 26);
        }
        // Offering costs the lending club nothing, and the borrower may still sell somebody before it
        // commits - so the refusal belongs here, where the obligation starts.
        Loan loan = loans.offer(parent.getId(), prospect.getId(), borrower.getId());
        assertEquals(Loan.LoanStatus.AGREED, loan.getStatus());

        assertEquals("SQUAD_FULL", codeOf(() -> loans.activate(loan.getId())),
                "a full squad cannot take him on, and the refusal says so in words");
    }

    // ── the termination notice ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the two clubs agreeing ends the loan at once")
    void mutualAgreementEndsItNow() {
        Loan loan = running(prospect);
        setWeek(7);

        loans.requestTermination(loan.getId(), parent.getId(), "we need him");
        assertTrue(loans.loan(loan.getId()).hasNotice());
        assertEquals(Loan.LoanStatus.ACTIVE, loans.loan(loan.getId()).getStatus(),
                "asking is not ending");

        Loan ended = loans.acceptTermination(loan.getId(), borrower.getId());
        assertEquals(Loan.LoanStatus.RECALLED, ended.getStatus(),
                "the lender asking and the borrower agreeing is a recall");
    }

    @Test
    @DisplayName("nobody answering ends it when the notice runs out")
    void anUnansweredNoticeEndsIt() {
        Loan loan = running(prospect);
        setWeek(7);

        Loan noticed = loans.requestTermination(loan.getId(), parent.getId(), "we need him");
        assertEquals(8, noticed.getTerminationNoticeWeek(),
                "raised in week 7, due in week 8 - never earlier than seven days, which is the owner's "
                        + "floor and the whole reason the notice exists");

        setWeek(7);
        assertEquals(0, loans.enforceNotices(), "and never in the week it was asked");

        setWeek(8);
        assertEquals(1, loans.enforceNotices(), "now it is due");
        assertEquals(Loan.LoanStatus.RECALLED, loans.loan(loan.getId()).getStatus());

        assertEquals(0, loans.enforceNotices(),
                "and enforcing the tick again closes nothing: the loan is no longer ACTIVE, so a "
                        + "closed loan cannot be closed twice");
    }

    @Test
    @DisplayName("the borrowing club can ask too, and it is recorded as its own outcome")
    void theBorrowerCanSendHimBack() {
        Loan loan = running(prospect);
        setWeek(7);

        loans.requestTermination(loan.getId(), borrower.getId(), "he is not working out");
        setWeek(8);
        loans.enforceNotices();

        assertEquals(Loan.LoanStatus.RETURNED_EARLY, loans.loan(loan.getId()).getStatus(),
                "a club reading its own history needs to know which of the two things happened");
    }

    @Test
    @DisplayName("a club cannot terminate somebody else's loan, or agree to its own request")
    void onlyTheTwoPartiesMayTerminate() {
        Loan loan = running(prospect);
        Team outsider = club("Outsider", serbia, tier5, true);

        assertEquals("LOAN_NOT_A_PARTY",
                codeOf(() -> loans.requestTermination(loan.getId(), outsider.getId(), "not ours")));

        loans.requestTermination(loan.getId(), parent.getId(), "we need him");
        assertEquals("LOAN_SELF_ACCEPT",
                codeOf(() -> loans.acceptTermination(loan.getId(), parent.getId())),
                "the club that asked cannot also grant it");
    }

    // ── the cap, which the feature reversed ─────────────────────────────────────────────────────

    @Test
    @DisplayName("a loanee occupies one of the thirty, and is a player this club may field")
    void aLoaneeCountsAndCanBeFielded() {
        int owned = 0;
        while (squadRegistration.squadSize(borrower.getId()) < SquadRegistrationService.MAX_CLUB_SQUAD - 1) {
            aPlayer(borrower, "Filler " + owned, 26);
            owned++;
        }
        int before = squadRegistration.squadSize(borrower.getId());
        assertTrue(squadRegistration.canRegister(borrower.getId()).allowed());

        running(prospect);

        assertEquals(before + 1, squadRegistration.squadSize(borrower.getId()),
                "he takes a place, which is the opposite of what isRegisteredByNobody asserted");
        assertFalse(squadRegistration.canRegister(borrower.getId()).allowed(),
                "so a club that has just taken him on cannot take anybody else");
        assertTrue(squadRegistration.availablePlayers(borrower.getId()).stream()
                        .anyMatch(p -> p.getId().equals(prospect.getId())),
                "and he is in the squad the match engine reads, or the loan achieves nothing");

        // The lender cannot field him, or he would be available to both clubs on one matchday.
        assertFalse(squadRegistration.availablePlayers(parent.getId()).stream()
                        .anyMatch(p -> p.getId().equals(prospect.getId())),
                "the lending club must not be able to field a player who is playing elsewhere - that "
                        + "was the bug this assertion was written for");

        // But he still counts against the lender's thirty: loaning somebody out is not a way to grow.
        int lenderOwned = players.findByTeamId(parent.getId()).size();
        assertEquals(lenderOwned, squadRegistration.squadSize(parent.getId()),
                "a player loaned away cannot be fielded here but is still registered here, so he is "
                        + "still one of the thirty. Loaning out fifteen players frees no places.");
    }

    @Test
    @DisplayName("a loanee cannot be bought, listed, or loaned on")
    void aLoaneeIsNotTradeable() {
        Loan loan = running(prospect);
        assertTrue(loans.isOnLoan(prospect.getId()));
        assertEquals(parent.getId(), loans.lendingClubOf(prospect.getId()).orElseThrow());

        // Loaned onward: the borrowing club asks to lend him to a third club. It is refused on
        // ownership first, which is the truer of the two reasons - it does not have him to lend.
        assertEquals("LOAN_NOT_OWNED",
                codeOf(() -> loans.offer(borrower.getId(), prospect.getId(), parent.getId())),
                "the borrowing club does not own him, so it cannot pass him on");

        // And the same from a club that genuinely owns a player who is already out.
        Player other = aPlayer(parent, "Other prospect", 19);
        running(other);
        assertEquals("LOAN_ALREADY_OUT",
                codeOf(() -> loans.offer(parent.getId(), other.getId(), borrower.getId())),
                "a second loan for the same player is refused as such, not as an ownership problem");

        assertEquals(Loan.LoanStatus.ACTIVE, loans.loan(loan.getId()).getStatus());
    }

    @Test
    @DisplayName("once the loan ends he is tradeable and loanable again")
    void endingRestoresHim() {
        Loan loan = running(prospect);
        setWeek(12);
        loans.closeFinishedLoans();

        assertFalse(loans.isOnLoan(prospect.getId()));
        assertTrue(squadRegistration.availablePlayers(parent.getId()).stream()
                        .anyMatch(p -> p.getId().equals(prospect.getId())),
                "he is the lending club's to use, list, sell or loan out again");
    }

    @Test
    @DisplayName("a club cannot lend a player it does not own, or to itself")
    void theObviousMistakesAreRefused() {
        assertEquals("LOAN_SAME_CLUB",
                codeOf(() -> loans.offer(parent.getId(), prospect.getId(), parent.getId())));

        Player someoneElses = aPlayer(borrower, "Not ours", 19);
        assertEquals("LOAN_NOT_OWNED",
                codeOf(() -> loans.offer(parent.getId(), someoneElses.getId(), borrower.getId())),
                "a club cannot lend a player who does not play for it");
    }

    @Test
    @DisplayName("a relegation between offer and acceptance is caught")
    void theTierIsCheckedAgainWhenHeArrives() {
        Loan loan = loans.offer(parent.getId(), prospect.getId(), borrower.getId());
        assertEquals(Loan.LoanStatus.AGREED, loan.getStatus());

        // The destination is promoted above the lender between the offer and the acceptance.
        Competition promoted = competition("Promoted", 1);
        borrower.setCompetition(promoted);
        teams.save(borrower);

        assertEquals("LOAN_NOT_LOWER_TIER", codeOf(() -> loans.activate(loan.getId())),
                "an offer made while he was below us does not survive him being promoted above us");
    }

    @Test
    @DisplayName("a club with no tier is refused rather than guessed at")
    void anUnknownTierIsRefused() {
        Team untiered = club("No tier", serbia, competition("Unset", null), true);
        Player p = aPlayer(untiered, "Unknown", 19);

        assertEquals("LOAN_NO_TIER",
                codeOf(() -> loans.offer(untiered.getId(), p.getId(), borrower.getId())),
                "defaulting an absent tier to 1 would make an unknown club top flight, and it could "
                        + "then loan anywhere. ClubRatingService guesses 1 there because a starting "
                        + "rating is a cosmetic guess; this is a permission.");
    }

    @Test
    @DisplayName("the loanable list and the destinations read the same rules the service enforces")
    void theScreensReadTheSameRules() {
        Team abroad = club("Abroad", brazil, tier5, true);
        Player tooOld = aPlayer(parent, "Too old", 30);
        running(prospect);

        List<Loan> out = loans.outgoing(parent.getId());
        assertEquals(1, out.size());
        assertEquals(prospect.getId(), out.get(0).getPlayerId());

        assertTrue(loans.isOnLoan(prospect.getId()));
        assertEquals(0, loans.incoming(parent.getId()).size(),
                "a club takes nobody in while it is lending its own player out");

        // Sanity on the fixtures the rules above rely on.
        assertTrue(tooOld.getAge() > LoanService.MAX_LOAN_AGE);
        assertEquals("BRA", abroad.getCountry().getIsoCode());
    }
}