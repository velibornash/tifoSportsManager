package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.FinanceCategory;
import org.example.footballmanager.newLogic.model.FinanceLedgerEntry;
import org.example.footballmanager.newLogic.model.Sponsor;
import org.example.footballmanager.newLogic.model.StaffMember;
import org.example.footballmanager.newLogic.model.StaffRole;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.FinanceLedgerEntryRepository;
import org.example.footballmanager.newLogic.repository.SponsorRepository;
import org.example.footballmanager.newLogic.repository.StaffMemberRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 2.3 — staff and sponsors as real entities.
 *
 * <p>Neither existed before: the staff directory was a hardcoded array in the browser and coaching
 * had no simulation effect at all. The properties worth protecting are that the numbers are real and
 * paid, and that they are stable across a reseed.
 */
@SpringBootTest
@ActiveProfiles("test")
class StaffSponsorServiceTest {

    @Autowired TeamRepository teams;
    @Autowired CompetitionRepository competitions;
    @Autowired StaffMemberRepository staff;
    @Autowired SponsorRepository sponsors;
    @Autowired StaffSponsorService service;
    @Autowired FinanceLedgerEntryRepository ledger;
    @Autowired WeeklyFinanceService finances;

    private Team aClub(String name, double reputation) {
        Team t = new Team();
        t.setName(name + "-" + System.nanoTime());
        // A club is a team with a competition. National sides have none, and the weekly settlement
        // now skips those, so a fixture without one is not a club — it is a shape the game does not
        // have. PyramidBuilder sets setCompetition(league) on every club it creates, so this is
        // what a real club looks like.
        t.setCompetition(aLeague());
        t.setBudget(1_000_000.0);
        t.setReputation(reputation);
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

    @Test
    @DisplayName("a club gets real staff, and a head coach")
    void aClubGetsStaff() {
        Team club = aClub("Staffed", 70);
        service.seedClub(club, 2026);

        List<StaffMember> members = staff.findByTeamId(club.getId());
        assertFalse(members.isEmpty(), "staff must actually be created");
        assertTrue(members.stream().anyMatch(m -> m.getRole() == StaffRole.HEAD_COACH),
                "a club without a head coach is not a club");
        for (StaffMember m : members) {
            assertNotNull(m.getName());
            assertTrue(m.getWeeklyWage() != null && m.getWeeklyWage() > 0, "staff are paid");
            assertTrue(m.getContractEndSeason() != null && m.getContractEndSeason() >= 2026);
        }
    }

    @Test
    @DisplayName("staff are specialised - a scout is a good scout")
    void staffAreSpecialised() {
        Team club = aClub("Specialists", 80);
        service.seedClub(club, 2026);
        staff.findByTeamId(club.getId()).stream()
                .filter(m -> m.getRole() == StaffRole.SCOUT)
                .findFirst()
                .ifPresent(s -> assertTrue(s.getScouting() >= 10,
                        "a scout should be a competent scout, was " + s.getScouting()));
    }

    @Test
    @DisplayName("seeding is idempotent - a club does not hire a second head coach")
    void seedingIsIdempotent() {
        Team club = aClub("Once", 70);
        service.seedClub(club, 2026);
        int first = staff.findByTeamId(club.getId()).size();
        service.seedClub(club, 2026);
        service.seedClub(club, 2026);
        assertEquals(first, staff.findByTeamId(club.getId()).size(),
                "reseeding must not duplicate the staff");
    }

    @Test
    @DisplayName("a better club has better staff")
    void qualityFollowsReputation() {
        Team small = aClub("Small", 25);
        Team big = aClub("Big", 95);
        service.seedClub(small, 2026);
        service.seedClub(big, 2026);

        double smallAvg = staff.findByTeamId(small.getId()).stream()
                .mapToInt(StaffMember::overall).average().orElse(0);
        double bigAvg = staff.findByTeamId(big.getId()).stream()
                .mapToInt(StaffMember::overall).average().orElse(0);

        assertTrue(bigAvg > smallAvg,
                "a 95-reputation club must have better staff than a 25-reputation one: "
                        + bigAvg + " vs " + smallAvg);
    }

    @Test
    @DisplayName("the same club always gets the same staff - a reseed must not reshuffle the world")
    void seedingIsStable() {
        Team club = aClub("Stable", 70);
        service.seedClub(club, 2026);
        String firstHeadCoach = staff.findByTeamId(club.getId()).stream()
                .filter(m -> m.getRole() == StaffRole.HEAD_COACH).findFirst()
                .orElseThrow().getName();

        staff.deleteAll(staff.findByTeamId(club.getId()));
        service.seedClub(club, 2026);
        String secondHeadCoach = staff.findByTeamId(club.getId()).stream()
                .filter(m -> m.getRole() == StaffRole.HEAD_COACH).findFirst()
                .orElseThrow().getName();

        assertEquals(firstHeadCoach, secondHeadCoach,
                "the head coach changed on a reseed, which would invalidate a saved league and any "
                        + "relationship the manager had built with him");
    }

    @Test
    @DisplayName("a club gets real sponsors with a real term")
    void aClubGetsSponsors() {
        Team club = aClub("Sponsored", 70);
        service.seedClub(club, 2026);

        List<Sponsor> deals = sponsors.findByTeamId(club.getId());
        assertFalse(deals.isEmpty(), "sponsors must be created");
        for (Sponsor s : deals) {
            assertNotNull(s.getName());
            assertTrue(s.getAnnualValue() != null && s.getAnnualValue() > 0);
            assertTrue(s.getEndSeason() > s.getStartSeason(), "a contract has a term");
            assertTrue(s.isActive(2026), "a fresh contract should be live in its first season");
        }
    }

    @Test
    @DisplayName("an expired sponsor pays nothing")
    void expiredSponsorsPayNothing() {
        Team club = aClub("Expired", 70);
        service.seedClub(club, 2026);
        Sponsor s = sponsors.findByTeamId(club.getId()).get(0);
        s.setEndSeason(2020);
        sponsors.save(s);

        assertFalse(s.isActive(2026));
        assertEquals(0.0, s.weeklyIncome(2026), 0.001,
                "an expired contract must not pay, or losing a sponsor is free");
    }

    @Test
    @DisplayName("staff wages and sponsorship both reach the ledger")
    void staffAndSponsorshipReachTheLedger() {
        Team club = aClub("Paid", 70);
        service.seedClub(club, 2026);
        finances.applyWeeklyFinances(club, 2026, 1);

        List<FinanceLedgerEntry> lines = ledger
                .findByTeamIdAndSeasonYearAndWeekNumber(club.getId(), 2026, 1);

        FinanceLedgerEntry staffLine = lines.stream()
                .filter(e -> e.getCategory() == FinanceCategory.STAFF_WAGES).findFirst().orElse(null);
        assertNotNull(staffLine, "the backroom is a real cost and must be in the ledger");
        assertTrue(staffLine.getAmount() < 0, "staff wages are an expense");

        FinanceLedgerEntry sponsorLine = lines.stream()
                .filter(e -> e.getCategory() == FinanceCategory.SPONSORSHIP).findFirst().orElse(null);
        assertNotNull(sponsorLine, "sponsorship income must be in the ledger");
        assertTrue(sponsorLine.getAmount() > 0, "sponsorship is income");
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
