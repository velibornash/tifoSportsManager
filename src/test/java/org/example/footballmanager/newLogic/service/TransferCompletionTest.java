package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.FinanceCategory;
import org.example.footballmanager.newLogic.model.FinanceLedgerEntry;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.Transfer;
import org.example.footballmanager.newLogic.model.TransferStatus;

import org.example.footballmanager.newLogic.repository.FinanceLedgerEntryRepository;
import org.example.footballmanager.newLogic.repository.GameClockRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.repository.TransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agreeing a transfer has to actually transfer somebody (Sprint 3.2).
 *
 * <p>The negotiation service used to be a very careful conversation about a thing that never
 * happened. Offers were opened, countered, objected to and accepted; the winning offer's status
 * changed; and then the player stayed with the selling club, the buyer was never charged, no
 * contract existed and no ledger line was written. A market where every deal is agreed and no deal
 * ever completes is worse than no market, because it looks finished.
 *
 * <p>Every test here asserts on the three things that must be true afterwards: the player is at the
 * new club, the money moved, and the paper trail exists.
 */
@SpringBootTest
@ActiveProfiles("test")
class TransferCompletionTest {

    @Autowired NegotiationService negotiation;
    @Autowired TransferRepository transfers;
    @Autowired PlayerRepository players;
    @Autowired FinanceLedgerEntryRepository ledger;
    @Autowired TeamRepository teams;
    @Autowired GameClockRepository clocks;

    private Long sellerId;
    private Long buyerId;
    private Team seller;
    private Team buyer;

    @BeforeEach
    void setUp() {
        // The window has to be open or a permanent move is refused before it starts.
        var clock = clocks.findAll().stream().findFirst().orElseThrow();
        clock.setCurrentWeek(TransferWindowService.SUMMER_OPEN);
        clocks.save(clock);

        seller = club("Selling side", 9_000_000);
        buyer = club("Buying side", 9_000_000);
        sellerId = seller.getId();
        buyerId = buyer.getId();

        // Both clubs need a trading history, because the wage ceiling is income-based. A club with
        // no settled weeks has an income of zero and therefore a ceiling of zero, and canAfford
        // correctly refuses any wage at all - which is right, and useless as a test fixture.
        int season = season();
        for (int week = 1; week <= 8; week++) {
            for (Team c : List.of(seller, buyer)) {
                ledger.save(FinanceLedgerEntry.of(c, season, week,
                        FinanceCategory.BROADCAST, 120_000, "Broadcast income"));
            }
        }
    }

    private Integer season() {
        return clocks.findAll().stream().findFirst().orElseThrow().getCurrentSeason();
    }

    private Team club(String name, double budget) {
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

    private Player aPlayer(Team club, String name, double wage) {
        Player p = new Player();
        p.setName(name + "-" + System.nanoTime());
        p.setTeam(club);
        p.setAge(24);
        p.setPlayerValue(2_000_000);
        p.setEarnings(wage);
        p.setMorale(60.0);
        p.setForm(6.0);
        return players.save(p);
    }

    private Transfer listed(Player p, double asking) {
        Transfer t = new Transfer();
        t.setPlayer(p);
        t.setSellerTeam(p.getTeam());
        t.setAskingPrice(asking);
        t.setStatus(TransferStatus.LISTED);
        t.setListedAt(java.time.LocalDateTime.now());
        return transfers.save(t);
    }

    @Test
    @DisplayName("an accepted offer moves the player, the money and the paperwork")
    void acceptedOfferCompletesTheTransfer() {
        Player p = aPlayer(seller, "Subject", 5_000);
        Transfer transfer = listed(p, 1_200_000);

        var offer = negotiation.openOffer(transfer, buyer, 1_000_000, 9_000, 3);
        assertNotNull(offer, "the buyer should be able to open an offer in the window");

        double sellerBefore = seller.getBudget();
        double buyerBefore = buyer.getBudget();

        negotiation.acceptOffer(transfer.getId(), offer.getId());

        // The player is at the buying club, on the money that was agreed.
        Player reloaded = players.findById(p.getId()).orElseThrow();
        assertNotNull(reloaded.getTeam(), "an accepted offer must move the player");
        assertEquals(buyerId, reloaded.getTeam().getId(),
                "the player is still at the selling club, so the deal did not complete");
        assertEquals(9_000.0, reloaded.getEarnings(), 0.01,
                "he is not on the wage that was agreed");

        // The money actually moved, in both directions.
        assertEquals(buyerBefore - 1_000_000, teams.findById(buyerId).orElseThrow().getBudget(), 0.01,
                "the buyer paid the fee");
        assertEquals(sellerBefore + 1_000_000, teams.findById(sellerId).orElseThrow().getBudget(), 0.01,
                "the seller received it");

        // And there is a paper trail, because a budget that moves with no ledger is a bug waiting
        // to be a mystery.
        int season = season();
        List<FinanceLedgerEntry> buyerLines =
                ledger.findByTeamIdAndSeasonYearOrderByWeekNumberAsc(buyerId, season);
        assertTrue(buyerLines.stream()
                        .anyMatch(e -> e.getCategory() == FinanceCategory.TRANSFER_FEE_OUT
                                && Math.abs(e.getAmount()) > 0),
                "the buyer's payment must be on the ledger");
        assertTrue(ledger.findByTeamIdAndSeasonYearOrderByWeekNumberAsc(sellerId, season).stream()
                        .anyMatch(e -> e.getCategory() == FinanceCategory.TRANSFER_FEE_IN
                                && Math.abs(e.getAmount()) > 0),
                "the seller's receipt must be on the ledger");

        // The transfer itself is closed.
        assertEquals(TransferStatus.COMPLETED, transfers.findById(transfer.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("a rejected bid leaves the player where he was")
    void rejectedOfferChangesNothing() {
        Player p = aPlayer(seller, "Unsold", 5_000);
        Transfer transfer = listed(p, 900_000);
        var offer = negotiation.openOffer(transfer, buyer, 400_000, 6_000, 2);
        assertNotNull(offer);

        negotiation.rejectOffer(offer.getId());

        Player reloaded = players.findById(p.getId()).orElseThrow();
        assertEquals(sellerId, reloaded.getTeam().getId(), "a rejected bid must not move anyone");
        assertEquals(TransferStatus.LISTED, transfers.findById(transfer.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("a transfer outside the window is refused, so no deal can be agreed at all")
    void closedWindowRefusesTheMove() {
        var clock = clocks.findAll().stream().findFirst().orElseThrow();
        clock.setCurrentWeek(3);                       // no window in week 3
        clocks.save(clock);

        Player p = aPlayer(seller, "Out of season", 5_000);
        Transfer transfer = listed(p, 900_000);

        assertThrows(NegotiationService.TransferWindowClosedException.class,
                () -> negotiation.openOffer(transfer, buyer, 500_000, 6_000, 2),
                "a club cannot negotiate in a closed window");
    }
}
