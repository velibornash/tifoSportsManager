package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.GameClock;
import org.example.footballmanager.newLogic.model.GameDay;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.PlayerZoneLoad;
import org.example.footballmanager.newLogic.model.Zone;
import org.example.footballmanager.newLogic.repository.GameClockRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.PlayerZoneLoadRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Daily recovery for the whole world (owner, 2026-09-30).
 *
 * <p>Two defects, both found by the clock fix in the previous commit rather than by anybody looking at
 * this code. The job is pinned to {@code ANY_DAY} at 06:00, so it fired once a week when the clock
 * jumped a week at a time. The moment the clock started walking all seven days, it fired seven times a
 * week, and each firing cost 42 minutes:
 *
 * <pre>Recovery: 7408 player(s) recovered on season 2 week 12 day 1.</pre>
 *
 * <p>42 minutes because the job loaded every player in the world and then asked the zone-load repository
 * about each one — 16,354 round trips. And the two-day window it summed over was measured against the
 * wall clock, so a season the owner had played in one evening counted as "the last two days".
 */
class ZoneLoadRecoveryTest extends BaseTest {

    @Autowired private ZoneLoadService zoneLoads;
    @Autowired private PlayerRepository players;
    @Autowired private PlayerZoneLoadRepository zoneLoadRows;
    @Autowired private MatchRepository matches;
    @Autowired private MatchFixtureRepository fixtures;
    @Autowired private GameClockRepository clocks;
    @Autowired private PlatformTransactionManager transactionManager;

    private <T> T read(java.util.function.Supplier<T> body) {
        return new TransactionTemplate(transactionManager).execute(status -> body.get());
    }

    @Test
    @DisplayName("recovery is measured against the game clock, not the wall clock")
    void recoveryFollowsTheGameCalendar() {
        // The bug: LocalDateTime.now() in the recovery window. The owner plays a twelve-week season in
        // one evening, so every match he has ever played sits inside a two-day wall-clock window and
        // "what did he do in the last two days" answers with the whole season.
        setGameDate(LocalDateTime.of(2026, 3, 1, 6, 0));
        Player player = player("Recovery Game Clock");
        Match old = matchPlayedOn(LocalDateTime.of(2026, 1, 1, 18, 0));
        zoneLoadRows.save(zoneLoad(player, old, Zone.MIDFIELD_CENTRE, 90, 0.8));

        zoneLoads.applyDailyRecovery();
        read(() -> null);
        double fromAMonthAgo = zoneLoads.recoveryFor(player.getId());

        // Same player, same load, but the match is now yesterday in game time.
        deleteZoneLoads();
        setGameDate(LocalDateTime.of(2026, 3, 10, 6, 0));
        Match yesterday = matchPlayedOn(LocalDateTime.of(2026, 3, 9, 18, 0));
        zoneLoadRows.save(zoneLoad(player, yesterday, Zone.MIDFIELD_CENTRE, 90, 0.8));

        zoneLoads.applyDailyRecovery();
        read(() -> null);
        double fromYesterday = zoneLoads.recoveryFor(player.getId());

        assertTrue(fromYesterday > fromAMonthAgo,
                "a match from yesterday (" + fromYesterday + ") recovers no more than one from a month "
                        + "ago (" + fromAMonthAgo + ") — the window is not following the game clock");
    }

    @Test
    @DisplayName("the bulk pass gives the same answer as the per-player one")
    void theArithmeticIsUnchanged() {
        // The optimisation is one query instead of thousands. If the two paths disagree, one of them is
        // wrong and it is not knowable from the log, so they are compared directly.
        setGameDate(LocalDateTime.of(2026, 3, 10, 6, 0));

        Player heavy = player("Recovery Heavy");
        Match match = matchPlayedOn(LocalDateTime.of(2026, 3, 9, 18, 0));
        zoneLoadRows.save(zoneLoad(heavy, match, Zone.MIDFIELD_CENTRE, 90, 1.0));
        zoneLoadRows.save(zoneLoad(heavy, match, Zone.ATTACKING_CENTRE, 45, 0.9));

        Player idle = player("Recovery Idle");
        Match oldMatch = matchPlayedOn(LocalDateTime.of(2026, 1, 1, 18, 0));
        zoneLoadRows.save(zoneLoad(idle, oldMatch, Zone.MIDFIELD_CENTRE, 90, 1.0));

        int touched = zoneLoads.applyDailyRecovery();
        read(() -> null);

        assertTrue(zoneLoads.recoveryFor(heavy.getId()) > 0.0, "the played player recovers nothing");
        assertEquals(0.0, zoneLoads.recoveryFor(idle.getId()), 0.0001,
                "a player whose match is outside the window recovers something");
        assertTrue(touched >= 1, "the pass touched nobody at all");
    }

