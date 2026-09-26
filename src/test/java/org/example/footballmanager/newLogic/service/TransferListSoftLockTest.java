package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.dto.transfer.PlayerTransferStatusDTO;
import org.example.footballmanager.newLogic.dto.transfer.TransferDTO;
import org.example.footballmanager.newLogic.exception.ApiException;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.Transfer;
import org.example.footballmanager.newLogic.model.TransferStatus;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.repository.PlayerContractRepository;
import org.example.footballmanager.newLogic.repository.TransferRepository;
import org.example.footballmanager.newLogic.util.players.SquadNumberAssigner;
import org.example.commonmanager.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sprint 0.2 — transfer-list soft-lock.
 *
 * <p>The bug: a bare "register interest" entry (a club name with no price) set
 * {@code canRemove} to false, but it was not a priced offer, so {@code canRejectOffer} also
 * stayed false. The result was that once any club registered interest, the player could never
 * be delisted again — no seller action and no admin path could clear it. The UI rendered a
 * permanently disabled button.
 *
 * <p>The fix distinguishes a priced offer from bare interest in three places: the removal guard,
 * the DTO flags, and a new explicit "clear interest" action.
 */
class TransferListSoftLockTest {

    private static final long PLAYER_ID = 700L;
    private static final long SELLER_ID = 11L;
    private static final long BUYER_ID = 21L;
    private static final double ASKING = 750_000.0;

    private TransferRepository transferRepository;
    private PlayerRepository playerRepository;
    private TeamRepository teamRepository;
    private TransferService service;

    private Team seller;
    private Team buyer;
    private Player player;
    private Transfer listing;

