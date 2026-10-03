package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.dto.transfer.TeamTransferOverviewDTO;
import org.example.footballmanager.newLogic.dto.transfer.TransferDTO;
import org.example.footballmanager.newLogic.dto.transfer.TransferOfferDTO;
import org.example.footballmanager.newLogic.exception.ApiException;
import org.example.footballmanager.newLogic.model.FinanceCategory;
import org.example.footballmanager.newLogic.model.FinanceLedgerEntry;
import org.example.footballmanager.newLogic.model.GameClock;
import org.example.footballmanager.newLogic.model.OfferStatus;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.SquadRole;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.Transfer;
import org.example.footballmanager.newLogic.model.TransferOffer;
import org.example.footballmanager.newLogic.model.TransferStatus;
import org.example.footballmanager.newLogic.repository.FinanceLedgerEntryRepository;
import org.example.footballmanager.newLogic.repository.GameClockRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.repository.TransferOfferRepository;
import org.example.footballmanager.newLogic.repository.TransferRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * P2-2 — a seller accepts <em>one named offer</em>, and accepting one works at all.
 *
 * <p><b>Why this class exists.</b> {@code NegotiationServiceTest} already proves that
 * {@code acceptOffer(transferId, offerId)} picks the offer it is given, and it is green. It proves it
 * by calling the <em>service</em>. The only path the product has to that method went through
 * {@code TransferService.acceptBestOffer}, which settled the transfer and then settled it a second
 * time; the second settlement hit the {@code COMPLETED} guard, returned false, and threw
 * {@code ApiException(CONFLICT, "TRANSFER_NOT_COMPLETED")} — an unchecked exception, so the
 * transaction rolled the whole thing back. The endpoint could only ever answer 409. Four green test
 * classes called {@code acceptOffer} directly and none of them went near the broken hop, which is
 * precisely the "a green status is not evidence" shape this repository has been bitten by before.
 *
 * <p><b>What is guaranteed here, and why each one matters:</b>
 *
 * <ul>
 *   <li>Accepting an offer completes the deal and answers 2xx. Not "does not throw" — the player
 *       actually moves clubs, because a settlement that rolled back leaves the seller with an
 *       accepted bid and no transfer.</li>
 *   <li>Exactly one fee out and one fee in are written. Two settlements would move the money twice;
 *       this is asserted against the ledger rather than trusted.</li>
 *   <li>The seller chooses. Accepting the <em>cheaper</em> of two live offers must move the player
 *       at the cheaper fee. A max-by-fee implementation cannot pass this, which is the point.</li>
 *   <li>An offer belonging to a different transfer is refused, visibly. {@code acceptOffer} returns
 *       the thread unchanged for a wrong id and a nonexistent id alike, so a caller cannot tell a
 *       refusal from a success — the caller has to be told.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class SellerAcceptsANamedOfferTest {

    @Autowired TransferOfferRepository offers;
    @Autowired TransferRepository transfers;
    @Autowired PlayerRepository players;
    @Autowired TeamRepository teams;
    @Autowired GameClockRepository clocks;
    @Autowired FinanceLedgerEntryRepository ledger;
    @Autowired NegotiationService negotiation;
    @Autowired PlayerContractService contracts;
    @Autowired TransferService transferService;

    /**
     * Puts the calendar inside a registration window, creating the clock if the database has none.
     *
     * <p>The clock is created here rather than assumed. {@code NegotiationServiceTest} does
     * {@code clocks.findAll().stream().findFirst().orElseThrow()} and is green in a full suite but
     * <b>red 10/10 when run alone</b> — it depends on some earlier test having seeded a clock. A test
     * that passes or fails depending on what ran before it is not asserting anything about the code
     * under test, so this one builds what it needs.
     */
    private void inWindow() {
        var clock = clocks.findAll().stream().findFirst().orElseGet(() -> {
            GameClock fresh = new GameClock();
            fresh.setId(1L);
            fresh.setCurrentSeason(1);
            return clocks.save(fresh);
        });
        clock.setCurrentWeek(TransferWindowService.SUMMER_OPEN);
        clocks.save(clock);
    }

    private Team aClub(String name, double budget) {
        Team t = new Team();
        t.setName(name + "-" + System.nanoTime());
        t.setBudget(budget);
        t.setReputation(70.0);
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

    /**
     * Gives a club settled income, so the board grants it a transfer budget.
 *
     * <p>Not cosmetic. {@code TransferBudgetService.budgetFor} grades a club from its settled ledger
     * income, and {@code canAfford} refuses outright when there is none — "No settled income yet, so
     * no transfer budget has been granted." A club built with only a cash balance therefore cannot
     * sign anybody, and {@code settle} returns false. That is correct product behaviour and a trap
     * for a fixture: {@code NegotiationServiceTest.sellerChoosesAndOtherOffersSurvive} passes against
     * clubs like these, which means its acceptances never actually moved a player.
     */
    private void withSettledIncome(Team club, double weeklyIncome) {
        ledger.save(FinanceLedgerEntry.of(club, 1, 1, FinanceCategory.SPONSORSHIP,
                weeklyIncome, "Test sponsorship"));
    }

    private Player aPlayer(Team team, String name, double value, double wage) {
        Player p = new Player();
        p.setName(name);
        p.setTeam(team);
        p.setAge(26);
        p.setPlayerValue(value);
        p.setEarnings(wage);
        p.setMorale(60.0);
        p.setForm(6.0);
        return players.save(p);
    }

    private Transfer aListing(Team seller, Player player) {
        Transfer t = new Transfer();
        t.setPlayer(player);
        t.setSellerTeam(seller);
        t.setStatus(TransferStatus.LISTED);
        t.setAskingPrice(Math.max(1.0, player.getPlayerValue() * 0.3));
        return transfers.save(t);
    }

    private long ledgerCount(Team team, FinanceCategory category) {
        return ledger.findAll().stream()
                .filter(e -> e.getTeam() != null && team.getId().equals(e.getTeam().getId()))
                .filter(e -> e.getCategory() == category)
                .count();
    }

    /**
     * The defect, stated as the guarantee that replaces it: accepting an incoming offer has to
     * complete the transfer. Before the fix this threw
     * {@code ApiException(CONFLICT, "TRANSFER_NOT_COMPLETED")} and rolled back.
     */
    @Test
    @DisplayName("accepting an incoming offer completes the deal instead of reporting a conflict")
    void acceptingAnOfferCompletesTheTransfer() {
        inWindow();
        Team seller = aClub("CompleteSeller", 1_000_000);
        Team buyer = aClub("CompleteBuyer", 30_000_000);
        withSettledIncome(buyer, 10_000_000);
        Player p = aPlayer(seller, "Completed", 5_000_000, 14_000);
        contracts.assignToClub(p, seller, 1, SquadRole.STARTER);
        Transfer listing = aListing(seller, p);
        TransferOffer bid = negotiation.openOffer(listing, buyer, 1_500_000, 14_000, 3);

        TransferDTO dto = assertCompleted(transferService.acceptBestOffer(p.getId(), seller.getId()));

        assertEquals(OfferStatus.ACCEPTED, offers.findById(bid.getId()).orElseThrow().getStatus());
        assertEquals(TransferStatus.COMPLETED,
                transfers.findById(listing.getId()).orElseThrow().getStatus());
        assertEquals(buyer.getId(), players.findById(p.getId()).orElseThrow().getTeam().getId(),
                "the player must actually have moved clubs, not merely had an offer accepted");
        assertEquals(1, ledgerCount(buyer, FinanceCategory.TRANSFER_FEE_OUT),
                "exactly one fee out — a second settlement would move the money twice");
        assertEquals(1, ledgerCount(seller, FinanceCategory.TRANSFER_FEE_IN),
                "exactly one fee in");
        assertEquals(1_500_000, dto.getAgreedPrice(), 0.01);
    }

    /**
     * The feature the board asked for. The seller takes the <em>cheaper</em> bid, because the other
     * club is the better fit — which is the whole reason the service method takes an {@code offerId}.
     * No max-by-fee implementation can pass this.
     */
    @Test
    @DisplayName("the seller picks which of two live offers to accept")
    void theSellerChoosesTheOffer() {
        inWindow();
        Team seller = aClub("PickingSeller", 1_000_000);
        Team dear = aClub("DearBidder", 30_000_000);
        Team cheap = aClub("CheapBidder", 30_000_000);
        withSettledIncome(dear, 10_000_000);
        withSettledIncome(cheap, 10_000_000);
        Player p = aPlayer(seller, "Auctioned", 5_000_000, 14_000);
        contracts.assignToClub(p, seller, 1, SquadRole.STARTER);
        Transfer listing = aListing(seller, p);

        TransferOffer dearOffer = negotiation.openOffer(listing, dear, 2_000_000, 18_000, 3);
        TransferOffer cheapOffer = negotiation.openOffer(listing, cheap, 1_200_000, 14_000, 3);

        TransferDTO dto = assertCompleted(
                transferService.acceptOffer(p.getId(), cheapOffer.getId(), seller.getId()));

        assertEquals(1_200_000, dto.getAgreedPrice(), 0.01,
                "the named offer must be the one that is settled, not the richest one");
        assertEquals(cheap.getId(), players.findById(p.getId()).orElseThrow().getTeam().getId());
        assertEquals(OfferStatus.ACCEPTED,
                offers.findById(cheapOffer.getId()).orElseThrow().getStatus());
        assertEquals(OfferStatus.REJECTED,
                offers.findById(dearOffer.getId()).orElseThrow().getStatus(),
                "the bidder who lost is told the player has gone");
        assertNotNull(offers.findById(dearOffer.getId()).orElseThrow(),
                "and the rejected bid survives as a record rather than being deleted");
    }

    /**
     * An offer id from another transfer must be refused, not silently absorbed.
     *
     * <p>{@code acceptOffer} filters the offer out of <em>this</em> transfer's thread and returns
     * that thread unchanged when it is not found — so a wrong id and a successful accept are
     * indistinguishable to the caller unless the caller is told. The exit criterion on the board
     * says "rejects an offer for a different transfer"; a silent no-op is not a rejection.
     */
    @Test
    @DisplayName("an offer belonging to a different transfer is refused")
    void anOfferFromAnotherTransferIsRefused() {
        inWindow();
        Team seller = aClub("CrossedSeller", 1_000_000);
        Team otherSeller = aClub("OtherSeller", 1_000_000);
        Team buyer = aClub("CrossedBuyer", 30_000_000);
        withSettledIncome(buyer, 10_000_000);
        Player mine = aPlayer(seller, "MineToSell", 5_000_000, 14_000);
        Player theirs = aPlayer(otherSeller, "TheirsToSell", 5_000_000, 14_000);
        contracts.assignToClub(mine, seller, 1, SquadRole.STARTER);
        contracts.assignToClub(theirs, otherSeller, 1, SquadRole.STARTER);
        Transfer myListing = aListing(seller, mine);
        aListing(otherSeller, theirs);
        negotiation.openOffer(myListing, buyer, 1_500_000, 14_000, 3);
        TransferOffer theirOffer = negotiation.openOffer(
                transfers.findByPlayerId(theirs.getId()).orElseThrow(), buyer, 9_000_000, 14_000, 3);

        ApiException refusal = assertThrows(ApiException.class,
                () -> transferService.acceptOffer(mine.getId(), theirOffer.getId(), seller.getId()),
                "an offer on a different transfer must not be accepted for this one");
        // The specific refusal, not merely "something was thrown". An earlier version of this test
        // asserted only the exception type and passed against code that had stopped scoping the
        // lookup, because an incidental TRANSFER_NOT_COMPLETED is also an ApiException.
        assertEquals(HttpStatus.NOT_FOUND, refusal.getStatus(),
                "a bid on someone else's transfer is not found on this one, so 404 — not a conflict");
        assertEquals("OFFER_NOT_FOUND", refusal.getCode());

        assertEquals(TransferStatus.LISTED,
                transfers.findById(myListing.getId()).orElseThrow().getStatus(),
                "and the attempted transfer must be untouched");
        assertEquals(OfferStatus.OPEN,
                offers.findById(theirOffer.getId()).orElseThrow().getStatus(),
                "the other club's offer must still be live on its own transfer");
    }

    /**
 * The Transfer Centre's "Incoming offers" panel is reachable.
 *
 * <p>{@code getTeamTransferOverview} built that list with
 * {@code .filter(this::hasOpenOffer).filter(t -> !isActiveListing(t))}. But {@code hasOpenOffer}
 * requires {@code buyerTeam == null} and a priced bid, and {@code isActiveListing} requires status
 * {@code LISTED} and {@code buyerTeam == null} — so a listed player with a bid satisfied the first
 * filter and was then removed by the second. The list was empty for every possible input, and the
 * panel that renders it never appeared. Each bid must reach the browser with its id, because the
 * accept button has nothing to carry without one.
 */
@Test
    @DisplayName("the seller is shown his bids, each with the id the accept button needs")
    void theIncomingOffersPanelIsReachable() {
        inWindow();
        Team seller = aClub("OverviewSeller", 1_000_000);
        Team buyerA = aClub("OverviewA", 30_000_000);
        Team buyerB = aClub("OverviewB", 30_000_000);
        withSettledIncome(buyerA, 10_000_000);
        withSettledIncome(buyerB, 10_000_000);
        Player p = aPlayer(seller, "OnShow", 5_000_000, 14_000);
        contracts.assignToClub(p, seller, 1, SquadRole.STARTER);
        Transfer listing = aListing(seller, p);
        TransferOffer first = negotiation.openOffer(listing, buyerA, 1_200_000, 14_000, 3);
        TransferOffer second = negotiation.openOffer(listing, buyerB, 1_600_000, 16_000, 3);

        TeamTransferOverviewDTO overview = transferService
                .getTeamTransferOverview(seller.getId(), seller.getId());

        List<TransferDTO> incoming = overview.getIncomingOffers();
        assertEquals(1, incoming.size(),
                "a listed player with two live bids must appear in the seller's incoming offers");
        TransferDTO shown = incoming.get(0);
        assertEquals(p.getId(), shown.getPlayerId());
        assertTrue(shown.isCanAcceptOffer(), "and the seller must be allowed to answer the bids");

        List<TransferOfferDTO> shownOffers = shown.getOffers();
        assertEquals(2, shownOffers.size(), "both bids, not a merged summary line");
        // Order is deliberately not asserted: liveOffers comes back richest-first, which is what a
        // seller wants to read. What matters is that each bid kept its own identity.
        assertEquals(List.of(first.getId(), second.getId()).stream().sorted().toList(),
                shownOffers.stream().map(TransferOfferDTO::getId).sorted().toList(),
                "each bid keeps its own identity, which is what a button needs");
        assertTrue(shownOffers.stream().allMatch(o -> o.getNetToSeller() != null),
                "and each states what the seller actually receives");
    }

    /** The endpoint must answer 2xx with the transfer it just completed, not throw. */
    private TransferDTO assertCompleted(TransferDTO dto) {
        assertNotNull(dto, "the endpoint must answer with the transfer it just completed");
        assertEquals(Boolean.TRUE, dto.getOfferAccepted());
        return dto;
    }
}