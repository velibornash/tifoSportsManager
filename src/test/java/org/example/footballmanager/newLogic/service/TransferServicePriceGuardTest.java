package org.example.footballmanager.newLogic.service;

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
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 0.1 — transfer price guard.
 *
 * <p>Regression tests for the EUR 1 exploit: {@code normalizePrice} used to be
 * {@code Math.max(1.0, requested)} with no lower bound against the asking price, so any
 * listed player could be bought for EUR 1. The price logic now lives in a single choke
 * point, {@code resolveAgreedPrice}, plus a defence-in-depth floor inside
 * {@code completeTransfer}.
 *
 * <p>These are pure unit tests with mocked repositories — no Spring context, no database.
 */
class TransferServicePriceGuardTest {

    private static final long PLAYER_ID = 500L;
    private static final long SELLER_ID = 10L;
    private static final long BUYER_ID = 20L;
    private static final double ASKING = 1_000_000.0;

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
        buyer.setName("Buying FC");
        buyer.setBudget(50_000_000.0);

        player = new Player();
        player.setId(PLAYER_ID);
        player.setName("Target Player");
        player.setTeam(seller);
        player.setPlayerValue(ASKING);

        listing = new Transfer();
        listing.setId(90L);
        listing.setPlayer(player);
        listing.setSellerTeam(seller);
        listing.setAskingPrice(ASKING);
        listing.setStatus(TransferStatus.LISTED);

        when(transferRepository.findByPlayerId(PLAYER_ID)).thenReturn(Optional.of(listing));
        when(playerRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(teamRepository.findById(BUYER_ID)).thenReturn(Optional.of(buyer));
        when(teamRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transferRepository.save(any(Transfer.class))).thenAnswer(inv -> inv.getArgument(0));
        when(playerRepository.save(any(Player.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private ApiException capture(Runnable action) {
        return assertThrows(ApiException.class, action::run);
    }

    // ------------------------------------------------------------ the actual exploit

    @Test
    @DisplayName("S0.1: buying a listed player for EUR 1 is rejected")
    void buyListedPlayerRejectsPriceBelowAsking() {
        ApiException ex = capture(() -> service.buyListedPlayer(PLAYER_ID, BUYER_ID, 1.0));

        assertEquals("PRICE_BELOW_ASKING", ex.getCode());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
    }

    @Test
    @DisplayName("S0.1: the exploit does not move any money")
    void buyListedPlayerBelowAskingMovesNoMoney() {
        double buyerBefore = buyer.getBudget();
        double sellerBefore = seller.getBudget();

        capture(() -> service.buyListedPlayer(PLAYER_ID, BUYER_ID, 1.0));

        assertEquals(buyerBefore, buyer.getBudget(), 0.01, "buyer budget must be untouched");
        assertEquals(sellerBefore, seller.getBudget(), 0.01, "seller budget must be untouched");
        assertEquals(seller, player.getTeam(), "player must not have changed club");
        assertEquals(TransferStatus.LISTED, listing.getStatus(), "listing must stay active");
        verify(teamRepository, never()).saveAll(any());
        verify(playerRepository, never()).save(any(Player.class));
    }

    @Test
    @DisplayName("S0.1: direct-buy below the asking price is rejected too")
    void directBuyRejectsPriceBelowAsking() {
        ApiException ex = capture(() -> service.directBuyPlayer(PLAYER_ID, BUYER_ID, 1.0));

        assertEquals("PRICE_BELOW_ASKING", ex.getCode());
        assertEquals(seller, player.getTeam());
    }

    // ------------------------------------------------------------ boundary behaviour

    @Test
    @DisplayName("S0.1: exactly the asking price is accepted")
    void buyListedPlayerAcceptsExactAskingPrice() {
        service.buyListedPlayer(PLAYER_ID, BUYER_ID, ASKING);

        assertEquals(buyer, player.getTeam());
        assertEquals(ASKING, listing.getAgreedPrice(), 0.01);
        assertEquals(50_000_000.0 - ASKING, buyer.getBudget(), 0.01);
        assertEquals(ASKING, seller.getBudget(), 0.01);
    }

    @Test
    @DisplayName("S0.1: overpaying above the asking price is allowed (seller's gain)")
    void buyListedPlayerAcceptsAboveAskingPrice() {
        double over = ASKING * 1.5;

        service.buyListedPlayer(PLAYER_ID, BUYER_ID, over);

        assertEquals(buyer, player.getTeam());
        assertEquals(over, listing.getAgreedPrice(), 0.01);
        assertEquals(50_000_000.0 - over, buyer.getBudget(), 0.01);
    }

    @Test
    @DisplayName("S0.1: omitting the price means 'accept the asking price'")
    void buyListedPlayerWithNullPriceUsesAskingPrice() {
        service.buyListedPlayer(PLAYER_ID, BUYER_ID, null);

        assertEquals(ASKING, listing.getAgreedPrice(), 0.01);
        assertEquals(ASKING, seller.getBudget(), 0.01);
    }

    // ------------------------------------------------------------ invalid input

    @Test
    @DisplayName("S0.1: zero and negative offers are rejected as INVALID_PRICE")
    void nonPositivePriceRejected() {
        assertEquals("INVALID_PRICE", capture(() -> service.buyListedPlayer(PLAYER_ID, BUYER_ID, 0.0)).getCode());
        assertEquals("INVALID_PRICE", capture(() -> service.buyListedPlayer(PLAYER_ID, BUYER_ID, -500.0)).getCode());
    }

    @Test
    @DisplayName("S0.1: NaN and Infinity cannot bypass the guard")
    void nonFinitePriceRejected() {
        assertEquals("INVALID_PRICE", capture(() -> service.buyListedPlayer(PLAYER_ID, BUYER_ID, Double.NaN)).getCode());
        assertEquals("INVALID_PRICE", capture(() -> service.buyListedPlayer(PLAYER_ID, BUYER_ID, Double.POSITIVE_INFINITY)).getCode());
        assertEquals(seller, player.getTeam());
    }

    // ------------------------------------------------------------ budget still enforced

    @Test
    @DisplayName("S0.1: a valid price the club cannot afford is still INSUFFICIENT_BUDGET")
    void insufficientBudgetStillRejected() {
        buyer.setBudget(1000.0);

        ApiException ex = capture(() -> service.buyListedPlayer(PLAYER_ID, BUYER_ID, ASKING));

        assertEquals("INSUFFICIENT_BUDGET", ex.getCode());
        assertEquals(seller, player.getTeam());
    }

    @Test
    @DisplayName("S0.1: guard error message names both prices so the UI can show it")
    void guardErrorMessageIsActionable() {
        ApiException ex = capture(() -> service.buyListedPlayer(PLAYER_ID, BUYER_ID, 1.0));

        assertTrue(ex.getMessage().contains("1"), "should echo the offered price");
        assertTrue(ex.getMessage().contains("1000000"), "should echo the asking price");
    }
}
