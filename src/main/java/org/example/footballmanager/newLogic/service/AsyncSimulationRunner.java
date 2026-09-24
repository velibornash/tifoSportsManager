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

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger simulatedCount = new AtomicInteger(0);
    private final AtomicInteger totalCount = new AtomicInteger(0);

    public boolean isRunning() { return running.get(); }
    public int getSimulatedCount() { return simulatedCount.get(); }
    public int getTotalCount() { return totalCount.get(); }

    @Async
    public void simulateInBackground(List<Long> fixtureIds) {
        log.info("Background simulation started for {} fixtures", fixtureIds.size());
        if (!running.compareAndSet(false, true)) {
            log.warn("Background simulation already running");
            return;
        }
        totalCount.set(fixtureIds.size());
        simulatedCount.set(0);

        transactionTemplate.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        try {
            for (Long fixtureId : fixtureIds) {
                try {
                    transactionTemplate.executeWithoutResult(status -> {
                        MatchFixture fixture = matchFixtureRepository.findById(fixtureId).orElse(null);
                        if (fixture == null || fixture.isPlayed()) return;
                        if (fixture.getHomeTeam() == null || fixture.getAwayTeam() == null) return;

                        String homeName = fixture.getHomeTeam().getName();
                        String awayName = fixture.getAwayTeam().getName();

                        SimMatchService.SimMatchOutcome sim = simMatchService.simulate(homeName, awayName, false);
                        simMatchService.persist(fixture, sim.outcome(), -1L);
                    });
                    simulatedCount.incrementAndGet();
                    if (simulatedCount.get() % 10 == 0) {
                        log.info("Background progress: {}/{}", simulatedCount.get(), totalCount.get());
                    }
                } catch (Exception e) {
                    log.error("Failed to simulate fixture {} in background", fixtureId, e);
                }
            }
            log.info("Background simulation complete: {} fixtures", simulatedCount.get());
        } finally {
            running.set(false);
            log.info("Background simulation runner stopped");
        }
    }
}