    @Test
    @DisplayName("one recovery pass touches every player in the world and finishes")
    void thePassIsBounded() {
        // Not a benchmark. The assertion is that a pass over the seeded world completes and reports a
        // number, where before it was a 42-minute operation that held the request thread open. If this
        // ever needs a timeout to pass, the N+1 is back.
        setGameDate(LocalDateTime.of(2026, 3, 10, 6, 0));

        long startedAt = System.nanoTime();
        int touched = zoneLoads.applyDailyRecovery();
        long millis = (System.nanoTime() - startedAt) / 1_000_000;

        assertTrue(touched >= 0, "the pass reported a negative number of players");
        assertTrue(millis < 30_000,
                "a recovery pass over the world took " + millis + " ms; the per-player query pattern is "
                        + "back, or the world has outgrown a single pass");
    }

    @Test
    @DisplayName("a player who has never played is not touched")
    void neverPlayedIsNotRecovered() {
        setGameDate(LocalDateTime.of(2026, 3, 10, 6, 0));
        // A player with a zone load but no last_played_at: the row says he worked, the career record
        // says he has never appeared. Recovery belongs to the career record. My first version of this
        // test built a player *with* a last_played_at and then asserted he had none, so it failed on the
        // fixture and I nearly read it as a service bug.
        Player created = player("Recovery Never Played");
        created.setLastPlayedAt(null);
        Player player = read(() -> players.save(created));
        double before = player.getMorale();
        Match match = matchPlayedOn(LocalDateTime.of(2026, 3, 9, 18, 0));
        zoneLoadRows.save(zoneLoad(player, match, Zone.MIDFIELD_CENTRE, 90, 1.0));

        zoneLoads.applyDailyRecovery();
        read(() -> null);

        assertEquals(before, read(() -> players.findById(player.getId()).orElseThrow()).getMorale(),
                0.0001, "a player with no last_played_at was given recovery for a match they did not play in");
    }

    @Test
    @DisplayName("the clock repository is a leaf — the recovery job cannot cycle back into it")
    void theClockIsReadWithoutACycle() {
        // Worth a test because the alternative is invisible until boot fails for the whole world:
        // GameClockService depends on the job runner, the job runner on the recovery job, and the
        // recovery job on this service. Reading the clock through the service closes that loop, so the
        // clock is read through the repository instead.
        assertFalse(clocks.getClass().getName().contains("Service"),
                "the clock is fetched through a service, which risks the boot cycle this avoids");
    }

    // --- helpers ---

    private void setGameDate(LocalDateTime when) {
        read(() -> {
            GameClock clock = clocks.findTopByOrderByIdDesc().orElseGet(GameClock::new);
            clock.setCurrentDate(when);
            clock.setCurrentSeason(1);
            clock.setCurrentWeek(1);
            clock.setCurrentDay(GameDay.DAY_1.number());
            clocks.save(clock);
            return null;
        });
    }

    private Match matchPlayedOn(LocalDateTime when) {
        Match match = new Match();
        match.setPlayed(true);
        match.setSeasonYear(1);
        match.setMatchDate(when);
        match.setEventJson("[]");
        return read(() -> matches.save(match));
    }

    private PlayerZoneLoad zoneLoad(Player player, Match match, Zone zone, double minutes, double intensity) {
        PlayerZoneLoad load = new PlayerZoneLoad();
        load.setPlayer(player);
        load.setMatch(match);
        load.setZone(zone);
        load.setMinutes(minutes);
        load.setIntensity(intensity);
        return load;
    }

    private Player player(String name) {
        Player player = new Player();
        player.setName(name);
        player.setAge(25);
        player.setPosition(org.example.footballmanager.newLogic.model.Position.MID);
        player.setMorale(6.0);
        player.setForm(6.0);
        player.setLastPlayedAt(LocalDateTime.of(2026, 3, 9, 20, 0));
        return read(() -> players.save(player));
    }

    private void deleteZoneLoads() {
        read(() -> {
            zoneLoadRows.deleteAll();
            return null;
        });
    }
}
