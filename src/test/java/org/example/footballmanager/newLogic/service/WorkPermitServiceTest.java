package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.WorkPermit;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.GameClockRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.repository.WorkPermitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Work permits and the non-EU quota (Sprint 3.5).
 *
 * <p>The constraint that shapes a Serbian transfer window: four non-EU players in the top flight and
 * fewer below, each needing a permit. The interesting decision is not "can I afford him" but "can I
 * register him" — so a club at its quota has to sell before it can buy, which is a real and slightly
 * awkward thing to plan a season around.
 */
@SpringBootTest
@ActiveProfiles("test")
class WorkPermitServiceTest {

    @Autowired WorkPermitService permits;
    @Autowired WorkPermitRepository permitRepo;
    @Autowired PlayerRepository players;
    @Autowired TeamRepository teams;
    @Autowired CompetitionRepository competitions;
    @Autowired org.example.footballmanager.newLogic.repository.CountryRepository countries;
    @Autowired GameClockRepository clocks;

    private Team club;
    private Competition topFlight;

    @BeforeEach
    void setUp() {
        club = club("Quota club", 75.0);
        topFlight = competition("Top flight", 1, 4);
    }

    /**
     * The host country matters as much as the quota: a Serbian player in a Serbian league is
     * domestic, and without a country on the competition there is no way to know that.
     */
    private org.example.footballmanager.newLogic.model.Country serbia() {
        return countries.findByIsoCode("SRB").orElseGet(() -> {
            var c = new org.example.footballmanager.newLogic.model.Country();
            c.setName("Serbia");
            c.setIsoCode("SRB");
            c.setCurrencyCode("RSD");
            c.setReputation(60);
            return countries.save(c);
        });
    }

