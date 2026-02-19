package org.example.footballmanager.simulator;

import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.dto.GameStateDTO;
import org.example.footballmanager.dto.MatchEventDTO;
import org.example.footballmanager.dto.PlayerPositionDTO;
import org.example.footballmanager.service.DemoMatchRuntime;
import org.example.footballmanager.util.DemoMatchEventWebSocketHandler;
import org.example.footballmanager.util.DemoPositionWebSocketHandler;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class BroadcastPositonHandling {
    private static final int TICK_MS = 250;
    private static final int MATCH_DURATION_SECONDS = 90;
    private final Map<Long, DemoMatchRuntime> runtimes = new ConcurrentHashMap<>();
    private final Set<Long> runningMatches = ConcurrentHashMap.newKeySet();
    private final Map<Long, ScheduledExecutorService> schedulers = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private final PlayerMovementService playerMovementService;
    private final PlayerDecisionActions playerDecisionActions;
    private final DemoMatchEventWebSocketHandler eventWs;
    private final DemoPositionWebSocketHandler positionWs;
    public BroadcastPositonHandling(
        DemoMatchEventWebSocketHandler eventWs,
        DemoPositionWebSocketHandler positionWs,
        PlayerMovementService playerMovementService,
        PlayerDecisionActions playerDecisionActions) {

    this.eventWs = eventWs;
    this.positionWs = positionWs;
    this.playerMovementService = playerMovementService;
    this.playerDecisionActions = playerDecisionActions;
}

    public void startPositionBroadcastLoop(long matchId, DemoMatchRuntime rt) {
            updatePlayerPositions(rt, random);
            handlePossessionAndActions(rt, random);
            updateBallPosition(rt);
            broadcastCurrentState(matchId, rt);
    }
    public void stopMatch(Long matchId) {
        ScheduledExecutorService scheduler = schedulers.remove(matchId);
        if (scheduler != null) scheduler.shutdownNow();
        runtimes.remove(matchId);
        runningMatches.remove(matchId);
        log.info("Canvas simulacija završena za meč {}", matchId);
    }
    public void updatePlayerPositions(DemoMatchRuntime rt, Random random) {
        for (PlayerPositionDTO p : rt.players) {
            boolean attacksRight = p.getTeam().equals("HOME");
            playerMovementService.movePlayerByRole(p, rt.players, random, attacksRight, rt);
        }
    }
    public void handlePossessionAndActions(DemoMatchRuntime rt, Random random) {
        if (rt.isShooting || rt.isRebounding) {
            return;
        }
        rt.possessionTicks++;
        if (rt.possessionTicks > 6 + random.nextInt(9)) {
            PlayerPositionDTO next = playerDecisionActions.chooseNextAction(rt.currentCarrier, rt.players, random, rt);
            if (next != null) rt.currentCarrier = next;
            rt.possessionTicks = 0;
        }

        rt.spacePassCooldown++;
        if (rt.spacePassCooldown > 8 && random.nextDouble() < 0.17) {
            playerDecisionActions.trySpacePass(rt.currentCarrier, rt.players, random, rt);
            rt.spacePassCooldown = 0;
        }
    }
    public void updateBallPosition(DemoMatchRuntime rt) {
        if (rt.isShooting) {
            playerDecisionActions.handleShotMovement(random, rt);
        } else if (rt.isRebounding) {
            playerDecisionActions.handleReboundMovement(rt.players, random, rt);
        } else {
            rt.ball.setX(rt.currentCarrier.getX());
            rt.ball.setY(rt.currentCarrier.getY());
        }
    }
    public void broadcastCurrentState(long matchId, DemoMatchRuntime rt) {
        GameStateDTO state = new GameStateDTO(
                rt.tick / (1000 / TICK_MS),
                new ArrayList<>(rt.players),
                rt.ball
        );
        positionWs.broadcast(matchId, state);
    }
    public void broadcastCurrentEvent(long matchId, MatchEventDTO dto) {
        eventWs.broadcast(matchId, dto);

    }
}