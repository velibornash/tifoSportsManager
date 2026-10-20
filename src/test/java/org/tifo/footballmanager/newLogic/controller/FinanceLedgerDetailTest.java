package org.tifo.footballmanager.newLogic.controller;

import org.tifo.footballmanager.newLogic.model.FinanceCategory;
import org.tifo.footballmanager.newLogic.model.Player;
import org.tifo.footballmanager.newLogic.model.Stadium;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.repository.FinanceLedgerEntryRepository;
import org.tifo.footballmanager.newLogic.repository.PlayerRepository;
import org.tifo.footballmanager.newLogic.repository.TeamRepository;
import org.tifo.footballmanager.newLogic.service.ClubEconomics;
import org.tifo.footballmanager.newLogic.service.FinanceLedgerService;
import org.tifo.footballmanager.newLogic.service.WeeklyFinanceService;
import org.tifo.footballmanager.newLogic.model.SquadRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Finances page can show what arrived and what it cost (owner, 2026-10-11).
 *
 * <p>The owner asked: *"stavke pojedinacne na Finance mora da se vide da se zna tacno sta i koliko je
 * stigli i od koga i sta su tacno troskovi.. treba da se mogu videti tenantna nedelja (default), a da
 * moze da se izabere mesec ili season"*.
 *
 * <p>Two facts about the code as it stood. {@code GET /api/teams/{id}/finances/ledger} returned
 * everything asked for and **nothing in the browser had ever called it** — a repo-wide grep for
 * {@code finances/ledger} across {@code src/main/resources} returned zero hits. And the summary's
 * {@code byCategory} breakdown was returned and never rendered either.
 *
 * <p>This pins the shape the page now reads: a category, a signed amount and a note that says what
 * caused it, for every settled line, plus the week the game is in so the page can default to *this*
 * week rather than to a number that is right once a season.
 */
@SpringBootTest
@ActiveProfiles("test")
class FinanceLedgerDetailTest {

    @Autowired TeamRepository teams;
    @Autowired PlayerRepository players;
    @Autowired FinanceLedgerEntryRepository ledger;
    @Autowired WeeklyFinanceService finances;
    @Autowired FinanceLedgerService ledgerService;
    @Autowired FinanceController controller;

    @Test
    @DisplayName("every settled line carries a category, a signed amount and a reason")
    void everyLineExplainsItself() {
        Team club = aClub("Ledger detail", 30_000, 20.0, 75.0);
        for (int i = 0; i < 20; i++) {
            aPlayer(club, 2_000_000 + i * 250_000);
        }

        finances.applyWeeklyFinances(club, 1, 1);

        List<Map<String, Object>> rows = financeControllerEntries(club, 1);
        assertNotNull(rows, "the ledger endpoint must return rows");
        assertFalse(rows.isEmpty(), "a settled week produced no ledger lines");

        for (Map<String, Object> row : rows) {
            assertNotNull(row.get("category"), "a line with no category cannot be labelled");
            assertNotNull(row.get("label"), row.get("category") + " has no human label");
            assertNotNull(row.get("amount"), row.get("label") + " has no amount");
            assertNotNull(row.get("note"), row.get("label") + " has no note, so a manager cannot tell "
                    + "what caused it — which is the whole of the request");
            assertFalse(String.valueOf(row.get("note")).isBlank(),
                    row.get("label") + " has a blank note");
            assertNotNull(row.get("income"), row.get("label") + " does not say which way it flows");

            FinanceCategory category = FinanceCategory.valueOf(String.valueOf(row.get("category")));
            double amount = ((Number) row.get("amount")).doubleValue();
            assertEquals(category.isIncome() == amount > 0, Math.abs(amount) > 0.005,
                    category + " is " + (category.isIncome() ? "income" : "a cost") + " but came out "
                            + amount + ". A manager reading this cannot tell money in from money out");
        }
    }

