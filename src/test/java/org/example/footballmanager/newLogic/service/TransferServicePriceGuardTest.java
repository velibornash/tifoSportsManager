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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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

    /**
     * The single settlement path. Stubbed to succeed, because what these tests are about is the
     * price guard in front of it - and a price guard is only worth testing if the thing behind it
     * is a collaborator, not a second implementation of the same logic.
     */
    private final NegotiationService negotiation = mock(NegotiationService.class);

    private SquadRegistrationService registration;
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
        // Real, not a mock: this class is about the price floor, and a mocked squad rule would let a
        // test pass on a transfer the product would refuse. A 30-player club answers "yes" for free.
        registration = new SquadRegistrationService(playerRepository);

        service = new TransferService(
                transferRepository,
                playerRepository,
                teamRepository,
                mock(UserRepository.class),
                mock(SquadNumberAssigner.class),
                mock(TransferWindowService.class),
                new ClubNeedService(playerRepository, mock(PlayerContractRepository.class)),
                negotiation,
                mock(TransferListingFeeService.class),
                mock(ListingObjectionService.class),
                registration
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

        when(negotiation.settle(any(), any(), any(Double.class), any(Double.class), any()))
                .thenReturn(true);

        when(transferRepository.findById(90L)).thenReturn(Optional.of(listing));
        when(transferRepository.findByPlayerId(PLAYER_ID)).thenReturn(Optional.of(listing));
        when(playerRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(teamRepository.findById(BUYER_ID)).thenReturn(Optional.of(buyer));
        when(teamRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transferRepository.save(any(Transfer.class))).thenAnswer(inv -> inv.getArgument(0));
        when(playerRepository.save(any(Player.class))).thenAnswer(inv -> inv.getArgument(0));
        // Default: a buyer with room. Every price test below needs to reach the price logic rather
        // than stop at the squad limit, and a buyer at 30 players would mask the thing under test.
        when(playerRepository.findByTeamId(BUYER_ID)).thenReturn(java.util.List.of());
    }

    // ------------------------------------------------------------ the squad limit on this path

    /**
     * The path that moves players between clubs never checked squad size at all (owner, 2026-10-08).
     *
     * <p>The 25-player cap lived in {@code PlayerContractService} and was consulted only by
     * {@code sign()}. Buying from the transfer list — the main way a manager adds anybody — read the
     * budget and the price and nothing else, so a club could buy its way to fifty players. This is the
     * test that would have caught it, and it is a pure unit test against the same collaborators the
     * price tests use.
     */
    @Test
    @DisplayName("S0.2: a club with 30 players cannot buy another one")
    void aFullClubCannotBuy() {
        when(playerRepository.findByTeamId(BUYER_ID)).thenReturn(fullSquad());

        ApiException ex = capture(() -> service.buyListedPlayer(PLAYER_ID, BUYER_ID, ASKING));

        assertEquals("SQUAD_FULL", ex.getCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertTrue(ex.getMessage().contains("30"),
                "the message must state the limit, got: " + ex.getMessage());
    }

    /** And it must refuse <b>before</b> anything moves: the player stays where he is. */
    @Test
    @DisplayName("S0.2: a refused transfer settles nothing")
    void aRefusedTransferSettlesNothing() {
        when(playerRepository.findByTeamId(BUYER_ID)).thenReturn(fullSquad());

        capture(() -> service.buyListedPlayer(PLAYER_ID, BUYER_ID, ASKING));

        verify(negotiation, never()).settle(any(), any(), any(Double.class), any(Double.class), any());
    }

    /** One place short is not one too many: the same purchase goes through. */
    @Test
    @DisplayName("S0.2: one place short of the limit, the purchase goes through")
    void onePlaceShortStillBuys() {
        when(playerRepository.findByTeamId(BUYER_ID)).thenReturn(fullSquad(1));

        service.buyListedPlayer(PLAYER_ID, BUYER_ID, ASKING);

        verify(negotiation).settle(eq(90L), any(), eq(ASKING), any(Double.class), any());
    }

    /**
     * A player on the transfer list still occupies a place until he is bought.
     *
     * <p>Counting contracts, the old rule could not see a listed player at all, because a player made
     * from an academy junior has no contract until the next season's backfill.
     */
    @Test
    @DisplayName("S0.2: the count is players, so a listed player occupies a place")
    void theCountIsPlayersNotContracts() {
        assertEquals(SquadRegistrationService.MAX_CLUB_SQUAD, fullSquad().size(),
                "the fixture is exactly at the limit");
        assertEquals(30, SquadRegistrationService.MAX_CLUB_SQUAD,
                "owner, 2026-10-08: thirty players, one bucket for seniors and youth alike");
    }

    private java.util.List<Player> fullSquad() {
        return fullSquad(0);
    }

    private java.util.List<Player> fullSquad(int shortBy) {
        java.util.List<Player> squad = new java.util.ArrayList<>();
        for (int i = 0; i < SquadRegistrationService.MAX_CLUB_SQUAD - shortBy; i++) {
            Player p = new Player();
            p.setId(9000L + i);
            p.setName("Squad " + i);
            squad.add(p);
        }
        return squad;
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

        // The guard's job is to hand the right price to the settlement, not to perform it: there is
        // one settlement path now, and TransferCompletionTest exercises it against a real database.
        verify(negotiation).settle(eq(90L), eq(buyer), eq(ASKING), any(Double.class), any());
    }

    @Test
    @DisplayName("S0.1: overpaying above the asking price is allowed (seller's gain)")
    void buyListedPlayerAcceptsAboveAskingPrice() {
        double over = ASKING * 1.5;

        service.buyListedPlayer(PLAYER_ID, BUYER_ID, over);

        // Overpaying is allowed - it is the seller's gain - so the guard must not clamp it down.
        verify(negotiation).settle(eq(90L), eq(buyer), eq(over), any(Double.class), any());
    }

    @Test
    @DisplayName("S0.1: omitting the price means 'accept the asking price'")
    void buyListedPlayerWithNullPriceUsesAskingPrice() {
        service.buyListedPlayer(PLAYER_ID, BUYER_ID, null);

        // Omitting the price means "I accept the asking price", so that is what gets settled.
        verify(negotiation).settle(eq(90L), eq(buyer), eq(ASKING), any(Double.class), any());
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
