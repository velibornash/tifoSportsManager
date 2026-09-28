package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.newLogic.model.OfferStatus;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.Transfer;
import org.example.footballmanager.newLogic.model.TransferOffer;
import org.example.footballmanager.newLogic.model.TransferStatus;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.repository.TransferOfferRepository;
import org.example.footballmanager.newLogic.repository.TransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The stand-in transfer activity (owner, 2026-09-28).
 *
 * <p>Two properties matter more than the numbers. <b>Idempotency</b>, because a seeder that re-rolls
 * silently undoes the user's work on every restart — and a manager who has accepted an offer does not
 * expect a fresh market on the next boot. And <b>randomness over hand-picked rows</b>, because
 * hand-picked rows are a screenshot fixture and would hide exactly the layout problems a long club name
 * or a nine-figure fee causes.
 */
class TransferActivitySeederTest {

    private TeamRepository teams;
    private PlayerRepository players;
    private TransferRepository transfers;
    private TransferOfferRepository offers;
    private final List<TransferOffer> savedOffers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        teams = mock(TeamRepository.class);
        players = mock(PlayerRepository.class);
        transfers = mock(TransferRepository.class);
        offers = mock(TransferOfferRepository.class);

        List<Transfer> store = new ArrayList<>();
        when(transfers.save(any())).thenAnswer(i -> {
            store.add(i.getArgument(0));
            return i.getArgument(0);
        });
        when(transfers.findByStatus(TransferStatus.LISTED))
                .thenAnswer(i -> store.stream()
                        .filter(t -> t.getStatus() == TransferStatus.LISTED).toList());
        when(transfers.findByPlayerId(any())).thenReturn(Optional.empty());

