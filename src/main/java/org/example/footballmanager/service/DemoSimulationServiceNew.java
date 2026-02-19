package org.example.footballmanager.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.simulator.DemoMatchEngine;
import org.example.footballmanager.simulator.MatchPlaybackEngine;
import org.example.footballmanager.simulator.RuntimeToDB;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
public class DemoSimulationServiceNew {

    private final DemoMatchEngine matchEngine;
    private final MatchPlaybackEngine playbackEngine;
    private final RuntimeToDB runtimeToDB;

    @Transactional
    public CompletableFuture<Match> startDemoSimulation(long matchId) {

        Match match = matchEngine.loadAndValidateMatch(matchId);
        if (!matchEngine.startSimulationOnlyIfNotRunning(matchId)) {return CompletableFuture.completedFuture(null);}
        // 1️⃣ Simulacija (instant)
        DemoMatchRuntime runtime = matchEngine.simulateFullMatch(match);
        // 2️⃣ Snimanje u bazu
        Match saved = runtimeToDB.finalizeMatchResult(match, runtime.homePlayers, runtime.awayPlayers, runtime);
        // 3️⃣ Playback (animacija + eventi zajedno)
        runtime = playbackEngine.initializeRuntimeAndPositions(runtime);
        playbackEngine.start(matchId, runtime);

        return CompletableFuture.completedFuture(saved);
    }
}