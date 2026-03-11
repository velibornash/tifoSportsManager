package org.example.footballmanager.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.engines.MatchEngine;
import org.example.footballmanager.engines.MatchPlaybackEngine;
import org.example.footballmanager.engines.MatchStatisticEngine;
import org.example.footballmanager.engines.RealisticMatchEngine;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.MatchRuntime;
import org.example.footballmanager.util.RuntimeSaveToDB;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
public class SimulationService {

    private final MatchEngine matchEngine;
    private final MatchPlaybackEngine playbackEngine;
    private final MatchStatisticEngine matchStatisticEngine;
    private final RuntimeSaveToDB runtimeToDB;
    private final RealisticMatchEngine realisticMatchEngine;

    @Transactional
    public CompletableFuture<Match> startSimulation(long matchId) {

        Match match = matchEngine.loadAndValidateMatch(matchId);
        if (matchEngine.startSimulationOnlyIfNotRunning(matchId)) {
            return CompletableFuture.completedFuture(null);
        }

        // 1) Full simulation runtime
        MatchRuntime runtime = matchEngine.simulateFullMatch(match);

        // 2) Persist match + runtime events
        Match saved = runtimeToDB.finalizeMatchResult(match, runtime.homePlayers, runtime.awayPlayers, runtime);

        // 3) Playback stream
        playbackEngine.startPlayback(matchId, runtime);

        return CompletableFuture.completedFuture(saved);
    }

    /**
     * Realistična simulacija - novi sistem sa pametnom AI logikom
     */
    public CompletableFuture<Match> startRealisticSimulation(long matchId) {
        Match match = matchEngine.loadAndValidateMatch(matchId);
        if (matchEngine.startSimulationOnlyIfNotRunning(matchId)) {
            return CompletableFuture.completedFuture(null);
        }

        log.info("Starting realistic match simulation for match {}", matchId);

        // Pokreni simulaciju asinkrono sa čekanjem da se WebSocket klijent poveže
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Čekaj da se WebSocket klijent poveže
                Thread.sleep(500);
                
                // 1) Realistična simulacija (90 minuta sa event-ima)
                MatchRuntime runtime = realisticMatchEngine.simulateRealisticMatch(match);

                // 2) Persist match + runtime events (u novoj transakciji)
                return finalizeRealisticMatch(matchId, runtime);
            } catch (InterruptedException e) {
                log.error("Realistic match simulation interrupted for match {}", matchId, e);
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                log.error("Error in realistic match simulation for match {}", matchId, e);
                return null;
            }
        });
    }

    /**
     * Finalizuje realistic match u svojoj transakciji
     */
    @Transactional
    protected Match finalizeRealisticMatch(long matchId, MatchRuntime runtime) {
        Match managedMatch = matchEngine.loadAndValidateMatch(matchId);

        // 1) Persist match + runtime events
        Match saved = runtimeToDB.finalizeMatchResult(managedMatch, runtime.homePlayers, runtime.awayPlayers, runtime);

        // 2) Keep the legacy websocket playback path available, but the primary realisticDemo
        // UI now reads persisted replay data through the replay metadata/chunk endpoints.
        playbackEngine.startPlayback(matchId, runtime);

        log.info("Realistic match simulation finished for match {}", matchId);
        return saved;
    }
}
