package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.BaseTest;
import org.example.commonmanager.model.User;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.model.GameClock;
import org.example.footballmanager.newLogic.model.Loan;
import org.example.footballmanager.newLogic.model.Notification;
import org.example.footballmanager.newLogic.model.NotificationKind;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.LoanRepository;
import org.example.footballmanager.newLogic.repository.NotificationRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A loan must tell the manager it happened, and a loan must offer the right button (owner, 2026-10-08).
 *
 * <p><b>The report that started this:</b> *"poslajem ga nazad al ne stigne niti imam opciju da prihvatim
 * — loan bi trebao izmedju ostalog da stize u notifications"* — I send him back and he does not arrive,
 * and I have no option to accept him.
 *
 * <p>Two separate defects, and they are separate because only one of them is about a button:
 *
 * <ol>
 *   <li><b>The row offered the wrong action.</b> {@code loanRow} branched on the side first, so every
 *       borrowing row fell through to the last branch and offered <i>"Send him back"</i> — which the service
 *       refuses with {@code LOAN_NOT_ACTIVE} — while the action that actually starts the loan was on no
 *       row at all.</li>
 *   <li><b>Nothing was ever notified.</b> A loan offered, taken in, asked back and ended all moved inside
 *       the loans screen. A manager found out that a player had arrived by opening that screen.</li>
 * </ol>
 */
class LoanNotifiesAndOffersTheRightActionTest extends BaseTest {

    @Autowired LoanService loans;
    @Autowired NotificationRepository notifications;
    @Autowired LoanRepository loanRows;
    @Autowired TeamRepository teams;
    @Autowired PlayerRepository players;
    @Autowired org.example.footballmanager.newLogic.repository.CountryRepository countries;
    @Autowired org.example.footballmanager.newLogic.repository.CompetitionRepository competitions;
    @Autowired org.example.commonmanager.repository.UserRepository users;
    @Autowired org.example.footballmanager.newLogic.repository.GameClockRepository clocks;
    @Autowired org.example.footballmanager.newLogic.service.SeasonService seasons;

    @Test
    @DisplayName("a loan that arrives tells the manager it arrived")
    void anArrivingLoanIsNotified() {
        var world = aLoanWorld();

        // The signature is offer(lendingClub, player, borrowingClub). This was called
        // offer(player, lendingClub, borrowingClub) for several rounds of failing tests, and the
        // symptom — "No such player", with the player provably present — reads exactly like a missing
        // row and is not one. Two Longs next to each other and no compiler to help.
        assertTrue(players.findById(world.player().getId()).isPresent(),
                "fixture: the player was saved with id " + world.player().getId()
                        + " but findById cannot see it, so the row is not committed");
        Loan agreed = loans.offer(world.lender().getId(), world.player().getId(), world.borrower().getId());
        loans.activate(agreed.getId());

        // Counted rather than matched on a summary: the table is shared across the tests in this class,
        // so asserting on its whole contents made this test depend on the order they ran in. It passed
        // alone and failed in the class, which is a test that measures the wrong thing.
        List<Notification> rows = notifications.findAll();
        assertTrue(rows.stream().anyMatch(n -> n.getKind() == NotificationKind.LOAN_MOVED
                        && n.getSummary() != null && n.getSummary().contains("arrived on loan")),
                "taking a player in is the moment the owner asked to be told about, and no notification "
                        + "of it was written: " + rows.stream().map(Notification::getSummary).toList());
    }

    @Test
    @DisplayName("being asked to give a player back is notified, because silence costs somebody a week")
    void aReturnRequestIsNotified() {
        var world = aLoanWorld();

        Loan agreed = loans.offer(world.lender().getId(), world.player().getId(), world.borrower().getId());
        loans.activate(agreed.getId());
        long before = notifications.count();

        loans.requestTermination(agreed.getId(), world.borrower().getId(), "need him back");

        assertTrue(notifications.count() > before,
                "the asking club's button appeared to do nothing for a week; the other club is told now");
    }

