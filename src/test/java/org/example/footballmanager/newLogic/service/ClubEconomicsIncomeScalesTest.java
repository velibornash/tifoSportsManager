package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.FinanceCategory;
import org.example.footballmanager.newLogic.model.FinanceLedgerEntry;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.SquadRole;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.FinanceLedgerEntryRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A club earns in proportion to what it is worth (owner, 2026-10-11).
 *
 * <p><b>The measured defect.</b> On the owner's database, season 1 week 1, OFK Omladinac:
 *
 * <pre>
 *   gate          +173 591     broadcast         +2 900
 *   merchandising   +1 739     wages         -18 478 000
 *                               ─────────────────────────────
 *   income        +176 491     total         -18 496 388
 * </pre>
 *
 * <p>A 105× deficit. Broadcast was {@code competition.reputationWeight × 145} — a pure function of the
 * division, so every club in a league earned the same and none of it moved when the club did. No club
 * in the world was solvent and a manager's spending meant nothing, because the number did not respond
 * to anything he could do.
 *
 * <p>The fix ties television money to the club's own wage bill. These tests hold that relationship,
 * because "it scales" is a claim and the claim is what would rot.
 */
@SpringBootTest
@ActiveProfiles("test")
class ClubEconomicsIncomeScalesTest {

    @Autowired TeamRepository teams;
    @Autowired CompetitionRepository competitions;
    @Autowired PlayerRepository players;
    @Autowired FinanceLedgerEntryRepository ledger;
    @Autowired WeeklyFinanceService finances;
    @Autowired PlayerContractService contracts;

    @Test
    @DisplayName("a stronger club earns more, and the reason is on the line")
    void aStrongerClubEarnsMore() {
        Team small = aClub("Small", 8_000, 14.0, 30.0, 4);
        Team large = aClub("Large", 45_000, 22.0, 88.0, 4);

        finances.applyWeeklyFinances(small, 1, 1);
        finances.applyWeeklyFinances(large, 1, 1);

        double smallIncome = income(small);
        double largeIncome = income(large);

        assertTrue(largeIncome > smallIncome * 3,
                "the stronger club earned " + Math.round(largeIncome) + " and the weaker "
                        + Math.round(smallIncome) + ". Income must scale with the club, or the club is "
                        + "irrelevant to its own finances.");
    }

    @Test
    @DisplayName("broadcast is a stated share of the club's own wage bill")
    void broadcastIsAShareOfTheWageBill() {
        Team club = aClub("Share", 30_000, 20.0, 70.0, 1);
        for (int i = 0; i < 25; i++) {
            aPlayer(club, 40_000 + i * 4_000);
        }

        finances.applyWeeklyFinances(club, 1, 1);

        FinanceLedgerEntry line = line(club, FinanceCategory.BROADCAST);
        assertNotNull(line, "there must be a broadcast line to read the note off");
        double wageBill = -amount(club, FinanceCategory.WAGES);
        double expectedShare = ClubEconomics.broadcastShareOfWageBill(club.getCompetition());
        double share = line.getAmount() / wageBill;

        assertEquals(expectedShare, share, 0.001,
                "broadcast was " + Math.round(line.getAmount()) + " against a wage bill of "
                        + Math.round(wageBill) + ", which is not the stated share");
        assertTrue(line.getNote().contains("wage bill"),
                "the note is how a manager finds out why the number is what it is. It said: "
                        + line.getNote());
    }

    @Test
    @DisplayName("doubling a squad's wages doubles what it earns from television")
    void incomeIsProportionalNotFixed() {
        Team one = aClub("Proportional A", 30_000, 20.0, 70.0, 1);
        Team two = aClub("Proportional B", 30_000, 20.0, 70.0, 1);
        for (int i = 0; i < 10; i++) {
            aPlayer(one, 20_000);
            aPlayer(two, 40_000);
        }

        finances.applyWeeklyFinances(one, 1, 1);
        finances.applyWeeklyFinances(two, 1, 1);

        double broadcastOne = amount(one, FinanceCategory.BROADCAST);
        double broadcastTwo = amount(two, FinanceCategory.BROADCAST);

        assertEquals(2.0, broadcastTwo / broadcastOne, 0.01,
                "twice the wages should be twice the television money. It was " + broadcastOne
                        + " and " + broadcastTwo + " - which is the old behaviour with a different "
                        + "number in front of it, and would look fine on the page.");
    }

    @Test
    @DisplayName("a good ground fills, a bad one does not")
    void occupancyFollowsReputation() {
        Team small = aClub("Quiet", 10_000, 16.0, 18.0, 2);
        Team packed = aClub("Packed", 10_000, 16.0, 92.0, 2);

        double quietShare = ClubEconomics.homeOccupancy(small);
        double packedShare = ClubEconomics.homeOccupancy(packed);

        assertTrue(packedShare > quietShare * 1.8,
                "a reputation of 92 filled " + Math.round(packedShare * 100) + "% and a reputation of "
                        + "18 filled " + Math.round(quietShare * 100) + "%. It used to be a literal "
                        + "that said both of them filled 72%.");
    }

