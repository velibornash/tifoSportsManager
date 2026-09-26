package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 2.4 — the board.
 *
 * <p>A manager game needs a manager: without a board there is no consequence for a bad season and
 * no tension in a transfer window. The properties worth protecting are that trust moves in the
 * right direction, and that an unplayed season is not treated as an insolvent one.
 */
@SpringBootTest
@ActiveProfiles("test")
class BoardExpectationServiceTest {

    @Autowired TeamRepository teams;
    @Autowired PlayerRepository players;
    @Autowired CompetitionEntryRepository entries;
    @Autowired FinanceLedgerService ledgerService;
    @Autowired BoardExpectationService board;
    @Autowired WeeklyFinanceService finances;

    private Team aClub(String name, double budget) {
        Team t = new Team();
        t.setName(name + "-" + System.nanoTime());
        t.setBudget(budget);
        t.setReputation(60.0);
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

    private Player aPlayer(Team team, String name, double earnings, double value) {
        Player p = new Player();
        p.setName(name);
        p.setTeam(team);
        p.setEarnings(earnings);
        p.setPlayerValue(value);
        return players.save(p);
    }

    @Test
    @DisplayName("a club is always assessable")
    void alwaysAssessable() {
        Team club = aClub("Board", 1_000_000);
        BoardExpectationService.BoardMood mood = board.evaluate(club, null);
        assertNotNull(mood);
        assertTrue(mood.trust() >= 0 && mood.trust() <= 100, "trust must be 0-100");
        assertFalse(mood.headline().isBlank(), "the board must always have something to say");
    }

    @Test
    @DisplayName("an unplayed season is unknown, not insolvent")
    void unplayedSeasonIsNotInsolvent() {
        Team club = aClub("Unplayed", 1_000_000);
        BoardExpectationService.BoardMood mood = board.evaluate(club, null);
        assertTrue(mood.ffpRatio() == null,
                "with no settled income there is no ratio, and inventing one would be a lie");
        assertTrue(mood.health() == BoardExpectationService.FinancialHealth.UNKNOWN,
                "health must be UNKNOWN, not CRITICAL: " + mood.health());
    }

    @Test
    @DisplayName("an overdrawn club is a concern the board can see")
    void overdrawnIsAConcern() {
        Team club = aClub("Overdrawn", -250_000);
        BoardExpectationService.BoardMood mood = board.evaluate(club, null);
        assertTrue(mood.concerns().stream().anyMatch(c -> c.toLowerCase().contains("overdrawn")),
                "an overdrawn club must raise a concern: " + mood.concerns());
    }

    @Test
    @DisplayName("players paid far below their value are raised as a concern")
    void underpaidPlayersAreAConcern() {
        Team club = aClub("Underpaid", 2_000_000);
        aPlayer(club, "Unhappy", 100, 5_000_000);
        BoardExpectationService.BoardMood mood = board.evaluate(club, null);
        assertTrue(mood.concerns().stream().anyMatch(c -> c.toLowerCase().contains("unhappy")
                        || c.toLowerCase().contains("below what they are worth")),
                "underpaid players must be visible to the board: " + mood.concerns());
    }

    @Test
    @DisplayName("a reasonably paid squad raises no pay complaint")
    void fairPayRaisesNoComplaint() {
        Team club = aClub("FairPay", 2_000_000);
        aPlayer(club, "Content", 40_000, 1_500_000);
        BoardExpectationService.BoardMood mood = board.evaluate(club, null);
        assertFalse(mood.concerns().stream().anyMatch(c -> c.toLowerCase().contains("below what they are worth")),
                "a fairly paid squad must not be complained about: " + mood.concerns());
    }

    @Test
    @DisplayName("settling weeks makes the FFP ratio real, not invented")
    void settlingWeeksProducesARatio() {
        Team club = aClub("Settled", 2_000_000);
        for (int i = 0; i < 5; i++) {
            finances.applyWeeklyFinances(club, 2026, 100 + i);
        }
        Map<String, Object> summary = ledgerService.summarise(club);
        assertTrue((Integer) summary.get("ledgerLines") > 0, "the ledger must have lines");

        BoardExpectationService.BoardMood mood = board.evaluate(club, null);
        assertTrue(mood.weeksSettled() > 0, "settled weeks must be detected");
    }

    @Test
    @DisplayName("a club with no ledger says so rather than showing zeros that look like poverty")
    void noLedgerSaysSo() {
        Team club = aClub("Fresh", 500_000);
        Map<String, Object> summary = ledgerService.summarise(club);
        assertTrue(Boolean.FALSE.equals(summary.get("settled")), "a fresh club is unsettled");
        assertNotNull(summary.get("notice"), "the page must be told to say so");
    }
}
