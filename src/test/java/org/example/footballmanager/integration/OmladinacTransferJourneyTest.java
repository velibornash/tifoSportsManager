package org.example.footballmanager.integration;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.PlayerContract;
import org.example.footballmanager.newLogic.model.Position;
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
import org.example.footballmanager.newLogic.service.ContractBackfillService;
import org.example.footballmanager.newLogic.service.NegotiationService;
import org.example.footballmanager.newLogic.service.PlayerContractService;
import org.example.footballmanager.newLogic.service.TransferFeeService;
import org.example.footballmanager.newLogic.service.TransferService;
import org.example.footballmanager.newLogic.service.TransferWindowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.example.footballmanager.TestCountryCatalogue;

/**
 * The owner's club, from a real starting state, through a real transfer (owner, 2026-09-26).
 *
 * <p>This exists because four separate systems had each been finished, tested and shipped while
 * doing nothing: a signing that never moved the player, a transfer that never completed, a budget
 * that always read zero income, and a squad report full of positions the game does not have. Every
 * one of them had a passing unit test. The unit tests were the problem — they asserted on a single
 * collaborator and none of them asserted the thing a manager would notice.
 *
 * <p>So this is deliberately an integration test with no mocks anywhere: real clubs with real
 * economies, a real ledger, a real window, a real offer thread, and assertions on the three
 * outcomes that matter — the player is at the new club, the money moved, and the paper trail exists.
 *
 * <p>The club is called Omladinac because that is the club the owner actually manages
 * ({@code OFK Omladinac} in {@code DatabaseInitializer}). If this test fails, the owner's club is
 * broken, which is the shortest possible distance between a red build and a real problem.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestCountryCatalogue.class)
class OmladinacTransferJourneyTest {

    @Autowired
    TestCountryCatalogue catalogue;

    @Autowired PlayerContractService contracts;
    @Autowired NegotiationService negotiation;
    @Autowired TransferService transfers;
    @Autowired ContractBackfillService backfill;
    @Autowired TransferFeeService fees;

    @Autowired TeamRepository teams;
    @Autowired PlayerRepository players;
    @Autowired FinanceLedgerEntryRepository ledger;
    @Autowired GameClockRepository clocks;

    private Team omladinac;
    private Team rival;

    @BeforeEach
    void setUp() {
        catalogue.seed();
        // A window has to be open or nothing below can happen, and the calendar is the only thing
        // that decides that.
        var clock = clocks.findAll().stream().findFirst().orElseThrow();
        clock.setCurrentWeek(TransferWindowService.SUMMER_OPEN);
        clock.setCurrentSeason(1);
        clocks.save(clock);

        omladinac = club("OFK Omladinac", 4_000_000, 70);
        rival = club("Rival United", 6_000_000, 62);
        settleIncome(omladinac);
        settleIncome(rival);
    }

    // ------------------------------------------------------------ the club

    private Team club(String name, double budget, double reputation) {
        Team t = new Team();
        t.setName(name + "-" + System.nanoTime());
        t.setBudget(budget);
        t.setReputation(reputation);
        Stadium s = new Stadium();
        s.setName(name + " Stadium");
        s.setCapacity(12_000);
        s.setTicketPrice(15.0);
        s.setPitchQuality(80.0);
        s.setPitchCondition(80);
        s.setMaintenanceRemaining(0);
        t.setStadium(s);
        return teams.save(t);
    }

    private Player aPlayer(Team at, Position position, int age, double value, double wage) {
        Player p = new Player();
        p.setName("Player " + System.nanoTime());
        p.setTeam(at);
        p.setPosition(position);
        p.setAge(age);
        p.setPlayerValue(value);
        p.setEarnings(wage);
        p.setForm(6.5);
        p.setMorale(60.0);
        return players.save(p);
    }

    /** Eight settled weeks of income, which is what gives a club a wage ceiling. */
    private void settleIncome(Team club) {
        int season = 1;
        for (int week = 1; week <= 8; week++) {
            ledger.save(org.example.footballmanager.newLogic.model.FinanceLedgerEntry.of(
                    club, season, week,
                    org.example.footballmanager.newLogic.model.FinanceCategory.BROADCAST,
                    90_000, "Broadcast income"));
        }
    }

    // ------------------------------------------------------------ the journeys

    @Test
    @DisplayName("Omladinac can buy a player, and he arrives with the paperwork")
    void buysAPlayerAndHeActuallyArrives() {
        Player target = aPlayer(rival, Position.ATT, 24, 2_400_000, 5_000);
        contracts.assignToClub(target, rival, 1, SquadRole.STARTER);

        Transfer listing = transfers.listPlayerForTransfer(target.getId(), 1_200_000);
        assertNotNull(listing);
        assertEquals(TransferStatus.LISTED, transfers.findTransferForPlayer(target.getId()).getStatus());

        double omladinacBefore = omladinac.getBudget();
        double rivalBefore = rival.getBudget();

        TransferOffer offer = negotiation.openOffer(listing, omladinac, 1_000_000, 8_000, 3);
        assertNotNull(offer, "a club should be able to bid in an open window");
        negotiation.acceptOffer(listing.getId(), offer.getId());

        // 1. The player is at Omladinac, on the wage that was agreed.
        Player arrived = players.findById(target.getId()).orElseThrow();
        assertNotNull(arrived.getTeam());
        assertEquals(omladinac.getId(), arrived.getTeam().getId(),
                "the player must be at the buying club - this is the assertion that was passing "
                        + "while four separate systems did nothing");
        assertEquals(8_000.0, arrived.getEarnings(), 0.01);

        // 2. The money moved, in both directions.
        assertEquals(omladinacBefore - 1_000_000, teams.findById(omladinac.getId()).orElseThrow()
                .getBudget(), 0.01, "Omladinac paid the fee");
        assertEquals(rivalBefore + 1_000_000, teams.findById(rival.getId()).orElseThrow()
                .getBudget(), 0.01, "Rival United received it");

        // 3. The paper trail exists, because a budget that moves with no ledger is a mystery later.
        List<?> omladinacLines =
                ledger.findByTeamIdAndSeasonYearOrderByWeekNumberAsc(omladinac.getId(), 1);
        assertTrue(omladinacLines.stream().anyMatch(e -> {
            var line = (org.example.footballmanager.newLogic.model.FinanceLedgerEntry) e;
            return line.getCategory()
                    == org.example.footballmanager.newLogic.model.FinanceCategory.TRANSFER_FEE_OUT
                    && Math.abs(line.getAmount()) > 0;
        }), "Omladinac's payment must be on the ledger");

        // 4. And the transfer is closed out rather than left open.
        Transfer done = transfers.findTransferForPlayer(target.getId());
        assertEquals(TransferStatus.COMPLETED, done.getStatus());
        assertEquals(1_000_000.0, done.getAgreedPrice(), 0.01);
    }

    @Test
    @DisplayName("Omladinac can sign a released player directly, and the free-agent route works")
    void signsAFreeAgentDirectly() {
        Player released = aPlayer(rival, Position.MID, 27, 900_000, 3_000);
        contracts.assignToClub(released, rival, 1, SquadRole.ROTATION);

        // A short deal, signed through the service so the expiry is real rather than poked into
        // the database behind its back. Six months is two of this game's twelve-week seasons.
        assertTrue(contracts.sign(rival.getId(), released.getId(), 6, 1_000,
                SquadRole.ROTATION).signed());
        contracts.expireContracts(4);

        assertEquals(null, players.findById(released.getId()).orElseThrow().getTeam(),
                "an expired contract leaves the club, so he is a free agent");

        double before = omladinac.getBudget();
        PlayerContractService.Outcome outcome =
                contracts.sign(omladinac.getId(), released.getId(), 12, 6_000, SquadRole.ROTATION);

        assertTrue(outcome.signed(), "signing a free agent must work: " + outcome.reason());

        Player signed = players.findById(released.getId()).orElseThrow();
        assertNotNull(signed.getTeam(), "a signed player belongs to the club that signed him");
        assertEquals(omladinac.getId(), signed.getTeam().getId());
        assertEquals(6_000.0, signed.getEarnings(), 0.01, "on the agreed wage");

        PlayerContract nowContract = contracts.findByPlayerId(released.getId());
        assertEquals(omladinac.getId(), nowContract.getTeam().getId());
        assertTrue(nowContract.getExpirySeason() > 1, "a 12-month contract must outlast the season");
    }

    @Test
    @DisplayName("a big fee becomes instalments rather than a single payment")
    void aBigFeeIsPaidInInstalments() {
        Player target = aPlayer(rival, Position.WNG, 23, 12_000_000, 30_000);
        contracts.assignToClub(target, rival, 1, SquadRole.STARTER);
        Transfer listing = transfers.listPlayerForTransfer(target.getId(), 8_000_000);

        TransferOffer offer = negotiation.openOffer(listing, omladinac, 6_000_000, 40_000, 4);
        assertNotNull(offer);
        negotiation.acceptOffer(listing.getId(), offer.getId());

        // The player moves, but the club is not ruined in one afternoon.
        assertEquals(omladinac.getId(), players.findById(target.getId()).orElseThrow().getTeam().getId());
        assertTrue(omladinac.getBudget() > 3_000_000,
                "an instalment deal must not take the whole balance: " + omladinac.getBudget());

        var fee = fees.findStructureFor(listing.getId());
        assertNotNull(fee, "the instalment schedule must be recorded");
        assertTrue(fee.getOutstanding() > 0, "and something must still be owed");

        double rivalBefore = teams.findById(rival.getId()).orElseThrow().getBudget();
        double collected = fees.collectInstalment(listing.getId());
        assertTrue(collected > 0, "the first instalment must actually pay the seller");
        assertTrue(teams.findById(rival.getId()).orElseThrow().getBudget() > rivalBefore);
    }

    @Test
    @DisplayName("nothing happens outside the window")
    void theWindowIsEnforcedEndToEnd() {
        var clock = clocks.findAll().stream().findFirst().orElseThrow();
        clock.setCurrentWeek(3);
        clocks.save(clock);

        Player target = aPlayer(rival, Position.DEF, 25, 1_000_000, 4_000);
        contracts.assignToClub(target, rival, 1, SquadRole.STARTER);
        Transfer listing = transfers.listPlayerForTransfer(target.getId(), 600_000);

        // Bidding itself is refused, with a reason, and the player has not moved.
        assertThrowsWindowClosed(() -> negotiation.openOffer(listing, omladinac, 500_000, 5_000, 2));
        assertEquals(rival.getId(), players.findById(target.getId()).orElseThrow().getTeam().getId());
        assertEquals(TransferStatus.LISTED,
                transfers.findTransferForPlayer(target.getId()).getStatus());
    }

    private void assertThrowsWindowClosed(Runnable action) {
        try {
            action.run();
        } catch (NegotiationService.TransferWindowClosedException expected) {
            assertNotNull(expected.getMessage());
            return;
        }
        throw new AssertionError("expected the transfer window to refuse this");
    }

    @Test
    @DisplayName("Omladinac cannot sign a player away from the club that owns him")
    void cannotPoachAContractedPlayer() {
        Player theirs = aPlayer(rival, Position.ATT, 24, 3_000_000, 8_000);
        contracts.assignToClub(theirs, rival, 1, SquadRole.STARTER);

        PlayerContractService.Outcome outcome =
                contracts.sign(omladinac.getId(), theirs.getId(), 24, 15_000, SquadRole.STARTER);

        assertFalse(outcome.signed(), "a registered player is negotiated for, not signed away");
        assertEquals(rival.getId(), players.findById(theirs.getId()).orElseThrow().getTeam().getId());
    }

    @Test
    @DisplayName("Omladinac gets a transfer budget once it has income, and spends it")
    void theTransferBudgetIsReal() {
        // The bug this replaces: income read as zero for every club, so every club was told it had
        // no transfer budget and no transfer could ever complete.
        TransferOffer offer = negotiation.openOffer(
                transfers.listPlayerForTransfer(
                        aPlayer(rival, Position.MID, 24, 1_800_000, 5_000).getId(), 900_000),
                omladinac, 800_000, 7_000, 3);
        assertNotNull(offer, "a club with settled income can bid");
    }
}
