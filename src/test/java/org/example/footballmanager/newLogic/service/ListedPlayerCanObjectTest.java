package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.exception.ApiException;
import org.example.footballmanager.newLogic.model.FinanceCategory;
import org.example.footballmanager.newLogic.model.GameClock;
import org.example.footballmanager.newLogic.model.ListingObjection;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.SquadRole;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-3 — a player can refuse to be put on the transfer list.
 *
 * <p>The anti-speculation mechanic, and the anti-daytrade one: a club used to be able to list anybody
 * it liked at any price and the player's position was not modelled at all.
 *
 * <p><b>What is guaranteed here:</b>
 *
 * <ul>
 *   <li><b>A listed player can object, and says why.</b> The board's criterion is "refusal has a
 *       visible reason", so the reason is asserted as a string, not merely as a non-null flag.</li>
 *   <li><b>The club cannot delist him</b> while the objection stands. Delisting is how a club would
 *       make the objection go away, so it is precisely what has to be blocked.</li>
 *   <li><b>The club cannot accept a bid</b> while it stands, for the same reason: accepting is the
 *       act of selling him.</li>
 *   <li><b>And the objection survives re-listing.</b> The transfer row is unique per player and is
 *       recycled on every listing, so clearing the objection there would have made
 *       "reject the bids, take him off, put him back" a free way to wipe a player's refusal — which
 *       is the exact thing the mechanic exists to prevent.</li>
 *   <li><b>Resolving it costs something, or gives something up.</b> Upholding the player withdraws
 *       the listing; paying compensation clears it, charges the budget, and writes a ledger line.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class ListedPlayerCanObjectTest {

    @Autowired TransferRepository transfers;
    @Autowired PlayerRepository players;
    @Autowired TeamRepository teams;
    @Autowired GameClockRepository clocks;
    @Autowired FinanceLedgerEntryRepository ledger;
    @Autowired TransferService transferService;
    @Autowired ListingObjectionService objections;
    @Autowired PlayerContractService contracts;
    @Autowired NegotiationService negotiation;
    @Autowired org.example.footballmanager.newLogic.repository.PlayerContractRepository contractRepository;

    /** A club cannot negotiate in April, and a bid needs the window open. */
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

    private Player aPlayer(Team team, String name, double value, double wage) {
        Player p = new Player();
        p.setName(name);
        p.setTeam(team);
        p.setAge(26);
        p.setPlayerValue(value);
        p.setEarnings(wage);
        p.setMorale(55.0);
        p.setForm(6.0);
        return players.save(p);
    }

    /** Forces an objection deterministically, rather than hoping the roll goes a certain way. */
    private Transfer objectedListing(Team seller, Player player, double askingPrice) {
        transferService.listPlayerForTransfer(player.getId(), askingPrice);
        Transfer transfer = transfers.findByPlayerId(player.getId()).orElseThrow();
        objections.raiseIfWarranted(transfer, 0.0);
        return transfers.save(transfer);
    }

    private long objectionCostsCharged(Team team) {
        return ledger.findAll().stream()
                .filter(e -> e.getTeam() != null && team.getId().equals(e.getTeam().getId()))
                .filter(e -> e.getCategory() == FinanceCategory.LISTING_FEE)
                .filter(e -> e.getNote() != null && e.getNote().startsWith("Compensation"))
                .count();
    }

    /** The mechanic exists, and a refusal is never a bare flag. */
    @Test
    @DisplayName("a listed player can object, and the reason is visible")
    void aPlayerCanObjectAndSaysWhy() {
        Team seller = aClub("ObjectingSeller", 20_000_000, true);
        Player star = aPlayer(seller, "UnhappyStar", 12_000_000, 8_000);
        contracts.assignToClub(star, seller, 1, SquadRole.STAR);

        Transfer transfer = objectedListing(seller, star, 8_000_000);

        assertEquals(ListingObjection.WAGE_DISPUTE, transfer.getListingObjection(),
                "a STAR paid well under his own demand has a grievance, not a preference");
        assertNotNull(transfer.getListingObjectionReason());
        assertTrue(transfer.getListingObjectionReason().contains("underpaid"),
                "the manager is told what the problem actually is, so he can act on it: "
                        + transfer.getListingObjectionReason());
    }

    /** The board's criterion: the club cannot list him without resolving it. */
    @Test
    @DisplayName("the club cannot delist a player who is objecting")
    void theClubCannotDelistAnObjectingPlayer() {
        Team seller = aClub("DelistingSeller", 20_000_000, true);
        Player star = aPlayer(seller, "StaysPut", 12_000_000, 8_000);
        contracts.assignToClub(star, seller, 1, SquadRole.STAR);
        objectedListing(seller, star, 8_000_000);

        ApiException refusal = assertThrows(ApiException.class,
                () -> transferService.removeFromTransferList(star.getId(), seller.getId()));

        assertEquals(HttpStatus.CONFLICT, refusal.getStatus());
        assertEquals("PLAYER_OBJECTION_OPEN", refusal.getCode());
        assertTrue(refusal.getMessage().contains("underpaid"),
                "and the refusal carries the reason, not just a code: " + refusal.getMessage());
        assertEquals(TransferStatus.LISTED,
                transfers.findByPlayerId(star.getId()).orElseThrow().getStatus(),
                "the player is still on the list — the club did not get to make the objection vanish");
    }

    /** Accepting a bid is the act of selling him, so it is blocked too. */
    @Test
    @DisplayName("the club cannot accept a bid while the player is objecting")
    void theClubCannotAcceptABidWhileHeObjects() {
        inWindow();
        Team seller = aClub("SellingSeller", 20_000_000, true);
        Team buyer = aClub("EagerBuyer", 30_000_000, true);
        Player star = aPlayer(seller, "ReluctantSale", 12_000_000, 8_000);
        contracts.assignToClub(star, seller, 1, SquadRole.STAR);
        Transfer listing = transferService.listPlayerForTransfer(star.getId(), 8_000_000);

        // A real bid through the service, because "there are no offers" is a different refusal from
        // "you may not accept this one" and the test has to be reaching the guard, not an earlier check.
        negotiation.openOffer(transfers.findById(listing.getId()).orElseThrow(), buyer,
                8_000_000, 20_000, 3);

        Transfer live = transfers.findById(listing.getId()).orElseThrow();
        objections.raiseIfWarranted(live, 0.0);
        transfers.save(live);

        ApiException refusal = assertThrows(ApiException.class,
                () -> transferService.acceptBestOffer(star.getId(), seller.getId()));

        assertEquals(HttpStatus.CONFLICT, refusal.getStatus());
        assertEquals("PLAYER_OBJECTION_OPEN", refusal.getCode());
        assertEquals(seller.getId(), players.findById(star.getId()).orElseThrow().getTeam().getId(),
                "and he did not move clubs");
    }

    /**
 * The loophole: relisting must not relabel a refusal.
 *
 * <p><b>This test was written wrong first and had to be rewritten.</b> The obvious version — object,
 * then relist, then assert the objection is still there — <b>passed against broken code</b>, because a
 * relisting re-evaluates the player from scratch and raised the objection again with the same
 * reason. It could not tell "survived" from "re-raised".
 *
 * <p>So the player's grievance is cured between the two: he is given a raise, which removes the
 * wage dispute but leaves him a STAR the club would hate to lose. A fresh evaluation would now
 * produce a <em>different</em> reason ({@code DOES_NOT_WANT_TO_LEAVE}). The standing reason must
 * survive that, because the grievance he complained about is the one a manager has to answer.
 */
@Test
    @DisplayName("re-listing does not relabel or overwrite a standing objection")
    void relistingDoesNotWipeTheObjection() {
        Team seller = aClub("RelistingSeller", 20_000_000, true);
        Player star = aPlayer(seller, "RelistedStar", 12_000_000, 8_000);
        contracts.assignToClub(star, seller, 1, SquadRole.STAR);
        objectedListing(seller, star, 8_000_000);

        // Cure the wage grievance. He is now well paid, so a fresh evaluation would reach a
        // different conclusion — which is exactly what makes this test able to fail.
        var contract = contractRepository.findByPlayerId(star.getId()).orElseThrow();
        contract.setWeeklyWage(contracts.wageDemand(star.getId()).demandedWeeklyWage() * 2);
        contractRepository.save(contract);

        Transfer standing = transfers.findByPlayerId(star.getId()).orElseThrow();
        ListingObjection raisedAgain = objections.raiseIfWarranted(standing, 0.0);
        transfers.save(standing);

        // There is deliberately no assertion on `raisedAgain`. With the guard working, the method
        // returns the standing objection and never performs a fresh evaluation, so "what a fresh
        // evaluation would have concluded" is unobservable through this API — and a precondition
        // asserting it cannot be written. The setup is validated by the break instead: with the
        // guard removed this test fails with DOES_NOT_WANT_TO_LEAVE, which is only reachable once
        // the wage grievance really has been cured.
        assertEquals(ListingObjection.WAGE_DISPUTE, standing.getListingObjection(),
                "but the standing objection survives, with the reason the player actually gave");
        assertTrue(raisedAgain == ListingObjection.WAGE_DISPUTE,
                "and the caller is handed the standing reason, not a freshly invented one");
        assertTrue(standing.getListingObjectionReason().contains("underpaid"),
                "a relisting must not quietly rewrite a player's reason: "
                        + standing.getListingObjectionReason());
    }

    /** Resolution one: give the player what he asked for, and keep him. */
    @Test
    @DisplayName("upholding the player withdraws the listing and he stays yours")
    void upholdingThePlayerWithdrawsTheListing() {
        Team seller = aClub("UpholdingSeller", 20_000_000, true);
        Player star = aPlayer(seller, "KeptAtHome", 12_000_000, 8_000);
        contracts.assignToClub(star, seller, 1, SquadRole.STAR);
        objectedListing(seller, star, 8_000_000);

        transferService.resolveListingObjection(star.getId(),
                TransferService.ObjectionResolution.UPHELD, seller.getId());

        Transfer after = transfers.findByPlayerId(star.getId()).orElseThrow();
        assertEquals(TransferStatus.CANCELLED, after.getStatus());
        assertEquals(ListingObjection.NONE, after.getListingObjection());
        assertEquals(seller.getId(), players.findById(star.getId()).orElseThrow().getTeam().getId(),
                "he stays yours, and the objection is settled");
    }

    /** Resolution two: pay him to let it go, and carry on selling. */
    @Test
    @DisplayName("paying compensation clears the objection and charges the club")
    void payingCompensationClearsTheObjection() {
        Team seller = aClub("PayingSeller", 20_000_000, true);
        Player star = aPlayer(seller, "BoughtOff", 12_000_000, 8_000);
        contracts.assignToClub(star, seller, 1, SquadRole.STAR);
objectedListing(seller, star, 8_000_000);
        double beforeResolution = teams.findById(seller.getId()).orElseThrow().getBudget();

        transferService.resolveListingObjection(star.getId(),
                TransferService.ObjectionResolution.PAID, seller.getId());

        Transfer after = transfers.findById(star.getId()).orElseThrow();
        assertEquals(ListingObjection.NONE, after.getListingObjection(),
                "the objection is resolved and the player stays on the market");
        assertEquals(TransferStatus.LISTED, after.getStatus(), "so the club can carry on selling him");
        assertEquals(1, objectionCostsCharged(seller), "and the compensation is on the ledger");

        // Measured as a delta, not an absolute: the listing itself already charged the 2.5% listing
        // fee from P2-4, and this club has paid both. Asserting the absolute balance would be
        // asserting the other feature too, and would break the day that fee changes.
        assertEquals(400_000, beforeResolution
                        - teams.findById(seller.getId()).orElseThrow().getBudget(), 0.01,
                "5% of the EUR 8,000,000 asking price, paid to overrule him");
    }
}