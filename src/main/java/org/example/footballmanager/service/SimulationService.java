package org.example.footballmanager.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.engines.MatchEngine;
import org.example.footballmanager.engines.MatchPlaybackEngine;
import org.example.footballmanager.engines.MatchStatisticEngine;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.MatchRuntime;
import org.example.footballmanager.util.RuntimeSaveToDB;
import org.springframework.stereotype.Service;

import java.util.Random;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
public class SimulationService {

    private final MatchEngine matchEngine;
    private final MatchPlaybackEngine playbackEngine;
    private final MatchStatisticEngine matchStatisticEngine;
    private final RuntimeSaveToDB runtimeToDB;

    @Transactional
    public CompletableFuture<Match> startSimulation(long matchId) {

        Match match = matchEngine.loadAndValidateMatch(matchId);
        if (matchEngine.startSimulationOnlyIfNotRunning(matchId)) {
            return CompletableFuture.completedFuture(null);
        }

        // 1) Full simulation runtime
        MatchRuntime runtime = matchEngine.simulateFullMatch(match);

        // 1.5) Generate full additional event set before final save
        matchStatisticEngine.generateFakeAdditionalStats(
                match,
                runtime.homePlayers,
                runtime.awayPlayers,
                runtime.homeGoals,
                runtime.awayGoals,
                new Random()
        );

        // 2) Persist match + runtime events
        Match saved = runtimeToDB.finalizeMatchResult(match, runtime.homePlayers, runtime.awayPlayers, runtime);

        // 3) Playback stream
        playbackEngine.startPlayback(matchId, runtime);

        return CompletableFuture.completedFuture(saved);
    }
}