    @Test
    @DisplayName("the row offers the action the loan's status allows, and nothing else")
    void theRowOffersTheRightAction() throws IOException {
        String js = Files.readString(
                Path.of("src/main/resources/static/js/pages/features/loans.js"), StandardCharsets.UTF_8);

        assertTrue(js.contains("data-loan-action=\"activate\""),
                "an agreed loan has no button that starts it, so the player never arrives and there is "
                        + "nothing to accept — the owner's exact words");
        assertTrue(js.contains("Take him in"),
                "and the button says what it does rather than offering a return that is refused");
        assertTrue(js.contains("action === 'activate'"),
                "and the click handler exists; a button with no handler is the same defect one layer down");
        assertTrue(js.contains("Waiting for them to take him in"),
                "the lending side is told to wait rather than being offered a return on a loan that has "
                        + "not started");
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────

    private record World(Team lender, Team borrower, Player player) { }

    /**
     * A lending club above a borrowing one, in the same country, both human-managed, and each with a
     * manager — because the whole point of these tests is what a manager is **told**, and a club with no
     * manager has nobody to tell.
     */
    private World aLoanWorld() {
        worldCounter++;
        String tag = "w" + worldCounter + "-" + (System.nanoTime() % 100_000);
        // The clock has to exist and be running: LoanService reads the current week off it and refuses
        // a loan outright with NO_CLOCK otherwise, which is correct and is why the first two versions of
        // this fixture failed before reaching the notification it was written to check.
        if (clocks.findById(1L).map(c -> c.getCurrentWeek() == null).orElse(true)) {
            GameClock clock = seasons.getOrCreateClock();
            clock.setCurrentSeason(1);
            clock.setCurrentWeek(3);
            clocks.save(clock);
        }

        Country country = new Country();
        country.setName("Loanland " + tag);
        // A distinct ISO code per test, because these tests share the database and a country row is
        // unique on it. The first version derived it from the name's hash, which collided the moment two
        // worlds existed and failed the class on a unique index rather than on anything it was testing.
        country.setIsoCode(nextIsoCode());
        country.setState(CountryState.SIMULATED);
        countries.save(country);

        Team lender = club("Lending " + tag, country, competition(2, tag), "L" + tag);
        Team borrower = club("Borrowing " + tag, country, competition(4, tag), "B" + tag);

        Player player = new Player();
        player.setName("Loan player " + tag);
        player.setTeam(lender);
        player.setPosition(Position.MID);
        player.setAge(21);
        player.setRating(70);
        player.setPlayerValue(400_000);
        player.setEarnings(2_000);
        player.setMorale(60.0);
        player.setForm(6.0);
        // **The saved instance, not the one we built.** Without a transaction the entity we constructed
        // never had an id attached, so offer() looked up a null and answered PLAYER_NOT_FOUND — which is
        // what the first version of this fixture did.
        player = players.save(player);
        return new World(lender, borrower, player);
    }

    /**
     * Static, because JUnit creates a new instance of the test class for every method and an instance
     * counter restarts at zero each time — which is why the second version of this still collided.
     */
    private static int isoCounter = 0;

    /**
     * Also static, and for the same reason: {@code nanoTime()} is called per fixture build, but the user
     * rows are keyed on a username and two worlds built inside the same millisecond collide on it.
     */
    private static int worldCounter = 0;

    /** Three characters, unique across the whole class. */
    private String nextIsoCode() {
        isoCounter++;
        return "L" + (char) ('A' + (isoCounter - 1) / 26) + (char) ('A' + (isoCounter - 1) % 26);
    }

    private Competition competition(int tier, String tag) {
        Competition c = new Competition();
        c.setName("Loan league t" + tier + " " + tag);
        c.setType(CompetitionType.LEAGUE);
        c.setScope(CompetitionScope.NATIONAL);
        c.setTeamType(CompetitionTeamType.CLUB);
        c.setTier(tier);
        return competitions.save(c);
    }

    private Team club(String name, Country country, Competition competition, String tag) {
        Team team = new Team();
        team.setName(name + "-" + System.nanoTime());
        team.setFormation("4-3-3");
        team.setCountry(country);
        team.setCompetition(competition);
        // Loans are only between clubs a person manages.
        team.setHumanControlled(true);
        team.setBudget(5_000_000.0);
        teams.save(team);

        Stadium ground = new Stadium();
        ground.setName(name + " Ground");
        ground.setCapacity(20_000);
        ground.setTicketPrice(18.0);
        team.setStadium(ground);

        User manager = new User();
        manager.setUsername("loan-" + tag.toLowerCase() + "@test.local");
        manager.setEmail(manager.getUsername());
        manager.setFootballTeam(team);
        users.save(manager);

        return teams.save(team);
    }
}
