package org.example.footballmanager.service;

import org.example.footballmanager.engines.MatchEngine;
import org.example.footballmanager.engines.MatchPlaybackEngine;
import org.example.footballmanager.engines.MatchStatisticEngine;
import org.example.footballmanager.engines.RealisticMatchEngine;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.MatchRuntime;
import org.example.footballmanager.util.RuntimeSaveToDB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimulationServiceTest {

    @Mock private MatchEngine matchEngine;
    @Mock private MatchPlaybackEngine playbackEngine;
    @Mock private MatchStatisticEngine matchStatisticEngine;
    @Mock private RuntimeSaveToDB runtimeToDB;
    @Mock private RealisticMatchEngine realisticMatchEngine;

    @InjectMocks private SimulationService simulationService;

    @Test
    void startSimulationKeepsRuntimeEventsWithoutAddingSyntheticStats() {
        Match match = new Match();
        match.setId(11L);
        MatchRuntime runtime = new MatchRuntime();

        when(matchEngine.loadAndValidateMatch(11L)).thenReturn(match);
        when(matchEngine.startSimulationOnlyIfNotRunning(11L)).thenReturn(false);
        when(matchEngine.simulateFullMatch(match)).thenReturn(runtime);
        when(runtimeToDB.finalizeMatchResult(match, runtime.homePlayers, runtime.awayPlayers, runtime)).thenReturn(match);

        CompletableFuture<Match> result = simulationService.startSimulation(11L);

        assertSame(match, result.join());
        verify(runtimeToDB).finalizeMatchResult(match, runtime.homePlayers, runtime.awayPlayers, runtime);
        verify(playbackEngine).startPlayback(11L, runtime);
        verifyNoInteractions(matchStatisticEngine);
    }

    @Test
    void startRealisticSimulationReloadsMatchBeforeTransactionalFinalize() {
        Match simulatedMatch = new Match();
        simulatedMatch.setId(21L);

        Match managedMatch = new Match();
        managedMatch.setId(21L);

        MatchRuntime runtime = new MatchRuntime();

        when(matchEngine.loadAndValidateMatch(21L)).thenReturn(simulatedMatch, managedMatch);
        when(matchEngine.startSimulationOnlyIfNotRunning(21L)).thenReturn(false);
        when(realisticMatchEngine.simulateRealisticMatch(simulatedMatch)).thenReturn(runtime);
        when(runtimeToDB.finalizeMatchResult(managedMatch, runtime.homePlayers, runtime.awayPlayers, runtime)).thenReturn(managedMatch);

        CompletableFuture<Match> result = simulationService.startRealisticSimulation(21L);

        assertSame(managedMatch, result.join());
        verify(runtimeToDB).finalizeMatchResult(managedMatch, runtime.homePlayers, runtime.awayPlayers, runtime);
        verify(playbackEngine).startPlayback(21L, runtime);
    }
}