    @Test
    @DisplayName("the wage line says what it cost and the broadcast line says why")
    void theBigLinesExplainThemselves() {
        Team club = aClub("Ledger notes", 30_000, 20.0, 75.0);
        for (int i = 0; i < 20; i++) {
            aPlayer(club, 3_000_000);
        }
        finances.applyWeeklyFinances(club, 1, 1);

        String wages = noteFor(club, FinanceCategory.WAGES);
        String broadcast = noteFor(club, FinanceCategory.BROADCAST);

        assertTrue(wages.contains("players under contract"),
                "the wage line should say what it paid for. It said: " + wages);
        assertTrue(wages.contains("most expensive"),
                "the wage line should name the largest single wage in it, because that is the line a "
                        + "manager wants to renegotiate. It said: " + wages);
        assertTrue(broadcast.contains("wage bill"),
                "the broadcast line should show its working, because the owner asked to know where the "
                        + "money came from. It said: " + broadcast);
    }

    @Test
    @DisplayName("the summary tells the page which week the game is in")
    void theSummaryCarriesTheCurrentWeek() {
        Team club = aClub("Ledger week", 15_000, 15.0, 60.0);
        aPlayer(club, 1_000_000);
        finances.applyWeeklyFinances(club, 1, 1);

        Map<String, Object> summary = ledgerService.summarise(club, 1);

        assertTrue(summary.containsKey("currentWeek"),
                "the page defaults to this week, and without the game week it has to guess a number "
                        + "that is right for one week of the season and wrong for the other eleven");
        assertTrue(Number.class.isAssignableFrom(summary.get("currentWeek").getClass()));
        int week = ((Number) summary.get("currentWeek")).intValue();
        assertTrue(week >= 1 && week <= 12, "the game week was " + week + ", which is not 1-12");
        assertNotNull(summary.get("byCategory"),
                "the per-category breakdown is returned and was never rendered. It is the fastest read "
                        + "on where a club's money comes from");
    }

    // --- helpers ---

    /**
     * The ledger rows exactly as the endpoint shapes them.
     *
     * <p>Built through the controller because the shape <em>is</em> the contract: the page reads
     * {@code category}, {@code label}, {@code amount}, {@code note} and {@code income}, and a test that
     * read the entities instead would pass while the page stayed blank.
     */
    private List<Map<String, Object>> financeControllerEntries(Team club, int season) {
        return controller.entries(club.getId(), season).getBody();
    }

    private String noteFor(Team club, FinanceCategory category) {
        return ledger.findByTeamIdAndSeasonYearAndWeekNumber(club.getId(), 1, 1).stream()
                .filter(e -> e.getCategory() == category)
                .map(e -> String.valueOf(e.getNote()))
                .findFirst()
                .orElse("");
    }

    private Team aClub(String name, int capacity, double ticketPrice, double reputation) {
        Team club = new Team();
        club.setName(name + "-" + System.nanoTime());
        club.setCompetition(savedLeague());
        club.setBudget(1_000_000.0);
        club.setReputation(reputation);
        Stadium s = new Stadium();
        s.setName(name + " Ground");
        s.setCapacity(capacity);
        s.setTicketPrice(ticketPrice);
        s.setPitchQuality(85.0);
        s.setPitchCondition(85);
        s.setMaintenanceRemaining(0);
        club.setStadium(s);
        return teams.save(club);
    }

    @Autowired
    org.tifo.footballmanager.newLogic.repository.CompetitionRepository competitions;

    private org.tifo.footballmanager.newLogic.model.Competition savedLeague() {
        org.tifo.footballmanager.newLogic.model.Competition league =
                new org.tifo.footballmanager.newLogic.model.Competition();
        league.setName("Ledger league " + System.nanoTime());
        league.setType(org.tifo.footballmanager.newLogic.model.CompetitionType.LEAGUE);
        league.setTier(1);
        league.setReputationWeight(20);
        return competitions.save(league);
    }

    private Player aPlayer(Team club, double value) {
        Player player = new Player();
        player.setName("Ledger player " + System.nanoTime());
        player.setTeam(club);
        player.setAge(25);
        player.setRating(72);
        player.setPlayerValue(value);
        player.setEarnings(ClubEconomics.weeklyWage(value, SquadRole.STARTER, 25));
        return players.save(player);
    }
}
