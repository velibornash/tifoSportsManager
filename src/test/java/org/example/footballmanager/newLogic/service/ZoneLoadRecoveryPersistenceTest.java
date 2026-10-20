package org.example.footballmanager.newLogic.service;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.PlayerZoneLoad;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.PlayerZoneLoadRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Does daily morale recovery actually reach the database?
 *
 * <p>{@code dataFixSuggestions.md} §1.1 claims it does not: that {@code applyDailyRecovery()} mutates
 * the player, counts it, never saves, and then a green {@code job_run} row is written anyway — daily
 * recovery having therefore never happened.
 *
 * <p>The claim comes from reading the source, and it misses Hibernate. The players are read
 * <em>inside</em> the method's own transaction, so they are managed and their dirtied fields are
 * flushed at commit. A missing {@code save()} on a managed entity is not a lost write.
 *
 * <p>So this does not read the entity back through the same persistence context, which would just
 * return the in-memory value and prove nothing. It clears the context and re-reads, so the only way
 * morale can have changed is if the write was committed.
 */
@SpringBootTest
@ActiveProfiles("test")
class ZoneLoadRecoveryPersistenceTest {

    @Autowired private ZoneLoadService zoneLoads;
    @Autowired private PlayerRepository players;
    @Autowired private org.example.footballmanager.newLogic.repository.MatchRepository matches;
    @Autowired private PlayerZoneLoadRepository loadRepository;
    @Autowired private TeamRepository teams;
    @Autowired private CountryRepository countries;
    @Autowired private CompetitionRepository competitions;
    @Autowired private TransactionTemplate transactions;
    @Autowired private org.example.footballmanager.newLogic.repository.GameClockRepository clocks;

    /** The world's own date, which is what the recovery window is measured against. */
    private LocalDateTime gameNow() {
        return clocks.findTopByOrderByIdDesc()
                .map(org.example.footballmanager.newLogic.model.GameClock::getCurrentDate)
                .filter(java.util.Objects::nonNull)
                .orElse(LocalDateTime.now());
    }

    @Test
    @DisplayName("recovery morale survives the persistence context, so it is committed")
    void recoveryIsActuallyWritten() {
        Long playerId = transactions.execute(status -> {
            Player player = aPlayerWhoHasPlayed();
            return player.getId();
        });

        double before = transactions.execute(status -> players.findById(playerId).orElseThrow().getMorale());

        int touched = zoneLoads.applyDailyRecovery();

        // A fresh context, a fresh read. Anything other than "unchanged" proves the commit happened.
        double after = transactions.execute(status -> players.findById(playerId).orElseThrow().getMorale());

        assertTrue(touched > 0,
                "recovery touched nobody, so this test is not measuring anything — it needs a player "
                        + "inside the recovery window");
        assertNotEquals(before, after,
                "morale is identical after a re-read from the database. applyDailyRecovery() counted "
                        + touched + " player(s) and wrote none of them, so RecoveryJob has been "
                        + "reporting DONE while changing nothing.");
        assertTrue(after > before, "morale went down during recovery: " + before + " -> " + after);
    }

    /** A player with a zone load inside the window, which is what recovery keys off. */
    private Player aPlayerWhoHasPlayed() {
        Country country = new Country();
        country.setName("ZZ Recovery");
        country.setIsoCode("ZR1");
        country.setState(CountryState.SIMULATED);
        country = countries.save(country);

        Team team = new Team();
        team.setName("ZZ Recovery FC");
        team.setCountry(country);
        team.setHumanControlled(false);
        team = teams.save(team);

        Player player = new Player();
        player.setName("Test Recoverer");
        player.setAge(24);
        player.setTeam(team);
        player.setMorale(50.0);
        // Recovery keys off lastPlayedAt, not off having zone-load rows. Without it the player
        // is skipped entirely and the test silently measures nothing.
        player.setLastPlayedAt(gameNow().minusDays(1));
        player = players.save(player);

        // The window query joins the match and reads its date, so the load needs a real one.
        org.example.footballmanager.newLogic.model.Match match = new org.example.footballmanager.newLogic.model.Match();
        match.setHomeTeam(team);
        match.setAwayTeam(team);
        // The recovery window is measured on the GAME clock, not the wall clock. Dating the
        // load with now() put it outside the window on a world whose season runs to a different
        // date, and the test then measured nothing.
        match.setMatchDate(gameNow().minusDays(1));
        match = matches.save(match);

        PlayerZoneLoad load = new PlayerZoneLoad();
        load.setPlayer(player);
        load.setMatch(match);
        load.setZone(org.example.footballmanager.newLogic.model.Zone.MIDFIELD_CENTRE);
        load.setMinutes(90.0);
        load.setIntensity(1.0);
        loadRepository.save(load);

        return player;
    }
}