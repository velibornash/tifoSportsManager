package org.example.footballmanager.engines;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.dto.BallPositionDTO;
import org.example.footballmanager.dto.PlayerPositionDTO;
import org.example.footballmanager.service.PlayerMovementDecisionService;
import org.example.footballmanager.model.MatchRuntime;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.*;

@Component
@Slf4j
@RequiredArgsConstructor
public class MatchPlaybackEngine {

    private static final int TICK_MS = 300;

    private final PlayerMovementDecisionService movementService;
    private final Map<Long, ScheduledExecutorService> schedulers = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private final BroadcastEngine broadcastEngine;

    public MatchRuntime initializeRuntimeAndPositions(MatchRuntime runtime) {
         Random r = new Random();
        // Home players (1–11)
        for (int i = 1; i <= 11; i++) {
            runtime.players.add(new PlayerPositionDTO(i, "HOME", 10 + r.nextDouble() * 35, 10 + r.nextDouble() * 80, 0,0));
        }
        // Away players (12–22)
        for (int i = 12; i <= 22; i++) {
            runtime.players.add(new PlayerPositionDTO(i, "AWAY", 65 + r.nextDouble() * 30, 10 + r.nextDouble() * 80, 0,0));
        }
        runtime.ball = new BallPositionDTO(50, 50);
        runtime.currentCarrier = runtime.players.getFirst();
        return runtime;
    }
    public void startPlayback(long matchId, MatchRuntime rt) {
        // Proveri da li ima ikoga pre pokretanja
/*        if (!broadcastEngine.positionWsHandler.hasActiveSessions(matchId) &&
                !broadcastEngine.eventWsHandler.hasActiveSessions(matchId)) {
            log.info("Nema gledalaca za match {} – ne pokrećem playback", matchId);
            return;
        }*/
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        schedulers.put(matchId, scheduler);
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (rt.tick >= 900) {
                    stopPlayback(matchId);
                    return;
                }
                // 1️⃣ update animacije
                movementService.updatePositions(rt);
                movementService.handlePossessionAndActions(rt, random);
                movementService.updateBallPosition(rt);
                // 2️⃣ broadcast pozicija
                broadcastEngine.broadcastState(matchId, rt);
                // 3️⃣ broadcast eventa tog minuta
                if (rt.tick % 10 == 0) {
                    int minute = rt.tick / 10;
                    broadcastEngine.broadcastMinuteEvents(matchId, rt, minute);
                }
                rt.tick++;
            } catch (Exception e) {
                log.error("Playback greška", e);
                stopPlayback(matchId);
            }
        }, 0, TICK_MS, TimeUnit.MILLISECONDS);
        log.info("Playback pokrenut za meč {}", matchId);
    }
    public void stopPlayback(long matchId) {

        ScheduledExecutorService scheduler = schedulers.remove(matchId);

        if (scheduler != null) {
            scheduler.shutdownNow();
        }

        log.info("Playback završen za meč {}", matchId);
    }
}