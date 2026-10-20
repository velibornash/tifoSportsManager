package org.tifo.footballmanager.newLogic.service;

import org.tifo.footballmanager.newLogic.model.Country;
import org.tifo.footballmanager.newLogic.model.Player;
import org.tifo.footballmanager.newLogic.model.Position;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.repository.LoanRepository;
import org.tifo.footballmanager.newLogic.repository.PlayerContractRepository;
import org.tifo.footballmanager.newLogic.repository.PlayerRepository;
import org.tifo.footballmanager.newLogic.repository.TeamRepository;
import org.tifo.footballmanager.newLogic.repository.TransferRepository;
import org.tifo.footballmanager.newLogic.util.players.SquadNumberAssigner;
import org.tifo.commonmanager.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The transfer market's scout list must not cost one query per club in the world.
 *
 * <p><b>Where the number came from (owner, 2026-10-16):</b> "the transfers page opens after ten
 * seconds". Measured on the running application, the same request against the same database on the
 * same server took <b>11.70 s / 11.62 s</b> before the fix and <b>0.61 s</b> after.
 * {@code scoutedUnlisted} was asking {@code playerRepository.findByTeamId(...)} once per club in the
 * world and only then discarding the clubs of other countries — 14,880 queries to keep one country's
 * players — and then asking {@code transferRepository.findByPlayerId(...)} once per player it had
 * kept, before the limit of sixty.
 *
 * <p><b>Why the assertions are query counts and not timings:</b> a wall-clock assertion on a shared
 * machine fails for reasons that have nothing to do with the code. "How many times did you ask the
 * database" is a property of the code, and it is the property that was wrong. Same reasoning as
 * {@link TransferMarketSquadReadCountTest}, which guards the weekly rollover.
 *
 * <p>Pure unit test over mocked repositories: no Spring context, so it runs in milliseconds and the
 * counts are exact. The mocks answer like the database rather than returning nothing — a bulk squad
 * read returns the players of the clubs it was asked about — because a stub that answers wrongly
 * makes a correct service look broken, which is the failure mode this repository has hit before.
 */
class TransferScoutReadCountTest {

    private static final String VIEWER_COUNTRY = "SRB";
    private static final Long VIEWER_TEAM_ID = 1L;
    /** Two countries' worth of clubs, so a filter that never asks the database can be seen to fail. */
    private static final int CLUBS_PER_COUNTRY = 40;
    private static final int SQUAD_SIZE = 12;
    private static final int LISTED_COUNT = 3;

    private PlayerRepository playerRepository;
    private TeamRepository teamRepository;
    private TransferRepository transferRepository;
    private TransferService service;

    /** Every club the database holds: the viewer's own, Serbia's rivals, and Croatia's. */
    private final List<Team> allClubs = new ArrayList<>();
    private final List<Player> allPlayers = new ArrayList<>();
    /** The player ids that are on the market right now and must not be offered as scout rows. */
    private final List<Long> listedPlayerIds = List.of(900_001L, 900_002L, 900_003L);
    /** How many times the bulk squad read has been asked. See the stub below. */
    private int bulkReads;

