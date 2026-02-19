package org.example.footballmanager.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.dto.*;
import org.example.footballmanager.model.*;
import org.example.footballmanager.model.event.*;
import org.example.footballmanager.repository.*;
import org.example.footballmanager.simulator.DemoSimulator;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DemoSimulationService {
    private final DemoSimulator simulator;
    private final MatchRepository matchRepository;

    @Async
    @Transactional
    @SneakyThrows
    public CompletableFuture<Match> startDemoSimulation(long matchId) {
        Match match = simulator.loadAndValidateMatch(matchId);
        if (!simulator.startSimulationOnlyIfNotRunning(matchId)) {return CompletableFuture.completedFuture(null);}
        ScheduledExecutorService scheduler = simulator.createAndRegisterScheduler(matchId);
        DemoMatchRuntime runtime = simulator.initializeRuntimeAndPositions(matchId);
        simulator.startPositionBroadcastLoop(scheduler, matchId, runtime);
        simulator.prepareMatchEntities(match, runtime);
        runtime.homeTactics = simulator.createHomeTactics(match);
        runtime.awayTactics = simulator.createAwayTactics(match);
        runtime.homePlayers = match.getHomeLineup().getStartingPlayers();
        runtime.awayPlayers = match.getAwayLineup().getStartingPlayers();
        runtime = simulator.simulateMatch(match, runtime.crowd, runtime.referee, runtime.homeTactics, runtime.awayTactics, runtime.homePlayers, runtime.awayPlayers, scheduler);
        Match saved = simulator.finalizeMatchResult(match, runtime.homePlayers, runtime.awayPlayers, runtime);

        log.info("Simulacija završena za meč {}", matchId);
        return CompletableFuture.completedFuture(saved);
    }
}