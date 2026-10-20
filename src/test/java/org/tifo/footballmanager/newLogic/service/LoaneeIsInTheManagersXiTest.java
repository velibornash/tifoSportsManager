package org.tifo.footballmanager.newLogic.service;

import org.tifo.footballmanager.newLogic.model.Lineup;
import org.tifo.footballmanager.newLogic.model.Loan;
import org.tifo.footballmanager.newLogic.model.Player;
import org.tifo.footballmanager.newLogic.model.PlayerRole;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.repository.LoanRepository;
import org.tifo.footballmanager.newLogic.repository.PlayerRepository;
import org.tifo.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A loaned-in player IS in the manager's XI while he is here (owner, 2026-10-19).
 *
 * <p><b>What was wrong.</b> Three writers disagreed about who is in the XI, and all three were wrong
 * the same way:
 *
 * <ul>
 *   <li>{@code Lineup.currentPlayers} filtered the saved XI by {@code player.getTeam()}. A loanee keeps
 *       his <b>owning</b> club there — a loan never changes it — so he was dropped from the XI on the
 *       way out.</li>
 *   <li>{@code MatchLineupService.resolvePlayers} filtered the same way, so a manager could not
 *       <b>save</b> a loanee in the first place.</li>
 *   <li>{@code TeamTacticsService.saveLineup} took the club's owned players and never saw loanees at
 *       all.</li>
 * </ul>
 *
 * <p>The rule the owner confirmed already existed in one place —
 * {@code SquadRegistrationService.availablePlayers}: <b>owned, minus the ones loaned away, plus the
 * ones borrowed in</b> — and the entity could not use it, because the {@code Loan} table stores a
 * plain player id and the entity has no way to ask who is on loan. So the rule moved out of the entity
 * and into the service, and every reader now asks the service.
 *
 * <p><b>The control matters.</b> A player who belongs to another club and is <em>not</em> on loan must
 * still be refused, or the fix is just "accept everybody".
 */
@SpringBootTest
@ActiveProfiles("test")
class LoaneeIsInTheManagersXiTest {

    @Autowired SquadRegistrationService squadRegistration;
    @Autowired LoanRepository loans;
    @Autowired PlayerRepository players;
    @Autowired TeamRepository teams;

    private Team club;
    private Team otherClub;
    private Player loanee;
    private Player foreign;

    @BeforeEach
    void setUp() {
        club = teams.save(aTeam("Loan Club " + System.nanoTime()));
        otherClub = teams.save(aTeam("Loan Owner " + System.nanoTime()));

        loanee = players.save(aPlayer("Loanee", otherClub));
        foreign = players.save(aPlayer("Foreign", otherClub));
    }

    private Team aTeam(String name) {
        Team team = new Team();
        team.setName(name);
        return team;
    }

    private Player aPlayer(String name, Team owner) {
        Player player = new Player();
        player.setName(name);
        player.setTeam(owner);
        player.setRole(PlayerRole.CENTRE_MIDFIELDER);
        return player;
    }

    private void lend(Player player) {
        Loan loan = new Loan();
        loan.setPlayerId(player.getId());
        loan.setParentClubId(otherClub.getId());
        loan.setBorrowingClubId(club.getId());
        loan.setStatus(Loan.LoanStatus.ACTIVE);
        loan.setSeason(1);
        loan.setStartWeek(1);
        loan.setEndWeek(20);
        loans.save(loan);
    }

    /** A lineup of the club, with the given players in it. */
    private Lineup aLineupOf(Player... chosen) {
        Lineup lineup = new Lineup();
        lineup.setTeam(club);
        lineup.setStartingPlayers(List.of(chosen));
        lineup.setStarterOrderFromIds(List.of(chosen).stream().map(Player::getId).toList());
        return lineup;
    }

    @Test
    @DisplayName("a borrowed-in player is on the club's books")
    void aBorrowedInPlayerIsOnTheBooks() {
        lend(loanee);

        assertTrue(squadRegistration.onTheBooks(club.getId()).contains(loanee.getId()),
                "a player borrowed in is not on the borrowing club's books, so the loan achieves nothing");
    }

    @Test
    @DisplayName("a borrowed-in player stays in the manager's XI")
    void aBorrowedInPlayerStaysInTheXi() {
        lend(loanee);
        Lineup lineup = aLineupOf(loanee);

        List<Player> eleven = squadRegistration.theEleven(lineup);

        assertTrue(eleven.stream().anyMatch(p -> loanee.getId().equals(p.getId())),
                "the loanee was dropped from the manager's XI. His Player.team is still his owning "
                        + "club - a loan never changes it - so filtering on that dropped him");
    }

    @Test
    @DisplayName("a player who is not on loan to us is not in our XI")
    void aForeignPlayerIsNotInOurXi() {
        Lineup lineup = aLineupOf(foreign);

        List<Player> eleven = squadRegistration.theEleven(lineup);

        assertFalse(eleven.stream().anyMatch(p -> foreign.getId().equals(p.getId())),
                "a player who belongs to another club and is not on loan to us is in our XI. The fix "
                        + "is not 'accept everybody'");
    }

    @Test
    @DisplayName("a player loaned away is not in the lending club's XI")
    void aPlayerLoanedAwayIsNotInOurXi() {
        // The other half of the rule: a loan out takes him off the lending club's books, and he
        // must not be fielded by them while he plays for somebody else.
        Loan loan = new Loan();
        loan.setPlayerId(loanee.getId());
        loan.setParentClubId(club.getId());
        loan.setBorrowingClubId(otherClub.getId());
        loan.setStatus(Loan.LoanStatus.ACTIVE);
        loan.setSeason(1);
        loan.setStartWeek(1);
        loan.setEndWeek(20);
        loans.save(loan);

        assertFalse(squadRegistration.onTheBooks(club.getId()).contains(loanee.getId()),
                "a player we have loaned away is still on our books, so both clubs could field him");
    }
}