    @BeforeEach
    void setUp() {
        playerRepository = mock(PlayerRepository.class);
        teamRepository = mock(TeamRepository.class);
        transferRepository = mock(TransferRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        PlayerContractRepository contracts = mock(PlayerContractRepository.class);
        TransferWindowService windows = mock(TransferWindowService.class);
        when(windows.currentWindow()).thenReturn(TransferWindowService.Window.SUMMER);
        when(userRepository.findAllByFootballTeamIdIn(any())).thenReturn(List.of());

        // Club 0 is the viewer's own club, in Serbia. Its first player is a keeper: the viewer's own
        // keeper coming back as a signing target would be its own kind of wrong answer.
        Team viewerClub = club(VIEWER_TEAM_ID, VIEWER_COUNTRY);
        allClubs.add(viewerClub);
        for (int c = 1; c < CLUBS_PER_COUNTRY * 2; c++) {
            allClubs.add(club(1000L + c, c % 2 == 0 ? VIEWER_COUNTRY : "CRO"));
        }

        long nextPlayerId = 100_000L;
        for (Team club : allClubs) {
            for (int p = 0; p < SQUAD_SIZE; p++) {
                Player player = new Player();
                player.setId(nextPlayerId++);
                player.setName(club.getName() + "-" + p);
                player.setTeam(club);
                player.setPosition(p == 0 ? Position.GK : Position.ATT);
                player.setPlayerValue(1_000_000.0);
                allPlayers.add(player);
            }
        }
        // The players currently on the market. They belong to a Serbian club's squad, so a scout list
        // that never asked who is listed would show every one of them twice: once priced, once free.
        Team seller = allClubs.get(2);
        for (Long listedId : listedPlayerIds) {
            Player listed = new Player();
            listed.setId(listedId);
            listed.setName("Listed " + listedId);
            listed.setTeam(seller);
            listed.setPosition(Position.MID);
            allPlayers.add(listed);
        }

        // The database answers like the database.
        when(teamRepository.findClubTeamsForCountryIso(VIEWER_COUNTRY)).thenReturn(serbianClubs());
        when(teamRepository.findClubTeamsForCountryIso("CRO")).thenReturn(croClubs());
        // The whole world, which is what the code used to load before throwing most of it away. It is
        // stubbed on purpose: without it a reintroduced per-club sweep would find nothing to sweep and
        // the guard would pass on a service that had stopped answering at all.
        when(teamRepository.findClubTeamsForOperations()).thenReturn(allClubs);
        when(playerRepository.findByTeamId(any())).thenAnswer(invocation -> {
            Long teamId = invocation.getArgument(0);
            List<Player> squad = new ArrayList<>();
            for (Player player : allPlayers) {
                if (teamId.equals(player.getTeam().getId())) {
                    squad.add(player);
                }
            }
            return squad;
        });
        when(playerRepository.findByTeamIdIn(Mockito.<List<Long>>any())).thenAnswer(invocation -> {
            List<Long> teamIds = invocation.getArgument(0);
            List<Player> inThoseClubs = new ArrayList<>();
            for (Player player : allPlayers) {
                if (teamIds.contains(player.getTeam().getId())) {
                    inThoseClubs.add(player);
                }
            }
            // A query without ORDER BY has no promised order, and Postgres does not keep to the order
            // of the IN list. Reversing on alternate calls models exactly that: whatever order comes
            // back, the service must impose one before it cuts sixty rows out of this list. Without
            // this the stability guard below would pass against code that has no order at all.
            if (bulkReads++ % 2 == 1) {
                java.util.Collections.reverse(inThoseClubs);
            }
            return inThoseClubs;
        });
        when(transferRepository.findActiveListedPlayerIds()).thenReturn(listedPlayerIds);
        when(transferRepository.findByPlayerId(any())).thenReturn(Optional.empty());

        service = new TransferService(
                transferRepository,
                playerRepository,
                teamRepository,
                userRepository,
                mock(SquadNumberAssigner.class),
                windows,
                new ClubNeedService(playerRepository, contracts),
                mock(NegotiationService.class),
                mock(TransferListingFeeService.class),
                mock(ListingObjectionService.class),
                new SquadRegistrationService(playerRepository, mock(LoanRepository.class)),
                mock(LoanService.class));
    }

    private Team club(Long id, String isoCode) {
        Country country = new Country();
        country.setIsoCode(isoCode);
        Team club = new Team();
        club.setId(id);
        club.setName("Club " + id);
        club.setCountry(country);
        club.setBudget(1_000_000.0);
        return club;
    }

    private List<Team> serbianClubs() {
        return clubsInCountry(VIEWER_COUNTRY, true);
    }

    private List<Team> croClubs() {
        return clubsInCountry("CRO", false);
    }

    /** Clubs of one country, optionally without the viewer's own — what the repository query returns. */
    private List<Team> clubsInCountry(String isoCode, boolean excludeViewer) {
        List<Team> clubs = new ArrayList<>();
        for (Team club : allClubs) {
            if (!isoCode.equals(club.getCountry().getIsoCode())) {
                continue;
            }
            if (excludeViewer && VIEWER_TEAM_ID.equals(club.getId())) {
                continue;
            }
            clubs.add(club);
        }
        return clubs;
    }

    private long callsTo(Object bean, String methodName) {
        return Mockito.mockingDetails(bean).getInvocations().stream()
                .filter(inv -> methodName.equals(inv.getMethod().getName()))
                .count();
    }

    @Test
    @DisplayName("the scout list asks the database three questions, not one per club in the world")
    void theScoutListDoesNotAskPerClub() {
        service.scoutedUnlisted(VIEWER_TEAM_ID, VIEWER_COUNTRY);

        long perClubReads = callsTo(playerRepository, "findByTeamId");
        long perPlayerListingReads = callsTo(transferRepository, "findByPlayerId");
        long countryReads = callsTo(teamRepository, "findClubTeamsForCountryIso");
        long bulkReads = callsTo(playerRepository, "findByTeamIdIn");
        long listedIdReads = callsTo(transferRepository, "findActiveListedPlayerIds");

        // Not a budget — an exact contract. Counts are what regress, so they are what is written down.
        assertEquals(0, perClubReads,
                "the scout list asked for " + perClubReads + " individual club squads. That is the "
                        + "14,880-query sweep that made the transfers page take 11.7 s: it read every "
                        + "club in the world and only then threw the other countries away.");
        assertEquals(0, perPlayerListingReads,
                "the scout list asked " + perPlayerListingReads + " times who is already on the market, "
                        + "once per player and before the limit of 60. One list of ids answers that.");
        assertEquals(1, countryReads,
                "expected one question of the database for this country's clubs, got " + countryReads);
        assertEquals(1, bulkReads,
                "expected one bulk squad read covering the whole country, got " + bulkReads);
        assertEquals(1, listedIdReads,
                "expected one read of the players already on the market, got " + listedIdReads);
    }

    @Test
    @DisplayName("the bulk read asks for this country's clubs and nobody else's")
    void theBulkReadCoversExactlyThisCountry() {
        service.scoutedUnlisted(VIEWER_TEAM_ID, VIEWER_COUNTRY);

        ArgumentCaptor<List<Long>> asked = ArgumentCaptor.forClass(List.class);
        Mockito.verify(playerRepository).findByTeamIdIn(asked.capture());

        List<Long> expected = new ArrayList<>();
        for (Team club : serbianClubs()) {
            expected.add(club.getId());
        }
        assertEquals(new LinkedHashSet<>(expected), new LinkedHashSet<>(asked.getValue()),
                "the squad read was asked for clubs that are not this country's rivals: "
                        + asked.getValue());
    }

    @Test
    @DisplayName("the rows are right: rivals only, no keepers, nobody already for sale")
    void theScoutListReturnsTheRightRows() {
        List<Player> scouted = service.scoutedUnlisted(VIEWER_TEAM_ID, VIEWER_COUNTRY);

        assertFalse(scouted.isEmpty(), "a country of 40 clubs produced no scout rows at all");
        assertTrue(scouted.size() <= 60, "the limit of 60 was not applied: " + scouted.size());
        for (Player player : scouted) {
            assertFalse(VIEWER_TEAM_ID.equals(player.getTeam().getId()),
                    "the manager's own club appeared in his own scout list: " + player.getTeam().getId());
            assertTrue(player.getPosition() != Position.GK,
                    "a goalkeeper was offered as a signing target: " + player.getName());
            assertFalse(listedPlayerIds.contains(player.getId()),
                    "a player who is already on the market was also offered as an unlisted scout: "
                            + player.getName());
        }
    }

    @Test
    @DisplayName("the same market comes back every time it is asked for")
    void theMarketIsStableAcrossCalls() {
        List<Long> first = idsOf(service.scoutedUnlisted(VIEWER_TEAM_ID, VIEWER_COUNTRY));
        List<Long> second = idsOf(service.scoutedUnlisted(VIEWER_TEAM_ID, VIEWER_COUNTRY));

        assertEquals(first, second,
                "two identical calls returned different scout rows, so the market reshuffles itself "
                        + "on every page load. The cut has to be ordered, not left to the database.");
    }

    @Test
    @DisplayName("a country with nothing to scout says so instead of asking the database")
    void noCountryNoMarket() {
        assertTrue(service.scoutedUnlisted(VIEWER_TEAM_ID, "  ").isEmpty());
        assertTrue(service.scoutedUnlisted(null, VIEWER_COUNTRY).isEmpty());
        assertEquals(0, callsTo(teamRepository, "findClubTeamsForCountryIso"),
                "a blank country should be answered without asking the database anything");
    }

    /**
     * The ISO question itself. The Java this replaced compared with {@code equalsIgnoreCase}; a bare
     * equality in the query would quietly return an empty market for a manager whose stored code is
     * lower case, and an empty market is indistinguishable from a country with no rivals.
     */
    @Test
    @DisplayName("a lower-case country code asks the database about the same country")
    void theCountryQuestionIsCaseInsensitive() {
        service.scoutedUnlisted(VIEWER_TEAM_ID, VIEWER_COUNTRY.toLowerCase());

        Mockito.verify(teamRepository).findClubTeamsForCountryIso(VIEWER_COUNTRY);
    }

    private List<Long> idsOf(List<Player> players) {
        List<Long> ids = new ArrayList<>();
        for (Player player : players) {
            ids.add(player.getId());
        }
        return ids;
    }
}