        savedOffers.clear();
        when(offers.save(any())).thenAnswer(i -> {
            savedOffers.add(i.getArgument(0));
            return i.getArgument(0);
        });
    }

    @Test
    @DisplayName("does nothing at all when the market already has something on it")
    void doesNotRerollAnExistingMarket() {
        givenClubs(6, 0);
        // Something is already listed - the user has acted, and the market is theirs.
        Transfer existing = new Transfer();
        existing.setStatus(TransferStatus.LISTED);
        when(transfers.findByStatus(TransferStatus.LISTED)).thenReturn(List.of(existing));

        TransferActivitySeeder seeder = new TransferActivitySeeder(teams, players, transfers, offers, new Random(1));

        assertEquals(List.of(), seeder.seedIfMarketIsEmpty(List.of()),
                "re-rolling would undo whatever the user has done since the last boot");
    }

    @Test
    @DisplayName("puts random clubs' players on the market")
    void listsRandomPlayers() {
        givenClubs(6, 0);

        TransferActivitySeeder seeder = new TransferActivitySeeder(teams, players, transfers, offers, new Random(7));
        List<Transfer> made = seeder.seedIfMarketIsEmpty(List.of());

        assertTrue(made.size() > 0, "an empty screen cannot be reviewed, which is the point");
        for (Transfer listing : made) {
            assertEquals(TransferStatus.LISTED, listing.getStatus());
            assertTrue(listing.getAskingPrice() >= 10_000,
                    "a fee under 10,000 makes the market look broken: " + listing.getAskingPrice());
            assertTrue(listing.getListedAt() != null, "listedAt orders the market");
        }
    }

    @Test
    @DisplayName("prices are rounded to something a club would actually ask")
    void pricesAreRounded() {
        givenClubs(4, 0);

        TransferActivitySeeder seeder = new TransferActivitySeeder(teams, players, transfers, offers, new Random(3));
        for (Transfer listing : seeder.seedIfMarketIsEmpty(List.of())) {
            assertEquals(0.0, listing.getAskingPrice() % 5_000.0,
                    "transfer fees are negotiated, not metered to the euro: " + listing.getAskingPrice());
        }
    }

    @Test
    @DisplayName("the manager gets real incoming offers, which is the panel that is always empty")
    void createsIncomingOffersOnTheManagersPlayers() {
        givenClubs(6, 1);
        Team human = humanClub();

        new TransferActivitySeeder(teams, players, transfers, offers, new Random(11))
                .seedIfMarketIsEmpty(List.of(human));

        assertEquals(2, savedOffers.size(),
                "the Incoming offers panel has been permanently empty; the point of this seeder is "
                        + "that there is something to look at");

        for (TransferOffer offer : savedOffers) {
            assertEquals(OfferStatus.OPEN, offer.getStatus());
            assertEquals(1, offer.getRound());
            assertTrue(offer.getBuyerTeam() != null && !offer.getBuyerTeam().isHumanControlled(),
                    "a club makes its own offer to itself");
            assertTrue(offer.getExpiresAt().isAfter(Instant.now()), "an offer needs a live deadline");
            assertTrue(offer.getContractYears() >= 2 && offer.getContractYears() <= 4);
        }
    }

    @Test
    @DisplayName("an offer opens below the asking price, because the round structure exists to be used")
    void offersOpenBelowAsking() {
        givenClubs(6, 1);
        Team human = humanClub();

        new TransferActivitySeeder(teams, players, transfers, offers, new Random(5))
                .seedIfMarketIsEmpty(List.of(human));

        assertTrue(!savedOffers.isEmpty(), "expected offers to be created");
        for (TransferOffer offer : savedOffers) {
            double asking = offer.getTransfer().getAskingPrice();
            assertTrue(offer.getFee() < asking,
                    "an AI club that opens at the full asking price has not negotiated anything: "
                            + offer.getFee() + " vs " + asking);
            assertTrue(offer.getFee() > 0, "an offer with no fee is not an offer");
        }
    }

    @Test
    @DisplayName("the manager's own listing is not on the open market - he cannot be offered his player")
    void humanListingIsNotOnTheOpenMarket() {
        givenClubs(6, 1);
        Team human = humanClub();

        new TransferActivitySeeder(teams, players, transfers, offers, new Random(5))
                .seedIfMarketIsEmpty(List.of(human));

        assertTrue(transfers.findByStatus(TransferStatus.LISTED).stream()
                        .noneMatch(t -> t.getSellerTeam() != null
                                && human.getId().equals(t.getSellerTeam().getId())),
                "a listing marked OFFER_RECEIVED must not also appear as openly for sale");
    }

    private Team humanClub() {
        return teams.findClubTeamsForOperations().stream()
                .filter(Team::isHumanControlled).findFirst().orElseThrow();
    }

    // --- fixtures -------------------------------------------------------------------------------

    /** {@code aiClubs} AI clubs and {@code humanClubs} human ones, each with three players. */
    private void givenClubs(int aiClubs, int humanClubs) {
        List<Team> all = new ArrayList<>();
        long id = 1;
        for (int i = 0; i < aiClubs; i++) {
            Team club = team(id++, "AI Club " + i, false);
            all.add(club);
            withPlayers(club);
        }
        for (int i = 0; i < humanClubs; i++) {
            Team club = team(id++, "Human Club " + i, true);
            all.add(club);
            withPlayers(club);
        }
        when(teams.findClubTeamsForOperations()).thenReturn(all);
    }

    private void withPlayers(Team club) {
        List<org.example.footballmanager.newLogic.model.Player> squad = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            var player = new org.example.footballmanager.newLogic.model.Player();
            player.setId(club.getId() * 100 + i);
            player.setName("Player " + club.getId() + "-" + i);
            player.setTeam(club);
            player.setPlayerValue(100_000.0 + i * 250_000.0);
            squad.add(player);
        }
        when(players.findByTeamId(club.getId())).thenReturn(squad);
    }

    private Team team(Long id, String name, boolean human) {
        Team team = new Team();
        team.setId(id);
        team.setName(name);
        team.setHumanControlled(human);
        return team;
    }
}
