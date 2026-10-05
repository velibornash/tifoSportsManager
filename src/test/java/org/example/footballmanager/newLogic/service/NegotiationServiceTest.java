package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.OfferStatus;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.SquadRole;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.Transfer;
import org.example.footballmanager.newLogic.model.TransferOffer;
import org.example.footballmanager.newLogic.repository.TransferOfferRepository;
import org.example.footballmanager.newLogic.repository.TransferRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 3.2 — real negotiation.
 *
 * <p>The old model kept offers as {@code "Partizan offered €450000"} in a {@code Set<String>},
 * deduplicated by a prefix match on the club name — so a club named {@code Partizan} wiped the
 * offers of {@code Partizan United Youth} — and resolved the buyer with {@code findByName}, which
 * <b>throws</b> on a duplicate name. Both are properties these tests pin.
 */
@SpringBootTest
@ActiveProfiles("test")
class NegotiationServiceTest {

    @Autowired TransferOfferRepository offers;
    @Autowired org.example.footballmanager.newLogic.repository.PlayerRepository players;
    @Autowired org.example.footballmanager.newLogic.repository.TeamRepository teams;
    @Autowired TransferRepository transfers;
    @Autowired NegotiationService negotiation;
    @Autowired PlayerContractService contracts;
    @Autowired org.example.footballmanager.newLogic.repository.GameClockRepository clockRepository;

