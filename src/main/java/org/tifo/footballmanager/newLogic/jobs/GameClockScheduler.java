package org.tifo.footballmanager.newLogic.jobs;

import org.tifo.footballmanager.newLogic.service.GameClockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Runs the jobs the game clock has made due (owner, 2026-09-28; rebuilt 2026-10-11).
 *
 * <p><b>It does not move the clock.</b> It used to. The clock used to be four counters that only moved
 * when somebody pressed a button, so something had to press it for the game to play itself — hence
 * {@code game.clock.auto-advance}, which is how production ended up running the season at one game
 * hour per real hour by adding 3600 seconds to an offset every tick.
 *
 * <p>Now the clock is real time plus an offset, so it already moves. What is left for a scheduler to
 * do is the part that genuinely needs a heartbeat: <b>looking</b>. This calls
 * {@link GameClockService#synchronise()}, which walks every game hour since the last synchronisation
 * and runs whatever came due on each one.
 *
 * <h2>Why every thirty minutes</h2>
 *
 * <p>Half-hourly rather than hourly, so a trigger is at most half an hour late instead of a full hour,
 * and so an hour in which nothing is due costs nothing but a handful of memo lookups. The cron is on
 * the half hour rather than at a fixed rate because a fixed rate drifts and can fire twice across a
 * daylight-saving change.
 *
 * <h2>Why "the ones that did not run last time" is not a special case</h2>
 *
 * <p>It is what a walk from {@code lastSyncedAt} does. An app that was down over a kickoff, a job that
 * threw and was re-queued, a request that timed out halfway — all of them leave hours behind, and the
 * walk is the same code either way.
 */
@Component
public class GameClockScheduler {

    private static final Logger log = LoggerFactory.getLogger(GameClockScheduler.class);

    private final GameClockService clockService;
    private final boolean enabled;

    public GameClockScheduler(GameClockService clockService,
                              @Value("${game.clock.scheduler-enabled:false}") boolean enabled) {
        this.clockService = clockService;
        this.enabled = enabled;
    }

    /**
     * Every five minutes, on the five.
     *
     * <p><b>Five minutes, not thirty</b> (owner, 2026-10-22: "u 19:45 treba matchday-prepare-1").
     * The internationals kick off at 20:45, so the matchday is prepared at 19:45 and published at
     * 20:45, and a half-hourly tick cannot land on either: it fires at :00 and :30, and the two
     * triggers are at :45 of their hours. A tick that misses the hour a job is due in is a job that
     * runs late, and a lock that lands late is a manager whose eleven was changed after it was
     * computed.
     *
     * <p>Off in dev and test by default: a season that moves underneath a test run makes every season
     * test time-dependent. The advance buttons and {@code POST /api/game-clock/advance} are the manual
     * path and work identically.
     */
    @Scheduled(cron = "${game.clock.cron:0/5 * * * * *}")
    public void synchronise() {
        if (!enabled) {
            return;
        }
        try {
            var result = clockService.synchronise();
            @SuppressWarnings("unchecked")
            Map<String, Object> jobs = (Map<String, Object>) result.get("jobs");
            if (jobs == null) {
                return;
            }
            int ran = ((Number) jobs.getOrDefault("ran", 0)).intValue();
            int failed = ((Number) jobs.getOrDefault("failed", 0)).intValue();
            boolean truncated = Boolean.TRUE.equals(jobs.get("truncated"));
            if (ran > 0 || failed > 0 || truncated) {
                log.info("Game-clock sync: season {} week {} day {} hour {} - walked {}h, {} ran, {} failed{}.",
                        result.get("seasonNumber"), result.get("weekNumber"), result.get("day"),
                        result.get("hour"), jobs.getOrDefault("walkedHours", 0), ran, failed,
                        truncated ? " (catch-up capped at " + GameClockService.MAX_CATCH_UP_HOURS + "h)" : "");
            }
        } catch (RuntimeException e) {
            // A scheduler that throws stops being scheduled. Log and carry on to the next tick.
            log.error("Game-clock synchronisation failed", e);
        }
    }
}
