package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.SquadRole;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-7 — players retire.
 *
 * <p>Before this, <b>nothing retired</b>. There is no {@code retire} token anywhere in the codebase:
 * no constant, no age-filtered query, no status field. Players aged once a year and the only ways out
 * of a squad were a transfer or a contract expiring, so a 32-year-old became 60 and then 90 while
 * staying at his club, staying on the transfer list, and still being bid for at 30% of value.
 *
 * <p><b>What is guaranteed here, and why each one matters:</b>
 *
 * <ul>
 *   <li><b>The band is quality-scaled and predictable.</b> A club plans a squad around when a player
 *       ends, so the rule has to be knowable rather than a dice roll — asserted against the real
 *       0-100 rating scale, which is not the 0-10 scale the skills use.</li>
 *   <li><b>Nobody retires early.</b> A 30-year-old stays, whoever he is.</li>
 *   <li><b>He leaves, and he is not deleted.</b> This is the assertion that matters most: the obvious
 *       implementation, {@code Team.removePlayer}, silently deletes the row, because
 *       {@code Team.players} is mapped {@code orphanRemoval = true}. That is why the method has had no
 *       callers since it was written. So the test checks the row still exists and still holds his
 *       history.</li>
 *   <li><b>His registration slot comes back.</b> {@code canRegister} counts contracts, so a retired
 *       player left holding one would occupy one of the 25 senior slots for ever and the club could
 *       never replace him.</li>
 *   <li><b>The sweep is idempotent</b>, because a season roll-over must be safe to reason about.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class PlayersRetireTest {

    @Autowired org.example.footballmanager.newLogic.repository.PlayerRepository players;
    @Autowired org.example.footballmanager.newLogic.repository.TeamRepository teams;
    @Autowired org.example.footballmanager.newLogic.repository.PlayerContractRepository contracts;
    @Autowired RetirementService retirement;
    @Autowired PlayerContractService contractService;
    @Autowired SquadRegistrationService registration;
    @Autowired org.example.footballmanager.newLogic.repository.LineupRepository lineups;
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;

    private Team aClub(String name) {
        Team t = new Team();
        t.setName(name + "-" + System.nanoTime());
        t.setBudget(5_000_000.0);
        t.setReputation(60.0);
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

    private Player aPlayer(Team team, String name, int rating, int age) {
        Player p = new Player();
        p.setName(name);
        p.setTeam(team);
        p.setRating(rating);
        p.setAge(age);
        p.setPlayerValue(1_000_000);
        p.setEarnings(10_000);
        p.setMorale(60.0);
        p.setForm(6.0);
        return players.save(p);
    }

    /** The band is knowable, and it is on the real 0-100 scale rather than the 0-10 skill scale. */
    @Test
    @DisplayName("retirement age is a predictable band scaled by quality")
    void retirementAgeIsQualityScaled() {
        Team club = aClub("BandClub");

        Player journeyman = aPlayer(club, "Journeyman", 50, 30);
        Player solid = aPlayer(club, "SolidPro", 70, 30);
        Player top = aPlayer(club, "TopPro", 80, 30);
        Player elite = aPlayer(club, "ElitePro", 90, 30);

        assertEquals(33, retirement.retirementAge(journeyman), "a journeyman goes earliest");
        assertEquals(34, retirement.retirementAge(solid));
        assertEquals(35, retirement.retirementAge(top));
        assertEquals(36, retirement.retirementAge(elite), "the best player lasts longest");

        assertTrue(retirement.retirementAge(elite) > retirement.retirementAge(journeyman),
                "quality must buy a player years, or the bands are decoration");
        assertEquals(RetirementService.EARLIEST_RETIREMENT_AGE,
                Math.min(RetirementService.EARLIEST_RETIREMENT_AGE, retirement.retirementAge(journeyman)));
        assertEquals(RetirementService.LATEST_RETIREMENT_AGE, retirement.retirementAge(elite));
    }

    /** Nobody walks off early, at any quality. */
    @Test
    @DisplayName("a player inside his band is not retired, whatever his quality")
    void nobodyRetiresEarly() {
        Team club = aClub("YoungClub");
        Player prodigy = aPlayer(club, "Prodigy", 95, 28);

        retirement.retireOverduePlayers(2);

        // Asserted on *this* player rather than on the sweep's return count: the sweep is world-wide
        // and these tests share one database, so a count would be measuring whatever earlier test
        // methods left behind. The guarantee is about him, not about the total.
        assertFalse(players.findById(prodigy.getId()).orElseThrow().isRetired(),
                "a 28-year-old best player in the world does not retire");
        assertEquals(club.getId(), players.findById(prodigy.getId()).orElseThrow().getTeam().getId(),
                "and he is still at his club");
    }

    /** He leaves the club, and the row and his history survive. */
    @Test
    @DisplayName("a player at his band retires, keeps his row, and frees his registration slot")
    void retirementRemovesHimFromTheClubWithoutDeletingHim() {
        Team club = aClub("RetiringClub");
        Player old = aPlayer(club, "OldHand", 50, 33);
        Long playerId = old.getId();
        contractService.assignToClub(old, club, 1, SquadRole.STARTER);

        assertEquals(1, retirement.retireOverduePlayers(2),
                "a 33-year-old journeyman is due");

        Player after = players.findById(playerId).orElse(null);
        assertNotNull(after,
                "the row must survive. Team.removePlayer would have deleted it: Team.players is "
                        + "mapped orphanRemoval = true, which is why that method has no callers.");
        assertTrue(after.isRetired(), "and he is recorded as retired");
        assertEquals(2, after.getRetiredSeason(), "in the season he stopped");
        assertEquals(null, after.getTeam(), "he no longer belongs to a club");
        assertEquals(0.0, after.getEarnings(), 0.001, "and draws no wage");

        var contract = contracts.findByPlayerId(playerId).orElseThrow();
        assertEquals(null, contract.getTeam(),
                "the contract ends too, so he does not linger on the books");
        // Counted in players since 2026-10-08, so the freed place is proved by the player's team being
        // null rather than by a contract count. Team.players is the membership, and it is what the cap reads.
        assertTrue(registration.canRegister(club.getId()).allowed(),
                "and the place is genuinely free again");
    }

    /** The same sweep twice must not double-count or resurrect anything. */
    @Test
    @DisplayName("the sweep is idempotent — a retired player is not retired twice")
    void theSweepIsIdempotent() {
        Team club = aClub("IdempotentClub");
        Player once = aPlayer(club, "OnceOnly", 45, 34);

        retirement.retireOverduePlayers(3);
        assertTrue(players.findById(once.getId()).orElseThrow().isRetired(),
                "precondition: he is retired by the first pass");

        assertEquals(0, retirement.retireOverduePlayers(3),
                "a second pass in the same season must find nobody left to retire — a retired player "
                        + "has no club, so the sweep's own query skips him");
        assertEquals(3, players.findById(once.getId()).orElseThrow().getRetiredSeason(),
                "and the season he retired in is not overwritten");
    }

    /**
     * A retired player must not keep starting matches.
     *
     * <p>Not part of the retirement mechanic itself, but the mechanic's first outing: the join table
     * keeps naming a player after he leaves, so a saved XI would happily start a man who no longer
     * plays for the club. This was already true for contract expiry; retirement makes it routine.
     */
    @Test
    @DisplayName("a retired player drops out of his club's saved XI")
    void aRetiredPlayerCannotStillBeInTheStartingEleven() {
        Team club = aClub("LineupClub");
        Player starter = aPlayer(club, "AgedStarter", 45, 34);
        contractService.assignToClub(starter, club, 1, SquadRole.STARTER);

        var lineup = new org.example.footballmanager.newLogic.model.Lineup();
        lineup.setTeam(club);
        lineup.setStartingPlayers(java.util.List.of(starter));
        lineup.setStarterOrderFromIds(java.util.List.of(starter.getId()));
        final Long lineupId = lineups.saveAndFlush(lineup).getId();

        assertEquals(1, inTransaction(() -> lineups.findById(lineupId).orElseThrow()
                        .getOrderedStartingPlayers().size()),
                "precondition: he is in the XI while he plays");

        retirement.retireOverduePlayers(4);

        // Re-read inside its own transaction, so the persistence context is genuinely fresh and the
        // join table is what is being consulted. Holding the same in-memory instance would test my
        // own object graph rather than the row the database keeps naming a player who has left —
        // which is the whole failure mode.
        var reloaded = inTransaction(() -> {
            var found = lineups.findById(lineupId).orElseThrow();
            assertEquals(1, found.getStartingPlayers().size(),
                    "precondition: the join table still names him, and always will");
            return found.getOrderedStartingPlayers().size();
        });
        assertEquals(0, reloaded,
                "a player with no club is in nobody's XI, however loudly the join table says otherwise");
        assertNotNull(players.findById(starter.getId()).orElse(null),
                "and he is retired, not deleted");
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return new org.springframework.transaction.support.TransactionTemplate(txManager)
                .execute(status -> work.get());
    }
}