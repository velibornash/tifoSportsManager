package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.FeeStructure;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.Transfer;
import org.example.footballmanager.newLogic.model.TransferStatus;
import org.example.footballmanager.newLogic.repository.FeeStructureRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.repository.TransferRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 3.2 — instalments and sell-on clauses.
 *
 * <p>Nobody pays EUR 40m on the day, and the club that sold a player keeps a slice of the next fee.
 * Both of those are how the market actually works, and the game had neither: every fee was a single
 * number on one day, so there was no such thing as a club being owed money over time, and selling a
 * teenager was worth exactly what he was worth and no more.
 */
@SpringBootTest
@ActiveProfiles("test")
class TransferFeeServiceTest {

    @Autowired TeamRepository teams;
    @Autowired PlayerRepository players;
    @Autowired TransferRepository transfers;
    @Autowired FeeStructureRepository fees;
    @Autowired TransferFeeService service;

    private Team aClub(String name, double budget) {
        Team t = new Team();
        t.setName(name + "-" + System.nanoTime());
        t.setBudget(budget);
        t.setReputation(65.0);
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

    private Transfer aSale(Team seller, Player player, double fee) {
        Transfer t = new Transfer();
        t.setPlayer(player);
        t.setSellerTeam(seller);
        t.setStatus(TransferStatus.COMPLETED);
        t.setAgreedPrice(fee);
        return transfers.save(t);
    }

    private Player aPlayer(Team team, String name, double value) {
        Player p = new Player();
        p.setName(name);
        p.setTeam(team);
        p.setAge(20);
        p.setPlayerValue(value);
        p.setEarnings(3_000);
        p.setMorale(60.0);
        p.setForm(6.0);
        return players.save(p);
    }

    @Test
    @DisplayName("a small fee is paid on the day")
    void smallFeesArePaidUpfront() {
        Team seller = aClub("SmallSeller", 0);
        Player p = aPlayer(seller, "Cheap", 1_500_000);
        Transfer sale = aSale(seller, p, 400_000);

        FeeStructure f = service.agree(sale, 400_000, 0.0);
        assertEquals(400_000, f.getUpfront(), 0.01);
        assertEquals(0, f.getInstalments());
        assertFalse(f.hasOutstanding(), "a small fee leaves nothing owing");
        assertFalse(service.wouldBeInstalments(400_000));
    }

    @Test
    @DisplayName("a large fee is spread, and the seller is left owed money")
    void largeFeesAreSpread() {
        Team seller = aClub("BigSeller", 0);
        Player p = aPlayer(seller, "Expensive", 40_000_000);
        Transfer sale = aSale(seller, p, 24_000_000);

        FeeStructure f = service.agree(sale, 24_000_000, 0.15);
        assertTrue(f.hasOutstanding(), "a 24m fee is not paid on the day");
        assertTrue(f.getUpfront() < 24_000_000, "part of it is deferred");
        assertTrue(f.getOutstanding() > 0, "the selling club is owed the remainder");
        assertTrue(f.getInstalments() > 0);
        assertTrue(service.wouldBeInstalments(24_000_000));
    }

    @Test
    @DisplayName("instalments actually reach the selling club over time")
    void instalmentsArePaidOverTime() {
        Team seller = aClub("InstalSeller", 0);
        Player p = aPlayer(seller, "Instalment", 30_000_000);
        Transfer sale = aSale(seller, p, 20_000_000);
        FeeStructure f = service.agree(sale, 20_000_000, 0.0);

        double outstandingAtStart = f.getOutstanding();
        assertEquals(0.0, seller.getBudget(), 0.01, "nothing has been collected yet");

        double first = service.collectInstalment(sale.getId());
        assertTrue(first > 0, "a month passes and money arrives");

        // Re-agreing must NOT reset the schedule: the balance a club is owed is the whole point.
        FeeStructure reAgreed = service.agree(sale, 20_000_000, 0.0);
        assertTrue(reAgreed.getOutstanding() < outstandingAtStart,
                "the outstanding balance falls when an instalment is collected");
        assertTrue(reAgreed.getOutstanding() > 0, "and is not wiped by re-processing the deal");

        // Re-read: the service updates its own managed copy, so this reference is stale.
        Team reloaded = teams.findById(seller.getId()).orElseThrow();
        assertEquals(first, reloaded.getBudget(), 0.01,
                "and it lands in the selling club's real budget");
    }

    @Test
    @DisplayName("a sell-on clause pays the earlier club a share of the next fee")
    void sellOnPaysTheEarlierClub() {
        Team seller = aClub("SellOnSeller", 0);
        Player youngster = aPlayer(seller, "Academy", 2_000_000);
        Transfer firstSale = aSale(seller, youngster, 2_000_000);
        service.agree(firstSale, 2_000_000, 0.25);

        // He is sold again four years later for a lot more.
        double secondFee = 20_000_000;
        double share = service.sellOnShare(firstSale.getId(), secondFee);

        assertEquals(5_000_000, share, 0.01, "a 25% sell-on clause on a 20m next sale");
        assertEquals(5_000_000, teams.findById(seller.getId()).orElseThrow().getBudget(), 0.01,
                "which is how a club funds itself after selling a teenager");
    }

    @Test
    @DisplayName("no sell-on clause means no share - the distinction has to exist")
    void noClauseMeansNoShare() {
        Team seller = aClub("NoClause", 0);
        Player p = aPlayer(seller, "Plain", 1_000_000);
        Transfer sale = aSale(seller, p, 500_000);
        service.agree(sale, 500_000, 0.0);

        assertEquals(0.0, service.sellOnShare(sale.getId(), 10_000_000), 0.01);
        assertEquals(0.0, teams.findById(seller.getId()).orElseThrow().getBudget(), 0.01);
    }

    @Test
    @DisplayName("a sell-on clause is capped - above half, a club can never sell anyone")
    void sellOnIsCapped() {
        Team seller = aClub("GreedySeller", 0);
        Player p = aPlayer(seller, "Greedy", 5_000_000);
        Transfer sale = aSale(seller, p, 3_000_000);

        FeeStructure f = service.agree(sale, 3_000_000, 0.95);
        assertTrue(f.getSellOnPercentage() <= TransferFeeService.MAX_SELL_ON,
                "an uncapped clause would kill the market: " + f.getSellOnPercentage());
    }

    @Test
    @DisplayName("an unknown transfer owes nothing rather than throwing")
    void unknownTransferIsHandled() {
        assertEquals(0.0, service.sellOnShare(999_999L, 10_000_000), 0.001);
        assertEquals(0.0, service.collectInstalment(999_999L), 0.001);
    }
}
