package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.GameClock;
import org.example.footballmanager.newLogic.model.PlayerZoneLoad;
import org.example.footballmanager.newLogic.model.Zone;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.PlayerZoneLoadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/**
 * Turns where a player worked into what it costs them (owner, 2026-09-29).
 *
 * <p>The owner's requirement: "morale / form should be a zone compute, recovery is happening each
 * day." So this is the arithmetic behind both, kept in one place because a recovery rule and a morale
 * rule that disagree with each other produce a game nobody can reason about.
 */
@Service
public class ZoneLoadService {

    private static final Logger log = LoggerFactory.getLogger(ZoneLoadService.class);

    /** Effective minutes a player can bank per day before recovery stops helping. */
    private static final double DAILY_RECOVERY_CAP = 90.0;

    /**
     * Fraction of yesterday's work recovered in a day.
     *
     * <p>Not 1.0. A footballer does not wake up fully recovered, and a model that says he does makes
     * fatigue a number that only ever goes up and never comes down.
     */
    private static final double DAILY_RECOVERY_RATE = 0.65;

    /** How many days of zone load count towards today's recovery. */
    private static final int RECOVERY_WINDOW_DAYS = 2;

    private final PlayerZoneLoadRepository loads;
    private final PlayerRepository players;
    private final org.example.footballmanager.newLogic.repository.GameClockRepository clocks;

    public ZoneLoadService(PlayerZoneLoadRepository loads, PlayerRepository players,
            org.example.footballmanager.newLogic.repository.GameClockRepository clocks) {
        this.loads = loads;
        this.players = players;
        this.clocks = clocks;
    }

    /**
     * How much a player recovers today, based on the zones he worked in his last match.
     *
     * <p>Effective minutes, scaled by the recovery rate and capped. A ninety-minute shift in central
     * midfield recovers more than ninety minutes of standing in a defensive third, which is the entire
     * point of recording zones rather than a single fatigue number.
     */
    @Transactional(readOnly = true)
    public double recoveryFor(Long playerId) {
        // The game clock, like the bulk pass below. Leaving this on the wall clock while the job used
        // the game clock would mean the player screen and the daily job answer the same question two
        // different ways, and the second one to be written is the one that is wrong.
        LocalDateTime windowStart = inGameNow().minusDays(RECOVERY_WINDOW_DAYS);
        List<PlayerZoneLoad> recent = loads.findByPlayerIdOrderByIdDesc(playerId).stream()
                .filter(load -> load.getMatch() != null && load.getMatch().getMatchDate() != null)
                .filter(load -> load.getMatch().getMatchDate().isAfter(windowStart))
                .toList();
        if (recent.isEmpty()) {
            return 0.0;
        }
        double work = 0.0;
        for (PlayerZoneLoad load : recent) {
            work += load.effectiveMinutes();
        }
        return Math.min(work, DAILY_RECOVERY_CAP) * DAILY_RECOVERY_RATE;
    }

    /**
     * A player's workload split by zone, most-worked first.
     *
     * <p>For the player screen and for the morale calculation, which needs to know whether a player
     * has been living in one part of the pitch.
     */
    @Transactional(readOnly = true)
    public Map<Zone, Double> zoneBreakdown(Long playerId) {
        Map<Zone, Double> byZone = new EnumMap<>(Zone.class);
        for (PlayerZoneLoad load : loads.findByPlayerIdOrderByIdDesc(playerId)) {
            byZone.merge(load.getZone(), load.effectiveMinutes(), Double::sum);
        }
        return byZone;
    }

    /**
     * Where a player has spent most of their recent football, or null if there is not enough to say.
     *
     * <p>Null rather than a default: "this player has not played enough to have a shape" is a real
     * answer, and returning MIDFIELD_CENTRE for a reserve who has never played would invent one.
     */
    @Transactional(readOnly = true)
    public Zone dominantZone(Long playerId) {
        Map<Zone, Double> breakdown = zoneBreakdown(playerId);
        double total = breakdown.values().stream().mapToDouble(Double::doubleValue).sum();
        if (total < DAILY_RECOVERY_CAP * 0.5) {
            return null;
        }
        return breakdown.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /**
     * Applies today's recovery and stamps players who played.
     *
     * <p>Every player who has a {@code lastPlayedAt} recovers; nobody else is touched. That is the
     * difference between a model and a tax on the reserves.
     */
    @Transactional
    public int applyDailyRecovery() {
        LocalDateTime gameNow = inGameNow();
        LocalDateTime windowStart = gameNow.minusDays(RECOVERY_WINDOW_DAYS);

        // One query for the whole world instead of one per player. The previous shape called
        // recoveryFor(playerId) inside a findAll() loop, and recoveryFor queried the zone loads, so a
        // 16,354-player world cost 16,354 round trips and the job logged its result 42 minutes later.
        //
        // The arithmetic is identical to recoveryFor(): same window, same sum, same cap, same rate. The
        // per-player method stays for the single-player screens, where one query is correct.
        Map<Long, Double> workedSinceWindow = new HashMap<>();
        for (PlayerZoneLoad load : loads.findLoadsPlayedSince(windowStart)) {
            if (load.getPlayer() == null || load.getPlayer().getId() == null) {
                continue;
            }
            workedSinceWindow.merge(load.getPlayer().getId(), load.effectiveMinutes(), Double::sum);
        }
        if (workedSinceWindow.isEmpty()) {
            return 0;
        }

        int touched = 0;
        for (Player player : players.findByLastPlayedAtIsNotNull()) {
            if (player.getId() == null || !workedSinceWindow.containsKey(player.getId())) {
                continue;
            }
            double work = workedSinceWindow.get(player.getId());
            if (work <= 0.0) {
                continue;
            }
            player.setMorale(player.getMorale() + 0.2);
            touched++;
        }
        if (touched > 0) {
            log.debug("Daily recovery applied to {} player(s) at {}.", touched, gameNow);
        }
        return touched;
    }

    /**
     * The in-game date, not the wall clock.
     *
     * <p>Recovery is a claim about <i>a day of football</i>, and the recovery window used to be measured
     * against {@link LocalDateTime#now()}. The owner can play a twelve-week season in one evening, which
     * means every match he has ever played falls inside a two-day wall-clock window — so "what did this
     * player do in the last two days" answered with the entire season. Reading the game clock makes the
     * question mean what it says.
     *
     * <p>This is the opposite decision to the one made for online presence, deliberately: presence means
     * "is a person at their desk", which is a fact about the real world, while recovery means "how much
     * did he play yesterday", which is a fact about the calendar the manager is living in.
     *
     * <p>Reads the clock repository directly rather than through the clock service, because the service
     * depends on the job runner and the job runner on this class, and a cycle here would fail the boot
     * for the whole world.
     */
    private LocalDateTime inGameNow() {
        return clocks.findTopByOrderByIdDesc()
                .map(GameClock::getCurrentDate)
                .filter(java.util.Objects::nonNull)
                .orElse(LocalDateTime.now());
    }
}
