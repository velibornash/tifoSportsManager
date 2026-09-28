package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.Transfer;
import org.example.footballmanager.newLogic.model.TransferOffer;
import org.example.footballmanager.newLogic.model.TransferStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

/**
 * Stand-in transfer activity, so the screens have something on them (owner, 2026-09-28).
 *
 * <p><b>This is scaffolding and is labelled as such.</b> The real feature is T1 in the backlog — an AI
 * club each week deciding who it wants, registering interest, making an offer and settling it.
 * `NegotiationService` is written and unwired, which is the same shape of gap as
 * `RegistrationService` this morning.
 *
 * <p>Until that exists the transfer screens are permanently empty, and an empty screen cannot be
 * judged. "Is this the right layout, is the price formatted sensibly, does an offer look like an
 * offer" are all unanswerable against nothing — so a manager ends up reviewing a page that has never
 * rendered a row. This puts a handful of plausible rows on it.
 *
 * <p>Deliberately <b>random</b>, per the owner: random clubs, random players, prices scaled off real
 * player values. Hand-picked rows would be a screenshot fixture and would hide exactly the layout
 * problems a long name or a nine-figure fee causes.
 *
 * <p>Idempotent. It only runs when the market is completely empty, so it never re-rolls a market the
 * user has acted in, and it stops once anything at all is listed.
 */
@Component
public class TransferActivitySeeder {

    private static final Logger log = LoggerFactory.getLogger(TransferActivitySeeder.class);

    /** How many players get put on the market. Small on purpose — this is a demonstration, not a season. */
    private static final int LISTINGS = 14;

    /** How many AI clubs make an actual offer on one of the manager's players. */
    private static final int INCOMING_OFFERS = 2;

    /** Offers expire a week out, matching the engine's own default shape. */
    private static final long OFFER_LIFETIME_DAYS = 7;

    private final org.example.footballmanager.newLogic.repository.TeamRepository teams;
    private final org.example.footballmanager.newLogic.repository.PlayerRepository players;
    private final org.example.footballmanager.newLogic.repository.TransferRepository transfers;
    private final org.example.footballmanager.newLogic.repository.TransferOfferRepository offers;
    private final Random random;