    private Team club(String name, double reputation) {
        Team t = new Team();
        t.setName(name + "-" + System.nanoTime());
        t.setBudget(8_000_000.0);
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

    private Competition competition(String name, int tier, Integer limit) {
        Competition c = new Competition();
        c.setName(name + "-" + System.nanoTime());
        c.setType(CompetitionType.LEAGUE);
        c.setScope(CompetitionScope.NATIONAL);
        c.setTier(tier);
        c.setForeignPlayerLimit(limit);
        c.setCountry(serbia());
        return competitions.save(c);
    }

    private Player aPlayer(Team at, String name, String nationality, double value) {
        Player p = new Player();
        p.setName(name + "-" + System.nanoTime());
        p.setTeam(at);
        p.setNationality(nationality);
        p.setAge(24);
        p.setPlayerValue(value);
        p.setEarnings(4_000);
        p.setMorale(60.0);
        p.setForm(6.0);
        return players.save(p);
    }

    @Test
    @DisplayName("only non-EU players count against the quota")
    void euAndDomesticAreFree() {
        assertFalse(permits.countsAsForeign(aPlayer(club, "Serbian", "SRB", 1_000_000), topFlight),
                "a Serbian at a Serbian club is domestic - Serbia is not in the EU, and a check "
                        + "written as 'is he non-EU' would count every homegrown player as a foreigner");
        assertFalse(permits.countsAsForeign(aPlayer(club, "Spaniard", "ESP", 1_000_000), topFlight),
                "an EU player does not count against a non-EU quota");
        assertTrue(permits.countsAsForeign(aPlayer(club, "Brazilian", "BRA", 1_000_000), topFlight));
        assertFalse(permits.countsAsForeign(aPlayer(club, "Unrecorded", null, 1_000_000), topFlight),
                "no nationality on record is treated as domestic, so old data is not blocked");
    }

    @Test
    @DisplayName("a domestic signing needs no permit and is never blocked")
    void domesticPlayersAreUnaffected() {
        Player domestic = aPlayer(club, "Local", "SRB", 500_000);
        assertTrue(permits.canRegister(club.getId(), domestic.getId(), topFlight).allowed());

        WorkPermit permit = permits.applyFor(club.getId(), domestic.getId(), topFlight);
        assertTrue(permit.granted());
    }

    @Test
    @DisplayName("the quota fills up, and then no foreign player can be signed at any price")
    void theQuotaIsAFirmLimit() {
        assertEquals(4, permits.quotaFor(topFlight));
        assertTrue(permits.hasRoomForAnotherForeignPlayer(club.getId(), topFlight, season()));

        for (int i = 0; i < 4; i++) {
            Player foreigner = aPlayer(club, "Foreigner " + i, "BRA", 5_000_000);
            WorkPermit permit = permits.applyFor(club.getId(), foreigner.getId(), topFlight);
            assertTrue(permit.granted(), "permit " + i + " should be granted: " + permit.getReason());
        }

        assertFalse(permits.hasRoomForAnotherForeignPlayer(club.getId(), topFlight, season()));
        Player fifth = aPlayer(club, "One too many", "ARG", 20_000_000);
        WorkPermitService.RegistrationCheck check =
                permits.canRegister(club.getId(), fifth.getId(), topFlight);
        assertFalse(check.allowed(), "a fifth foreign player cannot be registered");
        assertEquals("QUOTA_FULL", check.code());
        assertTrue(check.reason().toLowerCase().contains("sell"),
                "and the manager is told selling is the only way: " + check.reason());
    }

    @Test
    @DisplayName("a lower league offers fewer places")
    void theQuotaFallsWithTheTier() {
        assertEquals(4, permits.quotaFor(competition("Tier 1", 1, null)));
        assertEquals(3, permits.quotaFor(competition("Tier 2", 2, null)));
        assertEquals(2, permits.quotaFor(competition("Tier 3", 3, null)));
        assertEquals(1, permits.quotaFor(competition("Tier 4", 4, null)));
        assertEquals(0, permits.quotaFor(competition("Tier 5", 5, null)),
                "the bottom of the pyramid takes no foreign players at all");
    }

    @Test
    @DisplayName("a competition may set its own quota, overriding the tier default")
    void aCompetitionCanOverrideItsQuota() {
        Competition generous = competition("Generous", 5, 7);
        assertEquals(7, permits.quotaFor(generous),
                "the rule belongs to the competition, not to a hardcoded tier table");
    }

    @Test
    @DisplayName("a small club is refused a permit, and told its standing is the problem")
    void standingMattersBelowTheTopFlight() {
        Team small = club("Small club", 35.0);
        Competition third = competition("Third tier", 3, 4);
        Player prospect = aPlayer(small, "Prospect", "NGA", 1_000_000);

        WorkPermitService.RegistrationCheck check =
                permits.canRegister(small.getId(), prospect.getId(), third);
        assertFalse(check.allowed());
        assertEquals("PERMIT_REFUSED", check.code());
        assertTrue(check.reason().toLowerCase().contains("standing"),
                "the reason names the cause, not just the refusal: " + check.reason());
    }

    @Test
    @DisplayName("buying from a weaker league helps: a big club can sign a modest player abroad")
    void pedigreeDecidesThePaperwork() {
        // Below the top two tiers, a club under 70 reputation needs a player worth 8m or more to
        // justify the permit. This is the strategic axis the backlog asked for: a club that cannot
        // outspend its quota rivals can still out scout them.
        Team midTable = club("Mid table", 65.0);
        Competition third = competition("Third tier again", 3, 4);

        Player nobodyWants = aPlayer(midTable, "Unknown", "GHA", 1_000_000);
        assertFalse(permits.canRegister(midTable.getId(), nobodyWants.getId(), third).allowed(),
                "a million-euro player does not justify a permit for a mid-table club abroad");

        Player quality = aPlayer(midTable, "Quality", "GHA", 12_000_000);
        assertTrue(permits.canRegister(midTable.getId(), quality.getId(), third).allowed(),
                "twelve million does, which is why scouting where the value is beats spending");
    }

    @Test
    @DisplayName("a club with no league cannot be handed foreign places")
    void anUnplacedClubHasNoQuota() {
        Player foreigner = aPlayer(club, "Nowhere to play", "BRA", 3_000_000);
        assertFalse(permits.canRegister(club.getId(), foreigner.getId(), null).allowed(),
                "no competition means no registration places at all");
    }

    private Integer season() {
        return clocks.findAll().stream().findFirst().orElseThrow().getCurrentSeason();
    }
}
