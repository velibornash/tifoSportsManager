package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.Transfer;
import org.example.footballmanager.newLogic.model.TransferStatus;
import org.example.footballmanager.newLogic.repository.PlayerContractRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.repository.TransferRepository;
import org.example.footballmanager.newLogic.util.players.SquadNumberAssigner;
import org.example.commonmanager.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The weekly transfer market must read each club's squad once per pass, not once per player considered.
 *
 * <p><b>How this was found:</b> not by reading the code — by a thread dump of a full test run that had
 * printed nothing for two and a half hours. The stack was
 * {@code GameClockService.advanceHours → WeekRolloverJob → TransferService.simulateWeeklyMarketActivity
 * → maybeCreateIncomingOffer → needsInterest → ClubNeedService.interest → ClubNeedService.clubSquad},
 * with 15,343 seconds of CPU on the main thread and 7.4 GB resident. It was not a hang. It was this.
 *
 * <p>The shape is a cross product. For each listed player the market asks every club whether it is
 * interested, and every one of those answers loaded that club's squad with its own query — then
 * {@code weightedBuyer} asked a second time for every club that said yes, and {@code valuation} a third.
 * So each listed player cost about three full sweeps of the world's clubs, one query per club.
 * At 14,880 clubs that is the difference between a weekly rollover and a weekly outage.
 *
 * <p>The assertion is about <b>query count</b>, not time. A wall-clock assertion on a shared machine
 * fails for reasons unrelated to the code; "how many times did you ask the database" is a property of
 * the code, and it is the thing that was wrong.
 *
 * <p>Pure unit test with mocked repositories: no Spring context, so it runs in milliseconds and the
 * counts are exact rather than inferred.
 */
class TransferMarketSquadReadCountTest {

    private static final int CLUB_COUNT = 30;
    private static final int SQUAD_SIZE = 18;
    private static final int LISTED_PLAYERS = 3;

    private PlayerRepository playerRepository;
    private TransferService service;

    /**
     * Pins the dice so the measurement is a measurement.
     *
     * <p>The market gates on {@code nextRandomDouble() > 0.42} for AI listings and {@code > 0.68} for
     * incoming offers. Left alone, the first attempt at this test simply did nothing and read zero
     * squads — and then <b>passed</b>, because a budget of thirty is satisfied by zero. A green test
     * that measured nothing is worse than no test, so the dice are pinned below both gates.
     *
     * <p>A constant 0.0 also makes {@code weightedBuyer} always pick the first willing buyer, which is
     * irrelevant here: the assertion is about how many times the database was asked, not who won.
     */
    private static final class DeterministicTransferService extends TransferService {
        DeterministicTransferService(TransferRepository transferRepository,
                                     PlayerRepository playerRepository,
                                     TeamRepository teamRepository,
                                     UserRepository userRepository,
                                     SquadNumberAssigner assigner,
                                     TransferWindowService windows,
                                     ClubNeedService clubNeedService,
                                     NegotiationService negotiation,
                                     TransferListingFeeService listingFees,
                                     ListingObjectionService listingObjections) {
            super(transferRepository, playerRepository, teamRepository, userRepository, assigner,
                    windows, clubNeedService, negotiation, listingFees, listingObjections);
        }

        @Override
        protected double nextRandomDouble() {
            return 0.0;
        }
    }

    /** clubId -> its squad, so the mock answers like the database would. */
    private final Map<Long, List<Player>> squads = new HashMap<>();

