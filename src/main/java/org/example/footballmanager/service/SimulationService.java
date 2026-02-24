package org.example.footballmanager.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.simulator.MatchEngine;
import org.example.footballmanager.simulator.MatchPlayback;
import org.example.footballmanager.util.MatchRuntime;
import org.example.footballmanager.util.RuntimeSaveToDB;
import org.springframework.stereotype.Service;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
public class SimulationService {

    private final MatchEngine matchEngine;
    private final MatchPlayback playbackEngine;
    private final RuntimeSaveToDB runtimeToDB;

    @Transactional
    public CompletableFuture<Match> startSimulation(long matchId) {

        Match match = matchEngine.loadAndValidateMatch(matchId);
        if (matchEngine.startSimulationOnlyIfNotRunning(matchId)) {return CompletableFuture.completedFuture(null);}
        // 1️⃣ Simulacija (instant)
        MatchRuntime runtime = matchEngine.simulateFullMatch(match);
        // 2️⃣ Snimanje u bazu
        Match saved = runtimeToDB.finalizeMatchResult(match, runtime.homePlayers, runtime.awayPlayers, runtime);
        // 3️⃣ Playback (animacija + eventi zajedno)
        playbackEngine.startPlayback(matchId, runtime);

        return CompletableFuture.completedFuture(saved);
    }
}