    @Test
    @DisplayName("improving the pitch does not stop the club paying for its stadium")
    void improvingThePitchDoesNotRefundTheStadium() {
        Team club = aClub("Upkeep", 20_000, 18.0, 60.0, 3);
        // Fund the pitch so the maintenance branch is the one that runs.
        Stadium s = club.getStadium();
        s.setMaintenanceRemaining(500);
        s.setPitchCondition(60);
        teams.save(club);

        finances.applyWeeklyFinances(club, 1, 1);

        double stadium = amount(club, FinanceCategory.FACILITY_UPKEEP);
        assertTrue(stadium < 0,
                "stadium upkeep was " + stadium + " in a week where the pitch work ran. This method "
                        + "returned a single line, so charging for the pitch returned early and the "
                        + "club paid for its grass and not for its ground.");
    }

    @Test
    @DisplayName("a renewal changes what the club is actually charged")
    void aRenewalReachesTheWageBill() {
        Team club = aClub("Renewal", 15_000, 15.0, 55.0, 5);
        Player player = aPlayer(club, 120_000);
        // A renewal needs a contract to renew, and assignToClub is the path that writes one.
        contracts.assignToClub(player, club, 1, SquadRole.STARTER);

        assertTrue(contracts.renew(player.getId(), 1, 9_000.0).renewed(),
                "precondition: the renewal itself succeeds");
        assertEquals(9_000.0, playerRepositoryEarnings(player.getId()),
                "the renewal was recorded on the contract but not on the player. The wage bill reads "
                        + "Player.earnings and nothing else, so the club kept paying the old wage "
                        + "after the manager signed a new one.");
    }

    @Test
    @DisplayName("a wage is a share of a player's value, and one function says which")
    void aWageIsAShareOfValue() {
        double starYoung = ClubEconomics.weeklyWage(10_000_000, SquadRole.STAR, 20);
        double starOld = ClubEconomics.weeklyWage(10_000_000, SquadRole.STAR, 30);
        double prospect = ClubEconomics.weeklyWage(10_000_000, SquadRole.PROSPECT, 30);

        assertTrue(starOld > starYoung,
                "a 30-year-old star should be worth more to his club than a 20-year-old at the same "
                        + "market value, because his next contract is further away");
        assertTrue(starOld > prospect * 3,
                "a star and a prospect at the same value cannot be priced the same, or a squad's wage "
                        + "structure is a flat rate");
        assertEquals(0.0, ClubEconomics.weeklyWage(-5.0, SquadRole.STAR, 25),
                "a negative value must not produce a negative wage");
    }

    // --- helpers ---

    private double playerRepositoryEarnings(Long playerId) {
        return players.findById(playerId).orElseThrow().getEarnings();
    }

    private double income(Team club) {
        return ledger.findByTeamIdAndSeasonYearAndWeekNumber(club.getId(), 1, 1).stream()
                .filter(l -> l.getAmount() > 0)
                .mapToDouble(FinanceLedgerEntry::getAmount)
                .sum();
    }

    private double amount(Team club, FinanceCategory category) {
        return ledger.findByTeamIdAndSeasonYearAndWeekNumber(club.getId(), 1, 1).stream()
                .filter(l -> l.getCategory() == category)
                .mapToDouble(FinanceLedgerEntry::getAmount)
                .sum();
    }

    private FinanceLedgerEntry line(Team club, FinanceCategory category) {
        return ledger.findByTeamIdAndSeasonYearAndWeekNumber(club.getId(), 1, 1).stream()
                .filter(l -> l.getCategory() == category)
                .findFirst()
                .orElse(null);
    }

    private Competition aLeague(int tier) {
        Competition league = new Competition();
        league.setName("Economics league " + tier + "-" + System.nanoTime());
        league.setType(CompetitionType.LEAGUE);
        league.setTier(tier);
        league.setReputationWeight(tier * 20);
        return competitions.save(league);
    }

    private Competition aLeague() {
        return aLeague(1);
    }

    private Team aClub(String name, int capacity, double ticketPrice, double reputation, int tier) {
        Team club = new Team();
        club.setName(name + "-" + System.nanoTime());
        club.setCompetition(aLeague(tier));
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

    private Player aPlayer(Team club, double value) {
        Player player = new Player();
        player.setName("Economics player " + System.nanoTime());
        player.setTeam(club);
        player.setAge(25);
        player.setRating(70);
        player.setPlayerValue(value);
        player.setEarnings(ClubEconomics.weeklyWage(value, SquadRole.STARTER, 25));
        return players.save(player);
    }

    private List<Player> playersOf(Team club) {
        return players.findByTeamId(club.getId());
    }
}
