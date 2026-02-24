package org.example.footballmanager.simulator.old;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.dto.BallPositionDTO;
import org.example.footballmanager.dto.GameStateDTO;
import org.example.footballmanager.dto.PlayerPositionDTO;
import org.example.footballmanager.service.old.DemoMatchRuntime;
import org.example.footballmanager.util.websocket.MatchEventWSHandler;
import org.example.footballmanager.util.websocket.PositionWSHandler;
import org.example.footballmanager.util.events.MatchEventMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.*;

@Component
@Slf4j
@RequiredArgsConstructor
public class MatchPlaybackEngine {

    private static final int TICK_MS = 300;

    private final PositionWSHandler positionWs;
    private final MatchEventWSHandler eventWs;
    private final PlayerMovementService movementService;
    private final MatchEventMapper mapper;
    private final PlayerDecisionActions decisionActions;
    private final Map<Long, ScheduledExecutorService> schedulers = new ConcurrentHashMap<>();
    private final Random random = new Random();

    public DemoMatchRuntime initializeRuntimeAndPositions(DemoMatchRuntime runtime) {
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

    public void start(long matchId, DemoMatchRuntime rt) {
                ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        schedulers.put(matchId, scheduler);
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (rt.tick >= 900) {
                    stop(matchId);
                    return;
                }
                // 1️⃣ update animacije
                updatePositions(rt);
                handlePossessionAndActions(rt, random);
                updateBallPosition(rt);
                // 2️⃣ broadcast pozicija
                broadcastState(matchId, rt);
                // 3️⃣ broadcast eventa tog minuta
                if (rt.tick % 10 == 0) {
                    int minute = rt.tick / 10;
                    broadcastMinuteEvents(matchId, rt, minute);
                }
                rt.tick++;
            } catch (Exception e) {
                log.error("Playback greška", e);
                stop(matchId);
            }
        }, 0, TICK_MS, TimeUnit.MILLISECONDS);
        log.info("Playback pokrenut za meč {}", matchId);
    }
    private void updatePositions(DemoMatchRuntime rt) {
        rt.players.forEach(p -> {
            boolean attacksRight = p.getTeam().equals("HOME");
            movementService.movePlayerByRole(
                    p,
                    rt.players,
                    ThreadLocalRandom.current(),
                    attacksRight,
                    rt
            );
        });
    }
    private void handlePossessionAndActions(DemoMatchRuntime rt, Random random) {
        if (rt.isShooting || rt.isRebounding) {
            return;
        }

        rt.possessionTicks++;
        if (rt.possessionTicks > 6 + random.nextInt(9)) {
            PlayerPositionDTO next = decisionActions.chooseNextAction(rt.currentCarrier, rt.players, random, rt);
            if (next != null) rt.currentCarrier = next;
            rt.possessionTicks = 0;
        }

        rt.spacePassCooldown++;
        if (rt.spacePassCooldown > 8 && random.nextDouble() < 0.17) {
            decisionActions.trySpacePass(rt.currentCarrier, rt.players, random, rt);
            rt.spacePassCooldown = 0;
        }
    }
    private void updateBallPosition(DemoMatchRuntime rt) {
        if (rt.isShooting) {
            decisionActions.handleShotMovement(random, rt);
        } else if (rt.isRebounding) {
            decisionActions.handleReboundMovement(rt.players, random, rt);
        } else {
            rt.ball.setX(rt.currentCarrier.getX());
            rt.ball.setY(rt.currentCarrier.getY());
        }
    }
    private void broadcastState(long matchId, DemoMatchRuntime rt) {

        GameStateDTO state = new GameStateDTO(
                rt.tick / 10,
                new ArrayList<>(rt.players),
                rt.ball
        );

        positionWs.broadcast(matchId, state);
    }
    public void broadcastMinuteEvents(long matchId, DemoMatchRuntime rt, int minute) {
        rt.runtimeEvents.stream()
                .filter(e -> e.getMinute() == minute)
                .forEach(e -> {
                    log.info("[{}'] Event: {}", minute, e.getDescription());
                    eventWs.broadcast(
                            matchId,
                            mapper.toDto(e)
                    );
                });
    }
    public void stop(long matchId) {

        ScheduledExecutorService scheduler = schedulers.remove(matchId);

        if (scheduler != null) {
            scheduler.shutdownNow();
        }

        log.info("Playback završen za meč {}", matchId);
    }
}