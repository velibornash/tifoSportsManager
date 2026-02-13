package org.example.footballmanager.service;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.dto.*;
import org.example.footballmanager.util.MatchEventWebSocketHandler;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

@Service
@RequiredArgsConstructor
public class CanvasSimulationService {

    private final MatchEventWebSocketHandler webSocketHandler;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public void startCanvasTestSimulation() {

        Random random = new Random();

        List<PlayerPositionDTO> players = new ArrayList<>();

        // 11 HOME
        for (int i = 1; i <= 11; i++) {
            players.add(new PlayerPositionDTO(i, "HOME",
                    10 + random.nextDouble() * 30,
                    10 + random.nextDouble() * 80));
        }

        // 11 AWAY
        for (int i = 12; i <= 22; i++) {
            players.add(new PlayerPositionDTO(i, "AWAY",
                    60 + random.nextDouble() * 30,
                    10 + random.nextDouble() * 80));
        }

        BallPositionDTO ball = new BallPositionDTO(50, 50);

        final int[] second = {0};

        scheduler.scheduleAtFixedRate(() -> {

            if (second[0] >= 90) {
                scheduler.shutdown();
                return;
            }

            // pomeri igrače malo random
            for (PlayerPositionDTO p : players) {
                p.setX(clamp(p.getX() + (random.nextDouble() - 0.5) * 5));
                p.setY(clamp(p.getY() + (random.nextDouble() - 0.5) * 5));
            }

            // lopta prati random igrača
            PlayerPositionDTO carrier = players.get(random.nextInt(players.size()));
            ball.setX(carrier.getX());
            ball.setY(carrier.getY());

            GameStateDTO state = new GameStateDTO(
                    second[0],
                    new ArrayList<>(players),
                    ball
            );

            webSocketHandler.broadcastEvent(state);

            second[0]++;

        }, 0, 1, TimeUnit.SECONDS);
    }

    private double clamp(double val) {
        return Math.max(0, Math.min(100, val));
    }
}
