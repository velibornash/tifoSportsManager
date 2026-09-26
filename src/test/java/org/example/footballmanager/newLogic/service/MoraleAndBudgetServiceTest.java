package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 2.5 and 2.6 — what a club can buy, and why a player cares.
 *
 * <p>{@code Player.form} used to be a creation-time constant, so nothing the manager did could
 * change how a player performed. The transfer budget used to be a formula in the browser. Both are
 * real now, and these tests pin the direction each one moves.
 */
@SpringBootTest
@ActiveProfiles("test")
class MoraleAndBudgetServiceTest {

    @Autowired TeamRepository teams;
    @Autowired PlayerRepository players;
    @Autowired MoraleService morale;
    @Autowired TransferBudgetService budgets;
    @Autowired WeeklyFinanceService finances;
    @Autowired org.example.footballmanager.newLogic.repository.GameClockRepository clocks;

    /**
     * The season the ledger is keyed by, taken from the clock rather than a literal year.
     *
     * <p>The reader used to guess the wall-clock year while the writers used the game season, so the
     * two never met and every club read as having no income. Settling the season the clock is in is
     * the invariant that fix restored.
     */
    private int gameSeason() {
        return clocks.findAll().stream().findFirst().orElseThrow().getCurrentSeason();
    }

    private Team aClub(String name, double budget) {
        Team t = new Team();
        t.setName(name + "-" + System.nanoTime());
        t.setBudget(budget);
        t.setReputation(60.0);
        Stadium s = new Stadium();
        s.setName(name + " Ground");
        s.setCapacity(30_000);
        s.setTicketPrice(20.0);
        s.setPitchQuality(85.0);
        s.setPitchCondition(85);
        s.setMaintenanceRemaining(0);
        t.setStadium(s);
        return teams.save(t);
    }

    private Player aPlayer(Team team, String name, double wage, double value) {
        Player p = new Player();
        p.setName(name);
        p.setTeam(team);
        p.setEarnings(wage);
        p.setPlayerValue(value);
        p.setMorale(60.0);
        p.setForm(6.0);
        return players.save(p);
    }

    // ---------- morale ----------

    @Test
    @DisplayName("a full match lifts a player's morale and form")
    void aGoodMatchLifts() {
        Player p = aPlayer(aClub("Good", 1_000_000), "Star", 10_000, 2_000_000);
        double moraleBefore = p.getMorale();
        double formBefore = p.getForm();

        morale.applyMatch(p, 90, 2, 1, 8.5, true, false);

        assertTrue(p.getMorale() > moraleBefore, "a great match must lift morale");
        assertTrue(p.getForm() > formBefore, "and form");
    }

    @Test
    @DisplayName("being left on the bench is the biggest demotivator there is")
    void theBenchHurts() {
        Player played = aPlayer(aClub("Played", 1_000_000), "Played", 10_000, 1_000_000);
        Player benched = aPlayer(aClub("Benched", 1_000_000), "Benched", 10_000, 1_000_000);

        morale.applyMatch(played, 90, 0, 0, 6.0, false, true);
        morale.applyMatch(benched, 0, 0, 0, 0, false, true);

        assertTrue(benched.getMorale() < played.getMorale(),
                "a player who did not play must be less happy than one who did: "
                        + benched.getMorale() + " vs " + played.getMorale());
    }

    @Test
    @DisplayName("being paid far below your worth makes you unhappy")
    void underpaidPlayersAreUnhappy() {
        Player underpaid = aPlayer(aClub("Cheap", 1_000_000), "Cheap", 500, 8_000_000);
        morale.applyStanding(underpaid, true, false);
        assertTrue(underpaid.getMorale() < 60,
                "a player on EUR 500 a week against EUR 8m of ability should not be content: "
                        + underpaid.getMorale());
    }

    @Test
    @DisplayName("being listed for transfer hurts")
    void beingListedHurts() {
        Player p = aPlayer(aClub("Listed", 1_000_000), "Listed", 10_000, 1_000_000);
        double before = p.getMorale();
        morale.applyStanding(p, true, true);
        assertTrue(p.getMorale() < before);
    }

