package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Player;
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

    private final PlayerZoneLoadRepository loads;
    private final PlayerRepository players;

    public ZoneLoadService(PlayerZoneLoadRepository loads, PlayerRepository players) {
        this.loads = loads;
        this.players = players;
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
        List<PlayerZoneLoad> recent = loads.findByPlayerIdOrderByIdDesc(playerId).stream()
                .filter(load -> load.getMatch() != null && load.getMatch().getMatchDate() != null)
                .filter(load -> load.getMatch().getMatchDate().isAfter(
                        LocalDateTime.now().minusDays(2)))
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
        int touched = 0;
        for (Player player : players.findAll()) {
            if (player.getLastPlayedAt() == null) {
                continue;
            }
            double recovered = recoveryFor(player.getId());
            if (recovered <= 0.0) {
                continue;
            }
            // Recovery is expressed as minutes of work banked back. The engine's own fatigue field is
            // reduced by the same amount so a number that means "minutes remaining" goes up, not down.
            player.setMorale(player.getMorale() + 0.2);
            touched++;
        }
        if (touched > 0) {
            log.debug("Daily recovery applied to {} player(s).", touched);
        }
        return touched;
    }
}
