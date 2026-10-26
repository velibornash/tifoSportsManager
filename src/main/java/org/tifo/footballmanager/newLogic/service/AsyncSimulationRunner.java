package org.tifo.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.tifo.footballmanager.newLogic.model.MatchFixture;
import org.tifo.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.tifo.footballmanager.newLogic.sim.SimMatchService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
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
    private final SeasonService seasonService;
    private final RankingPointsRebuildService rankingRebuild;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger simulatedCount = new AtomicInteger(0);
    private final AtomicInteger totalCount = new AtomicInteger(0);

    /**
     * Matchday jobs can share a kickoff hour. The first batch owns the worker and later batches wait here
     * instead of being silently discarded while the worker is busy.
     */
    private final Queue<List<Long>> pendingBatches = new ConcurrentLinkedQueue<>();
    private final Object queueLock = new Object();

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
    /** What one fixture turned out to be. Only {@link #SIMULATED} is a match that was played. */
    private enum Outcome { SIMULATED, ALREADY_GONE, ALREADY_PREPARED, UNPLAYABLE }

    private final AtomicInteger failedCount = new AtomicInteger(0);

    /**
     * Fixtures skipped because the matchday had already computed them.
     *
     * <p><b>This is the second path that played the same match twice.</b> The prepare job computes the
     * whole day an hour before kickoff and stores the result on the fixture as {@code preparedMatchId};
     * the fixture stays {@code played = false} until the publish job releases it. A play job arriving at
     * kickoff asked only "is it played?", answered no, and simulated the fixture again — a second match
     * for the same tie, with a different score, written over the first.
     *
     * <p>So "not played yet" is not the question. A fixture with a prepared match is someone else's, and
     * the answer here is no.
     */
    private final AtomicInteger preparedCount = new AtomicInteger(0);

    /** Ids of the fixtures that failed, so the report can name them rather than only count them. */
    private final java.util.List<Long> failedIds =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    public boolean isRunning() { return running.get(); }
    public int getSimulatedCount() { return simulatedCount.get(); }
    public int getTotalCount() { return totalCount.get(); }
    public int getFailedCount() { return failedCount.get(); }

    /** Fixtures this pass left alone because the matchday had already prepared them. */
    public int getPreparedCount() { return preparedCount.get(); }

    /** The fixtures this pass failed on, oldest first. A copy, so a caller cannot mutate the list. */
    public java.util.List<Long> getFailedIds() { return java.util.List.copyOf(failedIds); }

    @Async
    public void simulateInBackground(List<Long> fixtureIds) {
        boolean startsWorker;
        synchronized (queueLock) {
            pendingBatches.add(List.copyOf(fixtureIds));
            startsWorker = running.compareAndSet(false, true);
        }
        if (!startsWorker) {
            log.info("Background simulation already running; queued {} fixtures", fixtureIds.size());
            return;
        }

        log.info("Background simulation started for {} queued fixture batch(es)", pendingBatches.size());
        totalCount.set(0);
        simulatedCount.set(0);
        failedCount.set(0);
        preparedCount.set(0);
        failedIds.clear();

        transactionTemplate.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        try {
            while (true) {
                List<Long> batch;
                while ((batch = pendingBatches.poll()) != null) {
                    totalCount.addAndGet(batch.size());
                    simulateBatch(batch);
                }

                rateClubs();
                rebuildRanking();

                // A second job may have queued a batch while the first batch was being rated. Check and
                // release the worker under the same lock used when a caller enqueues a batch, so no batch
                // can land in the gap between "queue is empty" and "worker stopped".
                synchronized (queueLock) {
                    if (pendingBatches.isEmpty()) {
                        running.set(false);
                        break;
                    }
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
            synchronized (queueLock) {
                running.set(false);
            }
            log.info("Background simulation runner stopped");
        }
    }

    /**
     * A small bounded pool. Fixtures are independent and each carries its own transaction, so they
     * can run side by side; the pool keeps memory bounded (snapshots are already off for bot matches).
     * Four threads keeps the DB connection pool from being exhausted while still giving ~4x throughput.
     */
    private static final int SIM_POOL_SIZE = 2;

    private void simulateBatch(List<Long> fixtureIds) {
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(SIM_POOL_SIZE);
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>(fixtureIds.size());
        for (Long fixtureId : fixtureIds) {
            futures.add(pool.submit(() -> {
                try {
                    // **The outcome is decided inside the lambda, not after it.**
                    Outcome outcome = transactionTemplate.execute(status -> {
                            MatchFixture fixture = matchFixtureRepository.findById(fixtureId).orElse(null);
                            if (fixture == null) return Outcome.ALREADY_GONE;
                            if (fixture.isPlayed()) return Outcome.ALREADY_GONE;
                            if (fixture.getPreparedMatchId() != null) return Outcome.ALREADY_PREPARED;
                            if (fixture.getHomeTeam() == null || fixture.getAwayTeam() == null) {
                                return Outcome.UNPLAYABLE;
                            }

                            SimMatchService.SimMatchOutcome sim = simMatchService.simulate(fixture, false);
                            simMatchService.persist(fixture, sim.outcome(), -1L, sim.snapshots());
                            return Outcome.SIMULATED;
                        });

                    if (outcome == Outcome.UNPLAYABLE) {
                        failedCount.incrementAndGet();
                        failedIds.add(fixtureId);
                        log.warn("Fixture {} has no home side or no away side and was not played.", fixtureId);
                        return;
                    }
                    if (outcome == Outcome.ALREADY_GONE) {
                        return;
                    }
                    if (outcome == Outcome.ALREADY_PREPARED) {
                        // The matchday already computed this one; the publish job releases it at kickoff.
                        preparedCount.incrementAndGet();
                        return;
                    }
                    simulatedCount.incrementAndGet();
                    if (simulatedCount.get() % 10 == 0) {
                        log.info("Background progress: {}/{}", simulatedCount.get(), totalCount.get());
                    }
                } catch (Exception e) {
                    failedCount.incrementAndGet();
                    failedIds.add(fixtureId);
                    log.error("Failed to simulate fixture {} in background ({}/{} so far)",
                            fixtureId, failedCount.get(), totalCount.get(), e);
                }
            }));
        }
        for (java.util.concurrent.Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception ignored) {
                // already counted inside the task
            }
        }
        pool.shutdown();
        if (preparedCount.get() > 0) {
            log.info("{} fixtures were already prepared by the matchday job and were left for it to publish.",
                    preparedCount.get());
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
    /**
     * Rebuilds the ranking points and the medals once the batch is over (owner's decision, 2026-10-08).
     *
     * <p><b>This is the only thing in the application that ever writes those tables.</b> Before it,
     * {@code ClubRankingPointsService.recompute()}, {@code NationalRankingPointsService.recompute()},
     * {@code AchievementBonusService.apply()} and {@code HonourService.derive()} had no caller at all —
     * their tests called them, so they were green and the ledgers were empty. The country and club
     * ranking lists sorted nothing, no achievement bonus was applied, and no medal was derived.
     *
     * <p>Skipped for a batch that simulated nothing, so an empty matchday does not replay the world.
     *
     * <p>The catch is here and not only inside the rebuild: "cannot fail the matchday" is the runner's
     * promise, and it should not depend on a collaborator remembering to keep it.
     */
    private void rebuildRanking() {
        if (totalCount.get() <= 0) {
            return;
        }
        try {
            rankingRebuild.rebuildAfterBatch(seasonService.getActiveSeasonYear());
        } catch (RuntimeException e) {
            log.warn("Could not rebuild the ranking after the batch: {}", e.getMessage(), e);
        }
    }

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