    /**
     * Puts the calendar inside a registration window.
     *
     * <p>Necessary, not cosmetic: {@code NegotiationService.openOffer} refuses an out-of-window
     * attempt, and a club cannot negotiate in April whoever it is. The AI market is guarded the same
     * way and simply does not run outside a window. A test that opened offers without setting the
     * clock was testing a path that cannot happen in the game.
     */
    private void inWindow() {
        // **Create the clock rather than expect one.** Boot writes nothing (DatabaseInitializer's
        // ensureBaselineDataOnStartup has no caller), so the test database has no GameClock row and
        // `findFirst().orElseThrow()` failed in *every* method of this class before a single assertion ran.
        //
        // It also made the class look broken when it is only unseeded: the same code is **green in a full
        // run**, because some earlier class leaves a clock row behind. That is the clearest example on the
        // board of why "per-class green" and "green in a full run" are not the same measurement -- and why
        // neither is a substitute for reading the failure.
        var clock = clockRepository.findAll().stream()
                .findFirst()
                .orElseGet(org.example.footballmanager.newLogic.model.GameClock::new);
        clock.setCurrentSeason(1);
        clock.setCurrentDay(1);
        clock.setCurrentWeek(TransferWindowService.SUMMER_OPEN);
        // GameClock has no service-level save, so go through the repository.
        clockRepository.save(clock);
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

    private Transfer aListing(Team seller, Player player) {
        Transfer t = new Transfer();
        t.setPlayer(player);
        t.setSellerTeam(seller);
        t.setStatus(org.example.footballmanager.newLogic.model.TransferStatus.LISTED);
        t.setAskingPrice(Math.max(1.0, player.getPlayerValue() * 0.3));
        return transfers.save(t);
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
        // Must be persisted: a contract and a listing both hold a reference to it, and Hibernate
        // refuses to flush a transient instance.
        return players.save(p);
    }

    @Test
    @DisplayName("fee, wage and contract length are three separate things")
    void threeTermsAreSeparate() {
        inWindow();
        Team seller = aClub("Seller", 1_000_000);
        Team buyer = aClub("Buyer", 20_000_000);
        Player star = aPlayer(seller, "Star", 12_000_000, 40_000);
        contracts.assignToClub(star, seller, 1, SquadRole.STAR);

        TransferOffer offer = negotiation.openOffer(aListing(seller, star), buyer,
                4_000_000, 45_000, 5);

        assertNotNull(offer.getId());
        assertEquals(4_000_000, offer.getFee(), 0.01);
        assertEquals(45_000, offer.getWage(), 0.01);
        assertEquals(5, offer.getContractYears());
        assertEquals(buyer.getId(), offer.getBuyerTeam().getId(),
                "the buyer must be held as an identity, not a name");
    }

    @Test
    @DisplayName("an agent takes 2-5% of the fee, and the seller gets the rest")
    void agentTakesACut() {
        Team seller = aClub("AgentSeller", 1_000_000);
        Team buyer = aClub("AgentBuyer", 30_000_000);
        Player p = aPlayer(seller, "Priced", 5_000_000, 15_000);
        contracts.assignToClub(p, seller, 1, SquadRole.STARTER);

        TransferOffer offer = negotiation.openOffer(aListing(seller, p), buyer, 1_500_000, 20_000, 3);
        assertNotNull(offer.getAgentFee());
        assertTrue(offer.getAgentFee() >= 1_500_000 * 0.019 && offer.getAgentFee() <= 1_500_000 * 0.051,
                "the agent's share must be 2-5% of the fee, was " + offer.getAgentFee());
        assertEquals(1_500_000 - offer.getAgentFee(), offer.netToSeller(), 0.01,
                "the seller receives the fee less the agent's cut");
    }

    @Test
    @DisplayName("a deal can be agreed on the fee and still fail because the player refuses the wage")
    void thePlayerIsAThirdParty() {
        inWindow();
        Team seller = aClub("PWeller", 1_000_000);
        Team buyer = aClub("PBuyer", 30_000_000);
        Player greedy = aPlayer(seller, "Greedy", 9_000_000, 20_000);
        contracts.assignToClub(greedy, seller, 1, SquadRole.STAR);

        // Offer above what he actually asks for. The model refusing less than the demand is the
        // correct behaviour, so the "generous" case has to be genuinely generous.
        double demand = contracts.wageDemand(greedy.getId()).demandedWeeklyWage();
        TransferOffer generous = negotiation.openOffer(aListing(seller, greedy), buyer,
                3_000_000, demand * 1.3, 4);
        assertTrue(negotiation.playerWouldSign(generous),
                "an offer above his demand should be acceptable, but: " + generous.getPlayerObjection());

        // A second player whose demand is far higher than what is on the table.
        Player modest = aPlayer(seller, "Modest", 1_000_000, 4_000);
        contracts.assignToClub(modest, seller, 1, SquadRole.ROTATION);
        TransferOffer stingy = negotiation.openOffer(aListing(seller, modest), buyer,
                250_000, 100, 2);
        assertFalse(negotiation.playerWouldSign(stingy),
                "a move meeting the fee but not the wage must not be presented as agreed");
        assertNotNull(stingy.getPlayerObjection(),
                "and the objection must be explainable, not a silent failure");
    }

    @Test
    @DisplayName("a negotiation is a thread, not a replacement")
    void negotiationIsAThread() {
        inWindow();
        Team seller = aClub("ThreadSeller", 1_000_000);
        Team buyer = aClub("ThreadBuyer", 30_000_000);
        Player p = aPlayer(seller, "Threaded", 6_000_000, 18_000);
        contracts.assignToClub(p, seller, 1, SquadRole.STARTER);
        Transfer listing = aListing(seller, p);

        TransferOffer opening = negotiation.openOffer(listing, buyer, 1_000_000, 15_000, 2);
        TransferOffer counter = negotiation.counter(opening, 1_600_000, 22_000, 3);
        TransferOffer back = negotiation.counterBack(counter, 1_400_000, 20_000, 3);

        List<TransferOffer> thread = negotiation.thread(listing.getId());
        assertEquals(3, thread.size(), "every round must be visible, not overwritten");
        assertEquals(1, thread.get(0).getRound());
        assertEquals(3, thread.get(2).getRound());
        assertEquals(1_400_000, back.getFee(), 0.01);
    }

    @Test
    @DisplayName("a negotiation ends rather than continuing forever")
    void negotiationsTerminate() {
        inWindow();
        Team seller = aClub("EndlessSeller", 1_000_000);
        Team buyer = aClub("EndlessBuyer", 30_000_000);
        Player p = aPlayer(seller, "Endless", 4_000_000, 12_000);
        contracts.assignToClub(p, seller, 1, SquadRole.ROTATION);
        Transfer listing = aListing(seller, p);

        TransferOffer offer = negotiation.openOffer(listing, buyer, 500_000, 10_000, 2);
        for (int i = 0; i < NegotiationService.MAX_ROUNDS + 3; i++) {
            TransferOffer next = negotiation.counter(offer, 600_000 + i * 10_000, 11_000, 2);
            if (next == null || !next.getStatus().isLive()) { offer = next; break; }
            offer = next;
        }
        assertFalse(offer.getStatus().isLive(),
                "football does not have infinite haggling, but this loop should have stopped");
    }

    @Test
    @DisplayName("a club cannot open two live offers for the same player")
    void oneLiveOfferPerClub() {
        inWindow();
        Team seller = aClub("SingleSeller", 1_000_000);
        Team buyer = aClub("SingleBuyer", 30_000_000);
        Player p = aPlayer(seller, "Once", 3_000_000, 9_000);
        contracts.assignToClub(p, seller, 1, SquadRole.ROTATION);
        Transfer listing = aListing(seller, p);

        negotiation.openOffer(listing, buyer, 400_000, 9_000, 2);
        assertThrows(IllegalStateException.class,
                () -> negotiation.openOffer(listing, buyer, 500_000, 9_500, 2),
                "a club cannot bid twice for the same player");
    }

    @Test
    @DisplayName("the seller chooses which offer to accept, and the rest survive as a record")
    void sellerChoosesAndOtherOffersSurvive() {
        inWindow();
        Team seller = aClub("ChoosingSeller", 1_000_000);
        Team buyerA = aClub("BidderA", 30_000_000);
        Team buyerB = aClub("BidderB", 30_000_000);
        Player p = aPlayer(seller, "Auctioned", 5_000_000, 14_000);
        contracts.assignToClub(p, seller, 1, SquadRole.STARTER);
        Transfer listing = aListing(seller, p);

        TransferOffer a = negotiation.openOffer(listing, buyerA, 900_000, 14_000, 3);
        TransferOffer b = negotiation.openOffer(listing, buyerB, 1_200_000, 16_000, 3);

        // The seller takes the lower bid, because that buyer is a better fit for the squad.
        negotiation.acceptOffer(listing.getId(), a.getId());

        assertEquals(OfferStatus.ACCEPTED,
                offers.findById(a.getId()).orElseThrow().getStatus());
        assertNotNull(offers.findById(b.getId()).orElseThrow(),
                "rejecting one bid must not delete the other offer's record");
        assertEquals(OfferStatus.REJECTED,
                offers.findById(b.getId()).orElseThrow().getStatus(),
                "the other bidder is told the player has gone");
    }

    @Test
    @DisplayName("one offer can be rejected without clearing the rest")
    void rejectOneOfferOnly() {
        inWindow();
        Team seller = aClub("PickySeller", 1_000_000);
        Team buyerA = aClub("PickyA", 30_000_000);
        Team buyerB = aClub("PickyB", 30_000_000);
        Player p = aPlayer(seller, "Picky", 2_000_000, 8_000);
        contracts.assignToClub(p, seller, 1, SquadRole.ROTATION);
        Transfer listing = aListing(seller, p);

        TransferOffer a = negotiation.openOffer(listing, buyerA, 300_000, 8_000, 2);
        TransferOffer b = negotiation.openOffer(listing, buyerB, 450_000, 8_000, 2);

        negotiation.rejectOffer(a.getId());

        assertEquals(OfferStatus.REJECTED, offers.findById(a.getId()).orElseThrow().getStatus());
        assertEquals(OfferStatus.OPEN, offers.findById(b.getId()).orElseThrow().getStatus(),
                "rejecting one bid must leave the other live");
        assertEquals(1, negotiation.liveOffers(listing.getId()).size());
    }

    @Test
    @DisplayName("two clubs bidding produce two separate offers, not one merged string")
    void twoBiddersDoNotCollide() {
        inWindow();
        Team seller = aClub("SharedSeller", 1_000_000);
        // Deliberately a name-prefix pair: the old string model could not tell these apart.
        Team buyerOne = aClub("Partizan", 30_000_000);
        Team buyerTwo = aClub("Partizan United Youth", 30_000_000);
        Player p = aPlayer(seller, "Contested", 4_000_000, 12_000);
        contracts.assignToClub(p, seller, 1, SquadRole.STARTER);
        Transfer listing = aListing(seller, p);

        TransferOffer one = negotiation.openOffer(listing, buyerOne, 800_000, 12_000, 3);
        TransferOffer two = negotiation.openOffer(listing, buyerTwo, 850_000, 12_000, 3);

        assertNotNull(one.getId());
        assertNotNull(two.getId());
        assertFalse(one.getId().equals(two.getId()));
        assertEquals(2, negotiation.liveOffers(listing.getId()).size(),
                "'Partizan' must not wipe 'Partizan United Youth', which is exactly what the "
                        + "prefix-match dedupe in the old string model did");
    }

    @Test
    @DisplayName("offers expire rather than sitting open forever")
    void offersExpire() {
        inWindow();
        Team seller = aClub("StaleSeller", 1_000_000);
        Team buyer = aClub("StaleBuyer", 30_000_000);
        Player p = aPlayer(seller, "Stale", 2_000_000, 7_000);
        contracts.assignToClub(p, seller, 1, SquadRole.ROTATION);
        Transfer listing = aListing(seller, p);

        TransferOffer offer = negotiation.openOffer(listing, buyer, 200_000, 7_000, 2);
        offer.setExpiresAt(java.time.Instant.now().minusSeconds(60));
        offers.save(offer);

        assertEquals(1, negotiation.expireStale(listing.getId()));
        assertEquals(OfferStatus.EXPIRED, offers.findById(offer.getId()).orElseThrow().getStatus());
    }
}
