package org.example.footballmanager.newLogic.jobs;

import org.example.footballmanager.newLogic.model.GameDay;
import org.example.footballmanager.newLogic.service.GameClockService;
import org.example.footballmanager.newLogic.service.SeasonService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Moves the season forward on its own (owner, 2026-09-28).
 *
 * <p>Until this existed, a job ran only when somebody pressed a button. That is fine for testing and
 * useless in production: nobody is going to sit there clicking Advance Hour so the season plays
 * itself. This is the scheduled path, and it is the one that has to be correct.
 *
 * <p><b>It checks as well as advances.</b> The owner asked for a check on the hour, and the check is
 * the important half: on every tick it runs whatever is due for the current position, so a job that
 * was missed - an app that was down over a kickoff, a job that failed and was re-queued - is picked
 * up rather than being skipped for good. The done-flags make the second and third tick harmless.
 *
 * <p><b>Off by default.</b> Auto-advancing in dev and test would make the season move underneath the
 * tests, and every season test would become time-dependent. It is enabled per profile, and the
 * advance buttons remain the manual path for testing.
 */
@Component
public class GameClockScheduler {

    private static final Logger log = LoggerFactory.getLogger(GameClockScheduler.class);

    private final GameClockService clockService;
    private final JobRunner jobRunner;
    private final SeasonService seasons;
    private final boolean autoAdvance;
    private final boolean enabled;

    public GameClockScheduler(GameClockService clockService, JobRunner jobRunner, SeasonService seasons,
                              @Value("${game.clock.auto-advance:false}") boolean autoAdvance,
                              @Value("${game.clock.scheduler-enabled:false}") boolean enabled) {
        this.clockService = clockService;
        this.jobRunner = jobRunner;
        this.seasons = seasons;
        this.autoAdvance = autoAdvance;
        this.enabled = enabled;
    }

    /**
     * Hourly tick.
     *
     * <p>Cron rather than a fixed rate: a fixed rate drifts with the wall clock and can fire twice in
     * an hour across a DST change. An hourly check that means the top of the hour is what the owner
     * asked for and is the thing a manager can predict.
     */
    @Scheduled(cron = "${game.clock.cron:0 0 * * * *}")
    public void onTheHour() {
        if (!enabled) {
            return;
        }
        try {
            if (autoAdvance) {
                // Advancing is what makes a job due, so the two go together.
                clockService.advanceHour();
            }
            // Then check regardless. A job missed while the app was down is caught here rather than
            // being lost for good, which is the whole reason for the check.
            var snapshot = clockService.snapshot();
            int seasonYear = asInt(snapshot.get("seasonNumber"), 1);
            var result = jobRunner.runDue(
                    seasonYear,
                    asInt(snapshot.get("weekNumber"), 1),
                    asInt(snapshot.get("day"), GameDay.FIRST),
                    asInt(snapshot.get("hour"), 0));
            int ran = ((Number) result.getOrDefault("ran", 0)).intValue();
            int failed = ((Number) result.getOrDefault("failed", 0)).intValue();
            if (ran > 0 || failed > 0) {
                log.info("Hourly tick: season {} week {} day {} hour {} - {} job(s) ran, {} failed.",
                        seasonYear, snapshot.get("weekNumber"), snapshot.get("day"),
                        snapshot.get("hour"), ran, failed);
            }
        } catch (RuntimeException e) {
            // A scheduler that throws stops being scheduled. Log and carry on to the next hour.
            log.error("Hourly game-clock tick failed", e);
        }
    }

    private int asInt(Object value, int fallback) {
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