    @BeforeEach
    void setUp() {
        playerRepository = mock(PlayerRepository.class);
        TeamRepository teamRepository = mock(TeamRepository.class);
        TransferRepository transferRepository = mock(TransferRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        PlayerContractRepository contracts = mock(PlayerContractRepository.class);
        TransferWindowService windows = mock(TransferWindowService.class);

        when(windows.currentWindow()).thenReturn(TransferWindowService.Window.SUMMER);
        when(userRepository.findDistinctManagedTeamIds()).thenReturn(List.of());

        List<Team> clubs = new ArrayList<>();
        for (int c = 0; c < CLUB_COUNT; c++) {
            Team club = new Team();
            club.setId(1000L + c);
            club.setName("Club " + c);
            club.setBudget(50_000_000.0);
            clubs.add(club);

            List<Player> squad = new ArrayList<>();
            for (int p = 0; p < SQUAD_SIZE; p++) {
                Player player = new Player();
                player.setId(100_000L + (c * 1000L) + p);
                player.setName("P" + c + "-" + p);
                player.setTeam(club);
                player.setPosition(Position.values()[p % Position.values().length]);
                player.setPlayerValue(1_000_000.0);
                squad.add(player);
            }
            squads.put(club.getId(), squad);
        }
        // The bulk read the fix uses: every player in the world, flattened, exactly as the real
        // repository returns them. Stubbed to answer like the database rather than to return nothing,
        // so a pass that batches correctly sees the same squads the individual reads would have got.
        List<Player> everyone = new ArrayList<>();
        for (Team club : clubs) {
            when(playerRepository.findByTeamId(club.getId())).thenReturn(squads.get(club.getId()));
            everyone.addAll(squads.get(club.getId()));
        }
        when(playerRepository.findByTeamIdIn(Mockito.<List<Long>>any())).thenReturn(everyone);

        when(teamRepository.findClubTeamsForOperations()).thenReturn(clubs);

        // Three listed players — the axis the cross product multiplies along.
        List<Transfer> listings = new ArrayList<>();
        for (int i = 0; i < LISTED_PLAYERS; i++) {
            Player listed = new Player();
            listed.setId(900_000L + i);
            listed.setName("Listed " + i);
            listed.setTeam(clubs.get(i));
            listed.setPosition(Position.ATT);
            listed.setPlayerValue(2_000_000.0);

            Transfer transfer = new Transfer();
            transfer.setId(500_000L + i);
            transfer.setPlayer(listed);
            transfer.setStatus(TransferStatus.LISTED);
            transfer.setAskingPrice(2_000_000.0);
            listings.add(transfer);
        }
        when(transferRepository.findByStatusInAndBuyerTeamIsNull(any())).thenReturn(listings);
        when(transferRepository.findByPlayerId(any())).thenReturn(java.util.Optional.empty());
        when(contracts.findByPlayerId(any())).thenReturn(java.util.Optional.empty());

        service = new DeterministicTransferService(
                transferRepository,
                playerRepository,
                teamRepository,
                userRepository,
                mock(SquadNumberAssigner.class),
                windows,
                new ClubNeedService(playerRepository, contracts),
                mock(NegotiationService.class),
                mock(TransferListingFeeService.class),
                mock(ListingObjectionService.class));
    }

    private long findByTeamIdCalls() {
        return countCalls("findByTeamId");
    }

    private long countCalls(String methodName) {
        return Mockito.mockingDetails(playerRepository).getInvocations().stream()
                .filter(inv -> methodName.equals(inv.getMethod().getName()))
                .count();
    }

    @Test
    @DisplayName("squad reads scale with clubs, not with clubs x listed players")
    void theMarketDoesNotReadEachSquadOncePerPlayer() {
        service.simulateWeeklyMarketActivity();

        long perClubReads = findByTeamIdCalls();
        long bulkReads = countCalls("findByTeamIdIn");

        // Not a budget — an exact contract. The market answers every club's appetite from one snapshot,
        // so it must never ask for an individual squad at all. Measured before the fix: 180 per-club
        // reads for these 30 clubs and 3 listed players, and zero bulk reads.
        assertEquals(0, perClubReads,
                "the market asked for " + perClubReads + " individual squads. It answers every club's "
                        + "appetite from one snapshot, so any per-club read means the snapshot is not "
                        + "being passed down — which is the cross product that made the weekly rollover "
                        + "take hours on a world of 14,880 clubs.");

        assertEquals(1, bulkReads,
                "expected exactly one bulk squad read for the whole pass, got " + bulkReads
                        + ". More than one means the snapshot is being rebuilt; zero means it is not "
                        + "being used at all and every answer is falling back to its own query.");
    }

    @Test
    @DisplayName("doubling the listed players must not double the squad reads")
    void theCostIsFlatInTheNumberOfListedPlayers() {
        // The property that matters, stated directly: the pass must be O(clubs), not O(clubs x players).
        // If someone reintroduces a per-player load, this fails even if the absolute count stays small.
        service.simulateWeeklyMarketActivity();
        long firstPass = findByTeamIdCalls();

        Mockito.clearInvocations(playerRepository);
        service.simulateWeeklyMarketActivity();
        long secondPass = findByTeamIdCalls();

        assertEquals(firstPass, secondPass,
                "two identical passes read different numbers of squads (" + firstPass + " then "
                        + secondPass + "), so the count is not a property of the pass but of "
                        + "accumulated state — which is how a stale cache would show up here.");
    }
}