    /**
     * The constructor Spring uses.
     *
     * <p>Marked explicitly because this class has two. The unit test passes either way — it builds the
     * seeder directly with a seeded {@link Random} — so <b>nothing caught this but starting the
     * application</b>: Spring found two constructors and neither was a default, and refused to guess.
     * A unit test that constructs a class cannot tell you anything about how that class is wired.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public TransferActivitySeeder(
            org.example.footballmanager.newLogic.repository.TeamRepository teams,
            org.example.footballmanager.newLogic.repository.PlayerRepository players,
            org.example.footballmanager.newLogic.repository.TransferRepository transfers,
            org.example.footballmanager.newLogic.repository.TransferOfferRepository offers) {
        this(teams, players, transfers, offers, new Random());
    }

    TransferActivitySeeder(
            org.example.footballmanager.newLogic.repository.TeamRepository teams,
            org.example.footballmanager.newLogic.repository.PlayerRepository players,
            org.example.footballmanager.newLogic.repository.TransferRepository transfers,
            org.example.footballmanager.newLogic.repository.TransferOfferRepository offers,
            Random random) {
        this.teams = teams;
        this.players = players;
        this.transfers = transfers;
        this.offers = offers;
        this.random = random;
    }

    /**
     * Puts activity on the market, unless there already is some.
     *
     * <p>The "unless" is the important half. A seeder that re-rolls is a seeder that silently undoes
     * the user's work, and it would do it on every restart.
     */
    @Transactional
    public List<Transfer> seedIfMarketIsEmpty(List<Team> humanTeams) {
        if (!transfers.findByStatus(TransferStatus.LISTED).isEmpty()) {
            return List.of();
        }

        List<Team> aiClubs = teams.findClubTeamsForOperations().stream()
                .filter(t -> t.getId() != null)
                .filter(t -> !t.isHumanControlled())
                .toList();
        if (aiClubs.isEmpty()) {
            log.info("Transfer seeding skipped: no AI clubs to list players from.");
            return List.of();
        }

        List<Transfer> made = new java.util.ArrayList<>();

        // 1. The market. Random clubs, random players, price scaled off what the player is worth so
        //    the numbers are plausible rather than uniformly random - a 2 million euro striker listed
        //    at 40,000 is the sort of thing that makes a manager stop trusting a screen.
        for (int i = 0; i < LISTINGS; i++) {
            Team club = aiClubs.get(random.nextInt(aiClubs.size()));
            var squad = players.findByTeamId(club.getId());
            if (squad == null || squad.isEmpty()) {
                continue;
            }
            var player = squad.get(random.nextInt(squad.size()));
            if (transfers.findByPlayerId(player.getId()).isPresent()) {
                continue;
            }
            Transfer listing = new Transfer();
            listing.setPlayer(player);
            listing.setSellerTeam(club);
            listing.setStatus(TransferStatus.LISTED);
            listing.setAskingPrice(askingPriceFor(player));
            listing.setListedAt(LocalDateTime.now().minusHours(random.nextInt(72)));
            made.add(transfers.save(listing));
        }

        // 2. Incoming offers on the manager's own players, so the panel that is permanently empty
        //    shows a real offer with a real fee, a real wage and a real expiry.
        for (Team human : humanTeams == null ? List.<Team>of() : humanTeams) {
            if (human == null || human.getId() == null) {
                continue;
            }
            var squad = players.findByTeamId(human.getId());
            if (squad == null || squad.isEmpty()) {
                continue;
            }
            for (int i = 0; i < INCOMING_OFFERS; i++) {
                var player = squad.get(random.nextInt(squad.size()));
                if (transfers.findByPlayerId(player.getId()).isPresent()) {
                    continue;
                }
                Transfer listing = new Transfer();
                listing.setPlayer(player);
                listing.setSellerTeam(human);
                listing.setStatus(TransferStatus.OFFER_RECEIVED);
                listing.setAskingPrice(askingPriceFor(player));
                listing.setListedAt(LocalDateTime.now().minusDays(random.nextInt(4)));
                Transfer saved = transfers.save(listing);

                List<Team> buyers = aiClubs.stream()
                        .filter(c -> !c.getId().equals(human.getId()))
                        .toList();
                if (buyers.isEmpty()) {
                    continue;
                }
                Team buyer = buyers.get(random.nextInt(buyers.size()));

                TransferOffer offer = new TransferOffer();
                offer.setTransfer(saved);
                offer.setBuyerTeam(buyer);
                offer.setRound(1);
                offer.setStatus(org.example.footballmanager.newLogic.model.OfferStatus.OPEN);
                // An offer slightly under the asking price. An AI club that opens at the full asking
                // price has not negotiated anything, and the round structure exists to be used.
                double fee = Math.round(listing.getAskingPrice() * (0.75 + random.nextDouble() * 0.2));
                offer.setFee(fee);
                offer.setWage(Math.round((8_000 + random.nextInt(34_000)) / 500.0) * 500.0);
                offer.setContractYears(2 + random.nextInt(3));
                offer.setCreatedAt(Instant.now());
                offer.setExpiresAt(Instant.now().plusSeconds(OFFER_LIFETIME_DAYS * 24 * 3600));
                offers.save(offer);
            }
        }

        log.info("Seeded stand-in transfer activity: {} listings and {} incoming offers. "
                        + "Scaffolding for the T1 AI transfer loop, not real club behaviour.",
                made.size(), INCOMING_OFFERS);
        return made;
    }

    /**
     * A plausible asking price, scaled off the player's own value.
     *
     * <p>Between 60% and 150% of it. A club asking wildly over or under value is a thing that happens
     * in football, but the point of this seeder is to exercise the screen, and a market of uniformly
     * random numbers is the one thing that would not.
     */
    private double askingPriceFor(Object player) {
        double factor = 0.6 + random.nextDouble() * 0.9;
        // Rounded to 5,000: transfer fees are negotiated, not metered to the euro.
        return Math.max(10_000.0, Math.round(valueOf(player) * factor / 5_000.0) * 5_000.0);
    }

    /** The player's own value, or a plausible one when the entity does not carry it. */
    private double valueOf(Object player) {
        if (player instanceof org.example.footballmanager.newLogic.model.Player typed
                && typed.getPlayerValue() > 0) {
            return typed.getPlayerValue();
        }
        return 50_000 + random.nextInt(450_000);
    }
}