    @Test
    @DisplayName("form is a swing, not a permanent trait - it decays at the end of a season")
    void formDecaysAtEndOfSeason() {
        Player p = aPlayer(aClub("Swing", 1_000_000), "Swing", 10_000, 1_000_000);
        morale.applyMatch(p, 90, 3, 2, 9.0, true, false);
        assertTrue(p.getForm() > 7, "he should be flying after that: " + p.getForm());

        morale.endOfSeason(p);
        assertEquals(6.0, p.getForm(), 0.01,
                "without a decay a great run of form is permanent and every player ends at 10");
    }

    @Test
    @DisplayName("morale is a gentle effect, not a feedback loop")
    void moraleEffectIsNarrow() {
        Player ecstatic = aPlayer(aClub("Ecstatic", 1e6), "Ecstatic", 10_000, 1_000_000);
        ecstatic.setMorale(100);
        Player devastated = aPlayer(aClub("Devastated", 1e6), "Devastated", 10_000, 1_000_000);
        devastated.setMorale(0);

        double best = morale.moraleModifier(ecstatic);
        double worst = morale.moraleModifier(devastated);
        assertTrue(best > 1.0 && worst < 1.0, "morale must actually move performance");
        assertTrue(best - worst < 0.25,
                "the band is deliberately narrow: a wide one turns a bad run into a loop where a "
                        + "player who misses stops scoring, stops being picked, and misses more. "
                        + "Band was " + (best - worst));
    }

    // ---------- transfer budget ----------

    @Test
    @DisplayName("a club with no settled income has been granted nothing - and says so")
    void noIncomeNoBudget() {
        Team club = aClub("Unsettled", 5_000_000);
        TransferBudgetService.Budget b = budgets.budgetFor(club.getId());
        assertTrue(b.notGranted(), "nothing has been granted yet");
        assertTrue(b.reason().toLowerCase().contains("no settled income"),
                "and the reason must say why: " + b.reason());
    }

    @Test
    @DisplayName("a club with income and manageable wages gets a real budget")
    void settledClubGetsABudget() {
        Team club = aClub("Settled", 5_000_000);
        for (int i = 0; i < 6; i++) finances.applyWeeklyFinances(club, gameSeason(), 200 + i);

        TransferBudgetService.Budget b = budgets.budgetFor(club.getId());
        assertTrue(b.granted() > 0, "a club with income and no players must be granted something");
        assertTrue(b.granted() <= Math.max(0, club.getBudget()) * 0.5 + 0.01,
                "the budget can never exceed half the cash on hand: "
                        + b.granted() + " vs cash " + club.getBudget());
    }

    @Test
    @DisplayName("a club whose wages exceed its income is told the wage bill is the problem")
    void wageBillIsTheConstraint() {
        Team club = aClub("Broke", 1_000_000);
        // A wage bill far beyond anything the ground can generate. It has to be genuinely
        // absurd: once gate receipts were counted as income rather than dropped for having no
        // season, a 30,000-seat ground earns enough that 250k a week no longer bankrupts anyone.
        aPlayer(club, "Overpaid", 2_000_000, 100_000_000);
        for (int i = 0; i < 6; i++) finances.applyWeeklyFinances(club, gameSeason(), 300 + i);

        TransferBudgetService.Budget b = budgets.budgetFor(club.getId());
        assertTrue(b.notGranted(), "a club spending more than it earns gets no transfer budget");
        assertTrue(b.reason().toLowerCase().contains("wage"),
                "and is told it is the wages: " + b.reason());
    }

    @Test
    @DisplayName("affordability says WHICH limit stopped a signing, not just no")
    void affordabilityExplainsItself() {
        Team club = aClub("Signing", 10_000_000);
        for (int i = 0; i < 6; i++) finances.applyWeeklyFinances(club, gameSeason(), 400 + i);
        Player target = aPlayer(club, "Target", 1_000, 50_000_000);

        TransferBudgetService.Affordability a = budgets.canAfford(club.getId(), target.getId());
        assertFalse(a.affordable(), "EUR 12.5m for a EUR 1,000-a-week player is not affordable");
        assertNotNull(a.reason());
        assertTrue(a.reason().contains("short") || a.reason().contains("wage"),
                "the reason must name the constraint: " + a.reason());
    }

    @Test
    @DisplayName("an unknown player or club is handled, not crashed on")
    void unknownIdsAreHandled() {
        assertNotNull(budgets.budgetFor(999_999L));
        assertTrue(budgets.budgetFor(999_999L).notGranted());
        assertFalse(budgets.canAfford(999_999L, 999_999L).affordable());
    }
}
