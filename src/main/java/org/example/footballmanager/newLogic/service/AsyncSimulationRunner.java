package org.example.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.sim.SimMatchService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncSimulationRunner {

    private final MatchFixtureRepository matchFixtureRepository;
    private final TransactionTemplate transactionTemplate;
    private final SimMatchService simMatchService;
    private final ClubRatingService clubRatingService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger simulatedCount = new AtomicInteger(0);
    private final AtomicInteger totalCount = new AtomicInteger(0);

    /**
     * Fixtures this pass could not simulate, which were logged and skipped.
     *
     * <p><b>Previously only logged.</b> A failed fixture was caught, logged at ERROR and the loop moved
     * on, so the run finished reporting {@code simulatedCount} against {@code totalCount} and the owner
     * had to work out the difference himself: {@code 148/154} says six went missing but never says six
     * failed. Those six are silently unplayed fixtures, and a season that quietly loses a few percent of
     * its matches is a bug that gets discovered a long time afterwards, if at all.
     *
     * <p>Counted rather than swallowed, so {@code /simulation/current-round/status} can report it.
     */
    private final AtomicInteger failedCount = new AtomicInteger(0);

    /** Ids of the fixtures that failed, so the report can name them rather than only count them. */
    private final java.util.List<Long> failedIds =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    public boolean isRunning() { return running.get(); }
    public int getSimulatedCount() { return simulatedCount.get(); }
    public int getTotalCount() { return totalCount.get(); }
    public int getFailedCount() { return failedCount.get(); }

    /** The fixtures this pass failed on, oldest first. A copy, so a caller cannot mutate the list. */
    public java.util.List<Long> getFailedIds() { return java.util.List.copyOf(failedIds); }

    @Async
    public void simulateInBackground(List<Long> fixtureIds) {
        log.info("Background simulation started for {} fixtures", fixtureIds.size());
        if (!running.compareAndSet(false, true)) {
            log.warn("Background simulation already running");
            return;
        }
        totalCount.set(fixtureIds.size());
        simulatedCount.set(0);
        failedCount.set(0);
        failedIds.clear();

        transactionTemplate.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        try {
            for (Long fixtureId : fixtureIds) {
                try {
                    transactionTemplate.executeWithoutResult(status -> {
                        MatchFixture fixture = matchFixtureRepository.findById(fixtureId).orElse(null);
                        if (fixture == null || fixture.isPlayed()) return;
                        if (fixture.getHomeTeam() == null || fixture.getAwayTeam() == null) return;

                        SimMatchService.SimMatchOutcome sim = simMatchService.simulate(fixture, false);
                        simMatchService.persist(fixture, sim.outcome(), -1L, sim.snapshots());
                    });
                    simulatedCount.incrementAndGet();
                    if (simulatedCount.get() % 10 == 0) {
                        log.info("Background progress: {}/{}", simulatedCount.get(), totalCount.get());
                    }
                } catch (Exception e) {
                    // Counted, named, and logged — a failed fixture is an unplayed match, and leaving it
                    // only in the log is how a season quietly loses a few percent of its football.
                    failedCount.incrementAndGet();
                    failedIds.add(fixtureId);
                    log.error("Failed to simulate fixture {} in background ({}/{} so far)",
                            fixtureId, failedCount.get(), totalCount.get(), e);
                }
            }
            if (failedCount.get() > 0) {
                log.warn("Background simulation complete: {} of {} fixture(s) simulated, {} FAILED: {}",
                        simulatedCount.get(), totalCount.get(), failedCount.get(), getFailedIds());
            } else {
                log.info("Background simulation complete: {} of {} fixture(s) simulated",
                        simulatedCount.get(), totalCount.get());
            }
        } finally {
            rateClubs();
            running.set(false);
            log.info("Background simulation runner stopped");
        }
    }

    /**
     * Rates the clubs once the batch is over.
     *
     * <p>This is the batch boundary, and it is the reason club Elo is not recomputed per match inside
     * {@code SimMatchService.persist}. The replay reads every club in the world and every match it has
     * played, so a matchday of 155 fixtures run that way would replay the world 155 times — and the
     * cost would grow with the world rather than with the batch.
     *
     * <p>Its own transaction, and it cannot fail the batch: the football is already saved by the time
     * this runs, and the next matchday or restart would catch up anyway.
     */
    private void rateClubs() {
        try {
            ClubRatingService.Result rated = clubRatingService.recomputeDurably();
            log.info("Club Elo after the batch: {} match(es) replayed, {} club(s) rated, {} off their seed.",
                    rated.matchesReplayed(), rated.clubsRated(), rated.clubsMoved());
        } catch (RuntimeException e) {
            log.warn("Could not recompute club Elo after the batch: {}", e.getMessage());
        }
    }
}