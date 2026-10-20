package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.PlayerZoneLoad;
import org.example.footballmanager.newLogic.model.Zone;
import org.example.footballmanager.newLogic.sim.recording.MatchSnapshot;
import org.example.footballmanager.newLogic.sim.recording.PlayerSnapshot;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.PlayerZoneLoadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a played match into the zone load the recovery and morale rules read (owner, 2026-09-29).
 *
 * <p>The owner: "morale / form should be a zone compute, recovery is happening each day." The models
 * for that existed from the start — {@link Zone}, {@link PlayerZoneLoad}, {@link ZoneLoadService} and
 * {@link org.example.footballmanager.newLogic.jobs.impl.RecoveryJob} — and nothing ever wrote the
 * table, so recovery correctly reported zero for every player in the world. A model with no writer is
 * not a model with a bug; it is a model that has not been connected, and it stayed that way because
 * every test of it asserted the arithmetic rather than the arrival of the data.
 *
 * <p>Where the numbers come from, and why it is not a tap of the ball: it is the whole pitch. The
 * engine already records every player's position every tick, so the load is read off the match that
 * was played rather than reconstructed from events. A player who covered four zones for ninety minutes
 * and a keeper who held one are different, and that difference is invisible if you only look at the
 * ball.
 *
 * <p><b>Minutes and intensity are kept apart</b>, because a player can cover a lot of ground slowly or
 * sprint repeatedly and those leave very different traces on a leg. Minutes come from the tick count.
 * Intensity comes from how fast he was actually moving while he was there, normalised against the
 * engine's own top speed, so a keeper waiting on his line scores near zero and a winger running at
 * full pace scores near one.
 *
 * <p><b>Zones are from the player's own perspective</b>, which is the only version that means the same
 * thing to both teams in a match. The engine's rows run from one fixed goal line, so an away player's
 * row has to be mirrored before it means "his own third" — and his left is the pitch's right.
 */
@Service
public class ZoneLoadRecorder {

    private static final Logger log = LoggerFactory.getLogger(ZoneLoadRecorder.class);

    /** Forty ticks to a match minute, which is the engine's own figure. */
    private static final int TICKS_PER_MINUTE = 40;

    /**
     * Cells a second, at full pace, used to normalise intensity.
     *
     * <p>Deliberately the engine's own ceiling rather than a measured average: this is "how hard is he
     * working relative to a player at full speed", and a full-speed player should land at 1.0 rather
     * than at whatever the busiest match in the sample happened to average.
     */
    private static final double FULL_PACE_CELLS_PER_TICK = 0.5;

    private final PlayerZoneLoadRepository loads;
    private final PlayerRepository players;

    public ZoneLoadRecorder(PlayerZoneLoadRepository loads, PlayerRepository players) {
        this.loads = loads;
        this.players = players;
    }

    /** The zone a snapshot falls in, for a player on the given side. */
    static Zone zoneFor(double row, double column, String teamSide) {
        boolean away = teamSide != null && teamSide.equalsIgnoreCase("AWAY");
        // Row 1 is a fixed goal line, so an away player reads his own third from the far end.
        double ownRow = away ? 9.0 - row : row;
        Zone zone = Zone.of(ownRow, column);
        if (!away) {
            return zone;
        }
        // His left is the pitch's right, so the lane mirrors and the third does not - mirroring the
        // row already put him in his own third.
        return switch (zone.lane()) {
            case 0 -> zoneOf(zone.third(), 2);
            case 2 -> zoneOf(zone.third(), 0);
            default -> zone;
        };
    }

    private static Zone zoneOf(int third, int lane) {
        for (Zone zone : Zone.values()) {
            if (zone.third() == third && zone.lane() == lane) {
                return zone;
            }
        }
        return Zone.MIDFIELD_CENTRE;
    }

    /**
     * Writes one row per player per zone for a match, and stamps when each player last played.
     *
     * @param snapshots every tick of the match, as the recorder captured them
     * @param playersByEngineId engine player id to database player, already resolved by the caller.
     *        The engine's ids are strings ("HOME-1" or a real database id) and only the caller knows
     *        which are which, so the mapping is passed in rather than guessed here.
     * @return how many rows were written
     */
    @Transactional
    public int record(Match match, List<MatchSnapshot> snapshots, Map<String, Player> playersByEngineId) {
        if (match == null || snapshots == null || snapshots.isEmpty() || playersByEngineId == null) {
            return 0;
        }

        Map<String, Map<Zone, Bucket>> byZone = new HashMap<>();

        for (MatchSnapshot snapshot : snapshots) {
            for (PlayerSnapshot player : snapshot.getPlayers()) {
                if (player == null || player.getTeam() == null || player.getPosition() == null) {
                    continue;
                }
                Zone zone = zoneFor(player.getPosition().getRow(), player.getPosition().getColumn(),
                        player.getTeam());
                byZone.computeIfAbsent(player.getId(), k -> new EnumMap<>(Zone.class))
                        .computeIfAbsent(zone, k -> new Bucket())
                        .add(speedOf(player));
            }
        }

        int written = 0;
        LocalDateTime playedAt = match.getMatchDate() != null ? match.getMatchDate() : LocalDateTime.now();
        for (Map.Entry<String, Map<Zone, Bucket>> entry : byZone.entrySet()) {
            Player player = playersByEngineId.get(entry.getKey());
            if (player == null) {
                // A synthetic player, or one from another game. The engine falls back to synthetic
                // squads when a club has no lineup template, and a load row pointing at nothing is
                // worse than no row.
                continue;
            }
            for (Map.Entry<Zone, Bucket> zone : entry.getValue().entrySet()) {
                Bucket bucket = zone.getValue();
                if (bucket.ticks <= 0) {
                    continue;
                }
                double minutes = bucket.ticks / (double) TICKS_PER_MINUTE;
                double averageSpeed = bucket.speedSum / bucket.ticks;

                PlayerZoneLoad load = new PlayerZoneLoad();
                load.setPlayer(player);
                load.setMatch(match);
                load.setZone(zone.getKey());
                load.setMinutes(round2(minutes));
                load.setIntensity(round3(clamp(averageSpeed / FULL_PACE_CELLS_PER_TICK, 0.0, 1.0)));
                loads.save(load);
                written++;
            }
            player.setLastPlayedAt(playedAt);
            players.save(player);
        }

        if (written > 0) {
            log.info("Zone load: {} row(s) across {} player(s) for match {}.",
                    written, byZone.size(), match.getId());
        }
        return written;
    }

    /** Ticks and the speed they were accumulated at, for one player in one zone. */
    private static final class Bucket {
        private long ticks;
        private double speedSum;

        private Bucket() {
        }

        void add(double speed) {
            ticks++;
            speedSum += speed;
        }
    }

    private static double speedOf(PlayerSnapshot player) {
        return Math.hypot(player.getVelX(), player.getVelY());
    }

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