    @BeforeEach
    void setUp() {
        transferRepository = mock(TransferRepository.class);
        playerRepository = mock(PlayerRepository.class);
        teamRepository = mock(TeamRepository.class);

        service = new TransferService(
                transferRepository,
                playerRepository,
                teamRepository,
                mock(UserRepository.class),
                mock(SquadNumberAssigner.class),
                mock(TransferWindowService.class),
                new ClubNeedService(playerRepository, mock(PlayerContractRepository.class))
        );

        seller = new Team();
        seller.setId(SELLER_ID);
        seller.setName("Selling FC");
        seller.setBudget(0.0);

        buyer = new Team();
        buyer.setId(BUYER_ID);
        buyer.setName("Rival FC");
        buyer.setBudget(20_000_000.0);

        player = new Player();
        player.setId(PLAYER_ID);
        player.setName("Stuck Player");
        player.setTeam(seller);
        player.setPlayerValue(ASKING);

        listing = new Transfer();
        listing.setId(91L);
        listing.setPlayer(player);
        listing.setSellerTeam(seller);
        listing.setAskingPrice(ASKING);
        listing.setStatus(TransferStatus.LISTED);

        when(transferRepository.findByPlayerId(PLAYER_ID)).thenReturn(Optional.of(listing));
        when(playerRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(teamRepository.findById(BUYER_ID)).thenReturn(Optional.of(buyer));
        when(teamRepository.findById(SELLER_ID)).thenReturn(Optional.of(seller));
        when(transferRepository.save(any(Transfer.class))).thenAnswer(inv -> inv.getArgument(0));
        when(playerRepository.save(any(Player.class))).thenAnswer(inv -> inv.getArgument(0));
        when(teamRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ------------------------------------------------------------ the soft-lock itself

    @Test
    @DisplayName("S0.2: bare registered interest no longer blocks delisting")
    void bareInterestDoesNotBlockDelisting() {
        service.addInterest(PLAYER_ID, BUYER_ID, null);
        assertTrue(listing.getInterestedTeams().contains("Rival FC"), "precondition: bare interest registered");

        service.removeFromTransferList(PLAYER_ID, SELLER_ID);

        assertEquals(TransferStatus.CANCELLED, listing.getStatus());
        assertTrue(listing.getInterestedTeams().isEmpty(), "stale interest must be cleared on delist");
    }

    @Test
    @DisplayName("S0.2: DTO reports canRemove=true for bare interest (UI button stays enabled)")
    void dtoAllowsRemoveWithBareInterest() {
        service.addInterest(PLAYER_ID, BUYER_ID, null);

        PlayerTransferStatusDTO dto = service.getPlayerTransferStatus(PLAYER_ID, SELLER_ID);

        assertTrue(dto.isCanRemove(), "canRemove must be true - bare interest is not an offer");
        assertFalse(dto.isHasPricedOffer(), "hasPricedOffer must be false");
        assertTrue(dto.isCanClearInterest(), "seller must still get a clear-interest escape hatch");
    }

    @Test
    @DisplayName("S0.2: a live priced offer DOES still block delisting")
    void pricedOfferBlocksDelisting() {
        listing.getInterestedTeams().add("Rival FC offered €" + Math.round(ASKING * 1.2));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.removeFromTransferList(PLAYER_ID, SELLER_ID));

        assertEquals("ACTIVE_OFFER", ex.getCode());
        assertEquals(TransferStatus.LISTED, listing.getStatus());
    }

    // ------------------------------------------------------------ escape hatches

    @Test
    @DisplayName("S0.2: interested club can withdraw its own interest")
    void interestedClubCanWithdraw() {
        service.addInterest(PLAYER_ID, BUYER_ID, null);
        assertEquals(1, listing.getInterestedTeams().size());

        service.withdrawInterest(PLAYER_ID, BUYER_ID, null);

        assertTrue(listing.getInterestedTeams().isEmpty());
    }

    @Test
    @DisplayName("S0.2: withdrawing a priced offer entry also works (parsed from the string)")
    void withdrawRemovesPricedOfferEntry() {
        listing.getInterestedTeams().add("Rival FC offered €900000");

        service.withdrawInterest(PLAYER_ID, BUYER_ID, null);

        assertTrue(listing.getInterestedTeams().isEmpty());
    }

    @Test
    @DisplayName("S0.2: withdrawing when no interest exists is a clear 409, not a silent no-op")
    void withdrawWithoutInterestIsRejected() {
        ApiException ex = assertThrows(ApiException.class,
                () -> service.withdrawInterest(PLAYER_ID, BUYER_ID, null));

        assertEquals("NO_INTEREST_FOUND", ex.getCode());
    }

    @Test
    @DisplayName("S0.2: seller can clear all interest without accepting any offer")
    void sellerCanClearAllInterest() {
        listing.getInterestedTeams().add("Rival FC");
        listing.getInterestedTeams().add("Other FC offered €800000");

        TransferDTO dto = service.clearAllInterest(PLAYER_ID, SELLER_ID);

        assertTrue(listing.getInterestedTeams().isEmpty());
        assertEquals(TransferStatus.LISTED, listing.getStatus(), "player must STAY listed after clearing");
        assertEquals(0.0, seller.getBudget(), "no money may move when clearing");
        assertEquals(20_000_000.0, buyer.getBudget(), 0.01, "buyer budget untouched");
        assertTrue(dto.getActionMessage().contains("2"), "should report how many entries were cleared");
    }

    @Test
    @DisplayName("S0.2: a non-owner cannot clear someone else's interest")
    void nonOwnerCannotClear() {
        listing.getInterestedTeams().add("Rival FC");

        ApiException ex = assertThrows(ApiException.class,
                () -> service.clearAllInterest(PLAYER_ID, BUYER_ID));

        assertEquals("FORBIDDEN", ex.getCode());
        assertEquals(1, listing.getInterestedTeams().size());
    }

    @Test
    @DisplayName("S0.2: admin force-unlist works even with a live priced offer")
    void adminForceUnlistBeatsEverything() {
        listing.getInterestedTeams().add("Rival FC offered €5000000");

        TransferDTO dto = service.forceUnlist(PLAYER_ID);

        assertEquals(TransferStatus.CANCELLED, listing.getStatus());
        assertTrue(listing.getInterestedTeams().isEmpty());
        assertTrue(dto.getActionMessage().contains("admin"));
    }

    @Test
    @DisplayName("S0.2: a club cannot register interest in its own player")
    void cannotRegisterInterestInOwnPlayer() {
        ApiException ex = assertThrows(ApiException.class,
                () -> service.addInterest(PLAYER_ID, SELLER_ID, null));

        assertEquals("INVALID_TRANSFER", ex.getCode());
    }
}
