package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.exception.ApiException;
import org.example.footballmanager.newLogic.model.FinanceCategory;
import org.example.footballmanager.newLogic.model.GameClock;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-4 — listing costs a percentage of the asking price.
 *
 * <p>Listing used to be free, so a club could list its whole squad at a million each and pay nothing.
 *
 * <p><b>What is guaranteed here, and why each one matters:</b>
 *
 * <ul>
 *   <li><b>The fee scales.</b> A pittance listing costs a pittance and a mega-listing costs a real
 *       sum. A flat fee, or a fee that ignores the asking price, fails this — and a flat fee would
 *       be the wrong mechanic anyway, since it would price a journeyman the same as a superstar.</li>
 *   <li><b>It is charged once.</b> Re-pricing a player who is <em>already</em> listed is not a new
 *       listing. The {@code alreadyListed} flag existed in the listing method and was computed and
 *       never used, so this was free; a relist loop would have been free too.</li>
 *   <li><b>The money actually moves</b> — budget down, one ledger line — because a fee that is only
 *       computed is a fee that is not a fee.</li>
 *   <li><b>AI clubs pay nothing</b>, by owner decision. Charging a weekly self-listing across 14,880
 *       AI clubs would drain the AI economy for a mechanic that exists to stop a manager spamming the
 *       market.</li>
 *   <li><b>A club that cannot fund the fee cannot list</b>, rather than listing anyway and going
 *       negative — otherwise the fee only constrains honest managers.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class ListingFeeScalesWithTheAskingPriceTest {

    @Autowired TransferRepository transfers;
    @Autowired PlayerRepository players;
    @Autowired TeamRepository teams;
    @Autowired GameClockRepository clocks;
    @Autowired FinanceLedgerEntryRepository ledger;
    @Autowired TransferListingFeeService fees;
    @Autowired TransferService transferService;
    @Autowired FinanceLedgerService ledgerService;

    private Team aClub(String name, double budget, boolean human) {
        Team t = new Team();
        t.setName(name + "-" + System.nanoTime());
        t.setBudget(budget);
        t.setReputation(70.0);
        t.setHumanControlled(human);
        Stadium s = new Stadium();
        s.setName(name + " Ground");
        s.setCapacity(25_000);
        s.setTicketPrice(20.0);
        s.setPitchQuality(85.0);
        s.setPitchCondition(85);
        s.setMaintenanceRemaining(0);
        t.setStadium(s);
        return teams.save(t);
    }

    private Player aPlayer(Team team, String name, double value) {
        Player p = new Player();
        p.setName(name);
        p.setTeam(team);
        p.setAge(24);
        p.setPlayerValue(value);
        p.setEarnings(5_000);
        p.setMorale(60.0);
        p.setForm(6.0);
        return players.save(p);
    }

    private long listingFeesCharged(Team team) {
        return ledger.findAll().stream()
                .filter(e -> e.getTeam() != null && team.getId().equals(e.getTeam().getId()))
                .filter(e -> e.getCategory() == FinanceCategory.LISTING_FEE)
                .count();
    }

    private double listingFeeTotal(Team team) {
        return ledger.findAll().stream()
                .filter(e -> e.getTeam() != null && team.getId().equals(e.getTeam().getId()))
                .filter(e -> e.getCategory() == FinanceCategory.LISTING_FEE)
                .mapToDouble(e -> Math.abs(e.getAmount()))
                .sum();
    }

    /** The core guarantee: the fee is a share of the asking price, not a constant. */
    @Test
    @DisplayName("the fee is a percentage of the asking price, so it scales with it")
    void theFeeScalesWithTheAskingPrice() {
        Team seller = aClub("ScalingSeller", 50_000_000, true);

        double cheap = fees.feeFor(seller, 100_000);
        double mid = fees.feeFor(seller, 2_000_000);
        double dear = fees.feeFor(seller, 20_000_000);

        assertEquals(2_500, cheap, 0.01, "2.5% of EUR 100,000");
        assertEquals(50_000, mid, 0.01, "2.5% of EUR 2,000,000");
        assertEquals(500_000, dear, 0.01, "2.5% of EUR 20,000,000");

        assertEquals(200, dear / cheap, 0.001,
                "twenty times the asking price must cost two hundred times the fee — a flat fee, or "
                        + "one that ignores the asking price, cannot satisfy this");
    }

    /** Listing is not free, and the money is real. */
    @Test
    @DisplayName("listing a player charges the fee and writes it to the ledger")
    void listingIsNoLongerFree() {
        var clock = clocks.findAll().stream().findFirst().orElseGet(() -> {
            GameClock fresh = new GameClock();
            fresh.setId(1L);
            fresh.setCurrentSeason(1);
            fresh.setCurrentWeek(3);
            return clocks.save(fresh);
        });
        clock.setCurrentWeek(3);
        clocks.save(clock);

        Team seller = aClub("PayingSeller", 10_000_000, true);
        Player p = aPlayer(seller, "ListedForSale", 4_000_000);

        Transfer saved = transferService.listPlayerForTransfer(p.getId(), 4_000_000);

        assertEquals(4_000_000, saved.getAskingPrice(), 0.01);
        assertEquals(1, listingFeesCharged(seller), "exactly one listing fee on the ledger");
        assertEquals(100_000, listingFeeTotal(seller), 0.01, "2.5% of EUR 4,000,000");
        assertEquals(9_900_000, teams.findById(seller.getId()).orElseThrow().getBudget(), 0.01,
                "the fee leaves the club's budget — a fee that is only computed is not a fee");

        // The board's exit criterion: the fee must be visible on the Finances page. That page is
        // driven entirely by FinanceCategory.values(), so the claim is that a charged fee shows up
        // in the summarised ledger a manager actually reads.
        Map<String, Object> finances = ledgerService.summarise(teams.findById(seller.getId()).orElseThrow());
        @SuppressWarnings("unchecked")
        Map<String, Double> byCategory = (Map<String, Double>) finances.get("byCategory");
        assertTrue(byCategory.containsKey(FinanceCategory.LISTING_FEE.name()),
                "the Finances page enumerates FinanceCategory, so a new category must appear there");
        assertEquals(-100_000, byCategory.get(FinanceCategory.LISTING_FEE.name()), 0.01,
                "stored as a cost — negative by the enum's own sign convention");
    }

    /** Re-pricing an already-listed player is not a second listing. */
    @Test
    @DisplayName("re-pricing a player who is already listed is not charged again")
    void relistingIsNotChargedTwice() {
        Team seller = aClub("RelistingSeller", 10_000_000, true);
        Player p = aPlayer(seller, "PricedTwice", 2_000_000);

        transferService.listPlayerForTransfer(p.getId(), 2_000_000);
        transferService.listPlayerForTransfer(p.getId(), 5_000_000);
        transferService.listPlayerForTransfer(p.getId(), 9_000_000);

        assertEquals(1, listingFeesCharged(seller),
                "three calls on one live listing is still one listing — otherwise a relist loop is free");
        assertEquals(50_000, listingFeeTotal(seller), 0.01, "charged on the first ask of EUR 2,000,000");
        assertEquals(9_000_000,
                transfers.findByPlayerId(p.getId()).orElseThrow().getAskingPrice(), 0.01,
                "and the asking price still tracks the latest request");
    }

    /** Owner decision: the AI economy does not pay a manager-facing mechanic. */
    @Test
    @DisplayName("an AI club is not charged for listing")
    void aiClubsAreNotCharged() {
        Team ai = aClub("AiClub", 1_000_000, false);
        Player p = aPlayer(ai, "MachineListed", 3_000_000);

        assertEquals(0.0, fees.feeFor(ai, 3_000_000), 0.001,
                "AI clubs self-list weekly; charging them would drain 14,880 budgets for nothing");

        transferService.listPlayerForTransfer(p.getId(), 3_000_000);

        assertEquals(0, listingFeesCharged(ai));
        assertEquals(1_000_000, teams.findById(ai.getId()).orElseThrow().getBudget(), 0.01,
                "and the AI budget is untouched");
    }

    /** A fee that can be spent into the negative is not a fee. */
    @Test
    @DisplayName("a club that cannot fund the fee cannot list")
    void aClubThatCannotAffordTheFeeCannotList() {
        Team seller = aClub("BrokeSeller", 100, true);
        Player p = aPlayer(seller, "Unaffordable", 5_000_000);

        ApiException refusal = assertThrows(ApiException.class,
                () -> transferService.listPlayerForTransfer(p.getId(), 5_000_000),
                "listing at EUR 5,000,000 costs EUR 125,000 and this club has EUR 100");

        assertEquals(HttpStatus.CONFLICT, refusal.getStatus());
        assertEquals("INSUFFICIENT_BUDGET_FOR_LISTING_FEE", refusal.getCode());
        assertEquals(0, listingFeesCharged(seller));
        assertTrue(transfers.findByPlayerId(p.getId()).isEmpty(),
                "and nothing was listed, or the fee constrains only honest managers");
    }
}