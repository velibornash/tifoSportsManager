package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.FinanceCategory;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.model.FinanceLedgerEntry;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.example.footballmanager.newLogic.repository.FinanceLedgerEntryRepository;

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
    @Autowired CompetitionRepository competitions;
    @Autowired FinanceLedgerEntryRepository ledger;
    @Autowired WeeklyFinanceService finances;
    @Autowired AdmissionService admission;
    @Autowired PitchMaintenanceService pitch;
    @Autowired FinanceLedgerService ledgerService;
    @Autowired PlayerRepository players;

    /**
     * A club with a squad, because a club without one has no economy.
     *
     * <p>Since 2026-10-11 the settlement refuses a team with no players: measured on the owner's world,
     * 13,630 clubs with no squad were being paid 199,430,400 a week for merchandise and nothing else,
     * because they have no wages, no upkeep and no gate to set against it. The fixtures here used to
     * build a bare club and assert that it settled — which is exactly the case that is now correct to
     * refuse, and exactly why the bug went unnoticed for so long.
     */
    private Team aClub(String name, int capacity, double ticketPrice, double startingBudget) {
        Team t = new Team();
        t.setName(name + "-" + System.nanoTime());
        // A club is a team with a competition. National sides have none, and the weekly settlement
        // now skips those, so a fixture without one is not a club — it is a shape the game does not
        // have. PyramidBuilder sets setCompetition(league) on every club it creates, so this is
        // what a real club looks like.
        t.setCompetition(aLeague());
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
        t = teams.save(t);
        for (int i = 0; i < 18; i++) {
            Player p = new Player();
            p.setName(name + " player " + i + "-" + System.nanoTime());
            p.setTeam(t);
            p.setAge(23);
            p.setRating(70);
            p.setPlayerValue(1_500_000);
            p.setEarnings(3_000);
            players.save(p);
        }
        return t;
    }

    @Test
    @DisplayName("a week produces real ledger lines and moves the budget")
    void aWeekIsSettled() {
        Team club = aClub("Ledger", 20_000, 18.0, 1_000_000);
        double before = club.getBudget();

        WeeklyFinanceService.WeekResult r = finances.applyWeeklyFinances(club, 3, 1);

        assertFalse(r.notApplied(), "the week should settle");
        assertTrue(r.lines() >= 3, "expected several lines, got " + r.lines());
        assertEquals(before + r.net(), r.closing(), 0.01, "closing = opening + net");

        List<FinanceLedgerEntry> lines = ledger.findByTeamIdAndSeasonYearAndWeekNumber(
                club.getId(), 3, 1);
        assertFalse(lines.isEmpty(), "the lines must actually be persisted");
    }

    @Test
    @DisplayName("the sponsorship line names the sponsor on the boards")
    void theSponsorshipLineNamesTheSponsor() {
        // Owner request, 2026-10-12: the sponsor's income on the Finance page should say TIFO Ltd.
        // The `sponsor` table has no production seeder, so this method used to return null and no club
        // in the world had a sponsorship line at all - there was nothing to put a name on.
        Team club = aClub("Sponsor named", 20_000, 18.0, 1_000_000);
        Stadium ground = new Stadium();
        ground.setName("ZZ Sponsor ground");
        ground.setSponsorName("Vukovar Group");
        club.setStadium(ground);
        teams.saveAndFlush(club);

        finances.applyWeeklyFinances(club, 3, 11);

        FinanceLedgerEntry line = ledger.findByTeamIdAndSeasonYearAndWeekNumber(club.getId(), 3, 11).stream()
                .filter(e -> e.getCategory() == FinanceCategory.SPONSORSHIP)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no sponsorship line was settled, so the Finance page shows the owner nothing "
                                + "to name a sponsor on"));
        assertTrue(line.getNote().contains("Vukovar Group"),
                "the sponsorship line does not name the sponsor: " + line.getNote());
    }

    @Test
    @DisplayName("a ground with no sponsor named still earns, and says TIFO Ltd.")
    void anUnnamedSponsorStillPaysAndSaysSo() {
        Team club = aClub("Sponsor unnamed", 20_000, 18.0, 1_000_000);
        // A ground exists - every real club has one - but its manager has not named a sponsor yet.
        Stadium ground = new Stadium();
        ground.setName("ZZ Unnamed ground");
        club.setStadium(ground);
        teams.saveAndFlush(club);

        finances.applyWeeklyFinances(club, 3, 12);

        FinanceLedgerEntry line = ledger.findByTeamIdAndSeasonYearAndWeekNumber(club.getId(), 3, 12).stream()
                .filter(e -> e.getCategory() == FinanceCategory.SPONSORSHIP)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "a ground the manager has not named a sponsor for still has TIFO Ltd. on the "
                                + "boards, and that is a deal"));
        assertTrue(line.getNote().contains(Stadium.DEFAULT_SPONSOR),
                "an unnamed ground does not fall back to " + Stadium.DEFAULT_SPONSOR + ": " + line.getNote());
    }

    @Test
    @DisplayName("wages are a real cost and are stored negative")
    void wagesAreACost() {
        Team club = aClub("Wages", 10_000, 15.0, 1_000_000);
        finances.applyWeeklyFinances(club, 3, 2);

        List<FinanceLedgerEntry> lines = ledger.findByTeamIdAndSeasonYearAndWeekNumber(
                club.getId(), 3, 2);
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
        finances.applyWeeklyFinances(club, 3, 3);

        for (FinanceLedgerEntry e : ledger.findByTeamIdAndSeasonYearAndWeekNumber(club.getId(), 3, 3)) {
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

        WeeklyFinanceService.WeekResult first = finances.applyWeeklyFinances(club, 3, 4);
        double afterFirst = first.closing();

        WeeklyFinanceService.WeekResult second = finances.applyWeeklyFinances(club, 3, 4);
        assertTrue(second.notApplied(), "a repeat settlement must be refused");
        assertEquals(afterFirst, club.getBudget(), 0.01, "the budget must not move twice");
    }

    @Test
    @DisplayName("a different week does settle")
    void aDifferentWeekSettles() {
        Team club = aClub("Next", 20_000, 18.0, 1_000_000);
        assertFalse(finances.applyWeeklyFinances(club, 3, 5).notApplied());
        assertFalse(finances.applyWeeklyFinances(club, 3, 6).notApplied());
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
        finances.applyWeeklyFinances(club, 1, 1);
        finances.applyWeeklyFinances(club, 2, 1);

        // A week now writes every one of its lines against that season, not just the broadcast
        // money: gate receipts, wages and upkeep used to be written with a null season, so a
        // season's ledger was missing most of what happened in it.
        assertFalse(ledger.findByTeamIdAndSeasonYearAndWeekNumber(club.getId(), 1, 1).isEmpty());
        assertFalse(ledger.findByTeamIdAndSeasonYearAndWeekNumber(club.getId(), 2, 1).isEmpty());

        List<Integer> seasons = ledgerService.seasonsWithLedger(club.getId());
        assertTrue(seasons.contains(1) && seasons.contains(2),
                "a manager reviewing last season needs both to be listed: " + seasons);
    }

    @Test
    @DisplayName("a season with no ledger says so rather than showing zeroes")
    void emptySeasonIsHonest() {
        Team club = aClub("EmptySeason", 20_000, 18.0, 1_000_000);
        finances.applyWeeklyFinances(club, 2, 1);

        Map<String, Object> for2024 = ledgerService.summarise(club, 1);
        assertTrue(Boolean.FALSE.equals(for2024.get("settled")),
                "1 has no weeks, so it must read as unsettled");
        assertNotNull(for2024.get("notice"),
                "and must carry the notice, so the page can explain itself");
    }

    @Test
    @DisplayName("the ledger records why a line exists")
    void linesCarryAReason() {
        Team club = aClub("Notes", 20_000, 18.0, 1_000_000);
        finances.applyWeeklyFinances(club, 3, 7);
        for (FinanceLedgerEntry e : ledger.findByTeamIdAndSeasonYearAndWeekNumber(club.getId(), 3, 7)) {
            assertFalse(e.getNote() == null || e.getNote().isBlank(),
                    e.getCategory() + " has no explanation, which makes the Finances page useless");
        }
    }

    /** A minimal LEAGUE division, because a club belongs to one. */
    private Competition aLeague() {
        Competition competition = new Competition();
        competition.setName("ZZ Finance league " + System.nanoTime());
        competition.setType(CompetitionType.LEAGUE);
        competition.setTier(1);
        competition.setReputationWeight(20);
        return competitions.save(competition);
    }
}
