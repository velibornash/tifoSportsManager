package org.example.footballmanager.newLogic.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.engine_v1.MatchEngine;
import org.example.footballmanager.newLogic.engine_v1.MatchPlaybackEngine;
import org.example.footballmanager.newLogic.engine_v1.MatchStatisticEngine;
import org.example.footballmanager.newLogic.engine_v1.RealisticMatchEngine;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchRuntime;
import org.example.footballmanager.newLogic.model.event.MatchEvent;
import org.example.footballmanager.newLogic.repository.MatchEventRepository;
import org.example.footballmanager.newLogic.repository.MatchPlayerStatsRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.MatchTickStateRepository;
import org.example.footballmanager.newLogic.util.RuntimeSaveToDB;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;

import java.util.List;
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
    private final MatchRepository matchRepository;
    private final MatchTickStateRepository matchTickStateRepository;
    private final MatchEventRepository matchEventRepository;
    private final MatchPlayerStatsRepository matchPlayerStatsRepository;

    @Transactional
    public CompletableFuture<Match> startSimulation(long matchId) {

        Match match = matchEngine.loadAndValidateMatch(matchId);
        if (matchEngine.startSimulationOnlyIfNotRunning(matchId)) {
            return CompletableFuture.completedFuture(null);
        }

        try {
            MatchRuntime runtime = matchEngine.simulateFullMatch(match);
            Match saved = runtimeToDB.finalizeMatchResult(match, runtime.homePlayers, runtime.awayPlayers, runtime);
            playbackEngine.startPlayback(matchId, runtime);
            return CompletableFuture.completedFuture(saved);
        } finally {
            matchEngine.markSimulationFinished(matchId);
        }
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
            } finally {
                matchEngine.markSimulationFinished(matchId);
            }
        });
    }

    public boolean isSimulationRunning(long matchId) {
        return matchEngine.isSimulationRunning(matchId);
    }

    public CompletableFuture<Match> recoverAndRestartRealisticSimulation(long matchId) {
        resetPreparedMatch(matchId);
        return startRealisticSimulation(matchId);
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

    @org.springframework.transaction.annotation.Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void resetPreparedMatch(long matchId) {
        Match match = matchRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("Match not found: " + matchId));

        matchTickStateRepository.deleteByMatch(match);

        List<MatchEvent> events = matchEventRepository.findByMatch(match);
        if (!events.isEmpty()) {
            matchEventRepository.deleteAll(events);
        }

        var stats = matchPlayerStatsRepository.findByMatchId(matchId);
        if (!stats.isEmpty()) {
            matchPlayerStatsRepository.deleteAll(stats);
        }

        match.setHomeGoals(0);
        match.setAwayGoals(0);
        match.setAttendance(null);
        match.setPlayed(false);
        match.setStarted(false);
        match.setFinished(false);
        match.setEventJson(null);
        match.setHomeResultRevealed(true);
        match.setAwayResultRevealed(true);
        matchRepository.save(match);
        matchEngine.markSimulationFinished(matchId);
    }
}
