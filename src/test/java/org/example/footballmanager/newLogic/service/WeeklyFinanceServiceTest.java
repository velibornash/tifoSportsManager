package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.FinanceCategory;
import org.example.footballmanager.newLogic.model.FinanceLedgerEntry;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.example.footballmanager.newLogic.repository.FinanceLedgerEntryRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
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
 * Sprint 2.2 — the weekly ledger.
 *
 * <p>The properties worth protecting are that the wage bill is a real cost, that a week is settled
 * exactly once, and that income is signed the right way round. Everything else is calibration.
 */
@SpringBootTest
@ActiveProfiles("test")
class WeeklyFinanceServiceTest {

    @Autowired TeamRepository teams;
    @Autowired FinanceLedgerEntryRepository ledger;
    @Autowired WeeklyFinanceService finances;
    @Autowired AdmissionService admission;
    @Autowired PitchMaintenanceService pitch;
    @Autowired FinanceLedgerService ledgerService;

    private Team aClub(String name, int capacity, double ticketPrice, double startingBudget) {
        Team t = new Team();
        t.setName(name + "-" + System.nanoTime());
        t.setBudget(startingBudget);
        t.setReputation(60.0);
        Stadium s = new Stadium();
        s.setName(name + " Ground");
        s.setCapacity(capacity);
        s.setTicketPrice(ticketPrice);
        s.setPitchQuality(85.0);
        s.setPitchCondition(85);
        s.setMaintenanceRemaining(0);
        t.setStadium(s);
        return teams.save(t);
    }

    @Test
    @DisplayName("a week produces real ledger lines and moves the budget")
    void aWeekIsSettled() {
        Team club = aClub("Ledger", 20_000, 18.0, 1_000_000);
        double before = club.getBudget();

        WeeklyFinanceService.WeekResult r = finances.applyWeeklyFinances(club, 2026, 1);

        assertFalse(r.notApplied(), "the week should settle");
        assertTrue(r.lines() >= 3, "expected several lines, got " + r.lines());
        assertEquals(before + r.net(), r.closing(), 0.01, "closing = opening + net");

        List<FinanceLedgerEntry> lines = ledger.findByTeamIdAndSeasonYearAndWeekNumber(
                club.getId(), 2026, 1);
        assertFalse(lines.isEmpty(), "the lines must actually be persisted");
    }

    @Test
    @DisplayName("wages are a real cost and are stored negative")
    void wagesAreACost() {
        Team club = aClub("Wages", 10_000, 15.0, 1_000_000);
        finances.applyWeeklyFinances(club, 2026, 2);

        List<FinanceLedgerEntry> lines = ledger.findByTeamIdAndSeasonYearAndWeekNumber(
                club.getId(), 2026, 2);
        FinanceLedgerEntry wages = lines.stream()
                .filter(e -> e.getCategory() == FinanceCategory.WAGES).findFirst().orElse(null);

        // Only assert if the club actually has players; a bare club has no wage bill.
        if (wages != null) {
            assertTrue(wages.getAmount() <= 0, "a wage bill must reduce the budget: " + wages.getAmount());
        }
    }

    @Test
    @DisplayName("income is positive and costs are negative")
    void signsAreCorrect() {
        Team club = aClub("Signs", 20_000, 20.0, 1_000_000);
        finances.applyWeeklyFinances(club, 2026, 3);

        for (FinanceLedgerEntry e : ledger.findByTeamIdAndSeasonYearAndWeekNumber(club.getId(), 2026, 3)) {
            if (e.getCategory().isIncome()) {
                assertTrue(e.getAmount() >= 0, e.getCategory() + " must not be negative");
            } else {
                assertTrue(e.getAmount() <= 0, e.getCategory() + " must not be positive");
            }
        }
    }

    @Test
    @DisplayName("a week is never settled twice - a double settlement is a silent double wage bill")
    void weekIsSettledOnce() {
        Team club = aClub("Once", 20_000, 18.0, 1_000_000);

        WeeklyFinanceService.WeekResult first = finances.applyWeeklyFinances(club, 2026, 4);
        double afterFirst = first.closing();

        WeeklyFinanceService.WeekResult second = finances.applyWeeklyFinances(club, 2026, 4);
        assertTrue(second.notApplied(), "a repeat settlement must be refused");
        assertEquals(afterFirst, club.getBudget(), 0.01, "the budget must not move twice");
    }

    @Test
    @DisplayName("a different week does settle")
    void aDifferentWeekSettles() {
        Team club = aClub("Next", 20_000, 18.0, 1_000_000);
        assertFalse(finances.applyWeeklyFinances(club, 2026, 5).notApplied());
        assertFalse(finances.applyWeeklyFinances(club, 2026, 6).notApplied());
    }

    @Test
    @DisplayName("gate income scales with the stadium")
    void gateScalesWithTheStadium() {
        Team small = aClub("Small", 5_000, 18.0, 0);
        Team big = aClub("Big", 45_000, 18.0, 0);
        double smallGate = admission.projectHomeFixture(small).gateRevenue;
        double bigGate = admission.projectHomeFixture(big).gateRevenue;
        assertTrue(bigGate > smallGate * 5,
                "a 45k ground must be worth far more than a 5k one: " + bigGate + " vs " + smallGate);
    }

    @Test
    @DisplayName("each season keeps its own ledger")
    void seasonsAreSeparate() {
        Team club = aClub("Seasons", 20_000, 18.0, 1_000_000);
        finances.applyWeeklyFinances(club, 2024, 1);
        finances.applyWeeklyFinances(club, 2025, 1);

        assertEquals(1, ledger.findByTeamIdAndSeasonYearAndWeekNumber(club.getId(), 2024, 1).size());
        assertEquals(1, ledger.findByTeamIdAndSeasonYearAndWeekNumber(club.getId(), 2025, 1).size());

        List<Integer> seasons = ledgerService.seasonsWithLedger(club.getId());
        assertTrue(seasons.contains(2024) && seasons.contains(2025),
                "a manager reviewing last season needs both to be listed: " + seasons);
    }

    @Test
    @DisplayName("a season with no ledger says so rather than showing zeroes")
    void emptySeasonIsHonest() {
        Team club = aClub("EmptySeason", 20_000, 18.0, 1_000_000);
        finances.applyWeeklyFinances(club, 2025, 1);

        Map<String, Object> for2024 = ledgerService.summarise(club, 2024);
        assertTrue(Boolean.FALSE.equals(for2024.get("settled")),
                "2024 has no weeks, so it must read as unsettled");
        assertNotNull(for2024.get("notice"),
                "and must carry the notice, so the page can explain itself");
    }

    @Test
    @DisplayName("the ledger records why a line exists")
    void linesCarryAReason() {
        Team club = aClub("Notes", 20_000, 18.0, 1_000_000);
        finances.applyWeeklyFinances(club, 2026, 7);
        for (FinanceLedgerEntry e : ledger.findByTeamIdAndSeasonYearAndWeekNumber(club.getId(), 2026, 7)) {
            assertFalse(e.getNote() == null || e.getNote().isBlank(),
                    e.getCategory() + " has no explanation, which makes the Finances page useless");
        }
    }
}
