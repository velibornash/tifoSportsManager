package org.example.footballmanager.service;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.dto.*;
import org.example.footballmanager.util.MatchEventWebSocketHandler;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CanvasSimulationService {

    private final MatchEventWebSocketHandler webSocketHandler;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

    private static final int TICK_MS = 250; // 4x u sekundi
    private static final int MATCH_DURATION_SECONDS = 90;

    public void startCanvasTestSimulation() {

        Random random = new Random();
        List<PlayerPositionDTO> players = new ArrayList<>();

        // 11 HOME (leva polovina)
        for (int i = 1; i <= 11; i++) {
            players.add(new PlayerPositionDTO(
                    i,
                    "HOME",
                    10 + random.nextDouble() * 30,
                    10 + random.nextDouble() * 80
            ));
        }

        // 11 AWAY (desna polovina)
        for (int i = 12; i <= 22; i++) {
            players.add(new PlayerPositionDTO(
                    i,
                    "AWAY",
                    60 + random.nextDouble() * 30,
                    10 + random.nextDouble() * 80
            ));
        }

        BallPositionDTO ball = new BallPositionDTO(50, 50);

        final int[] tick = {0};
        final int totalTicks = MATCH_DURATION_SECONDS * (1000 / TICK_MS);

        PlayerPositionDTO[] currentCarrier = {players.get(0)};
        int[] possessionTicks = {0};

        scheduler.scheduleAtFixedRate(() -> {

            if (tick[0] >= totalTicks) {
                scheduler.shutdown();
                return;
            }

            // ==============================
            // 1️⃣ SMISLENO KRETANJE
            // ==============================
            for (PlayerPositionDTO p : players) {

                if (p.getTeam().equals("HOME")) {
                    // HOME napada desno
                    p.setX(clamp(p.getX() + random.nextDouble() * 1.5));
                } else {
                    // AWAY napada levo
                    p.setX(clamp(p.getX() - random.nextDouble() * 1.5));
                }

                // malo gore-dole kretanja
                p.setY(clamp(p.getY() + (random.nextDouble() - 0.5) * 2));
            }

            // ==============================
            // 2️⃣ POSSESSION + PASS LOGIKA
            // ==============================
            possessionTicks[0]++;

            if (possessionTicks[0] > 8) { // ~2 sekunde drži loptu

                PlayerPositionDTO next =
                        findNearbyTeammate(currentCarrier[0], players);

                if (next != null) {
                    currentCarrier[0] = next;
                }

                possessionTicks[0] = 0;
            }

            // lopta prati trenutnog igrača
            ball.setX(currentCarrier[0].getX());
            ball.setY(currentCarrier[0].getY());

            // ==============================
            // 3️⃣ SLANJE GAME STATE
            // ==============================
            GameStateDTO state = new GameStateDTO(
                    tick[0] / (1000 / TICK_MS), // sekunde
                    new ArrayList<>(players),
                    ball
            );

            webSocketHandler.broadcastEvent(state);

            tick[0]++;

        }, 0, TICK_MS, TimeUnit.MILLISECONDS);
    }

    // ======================================
    // Helper: pronalazi jednog od 3 najbliža saigrača
    // ======================================
    private PlayerPositionDTO findNearbyTeammate(
            PlayerPositionDTO carrier,
            List<PlayerPositionDTO> players) {

        Random random = new Random();
        List<PlayerPositionDTO> candidates = players.stream()
                .filter(p -> p.getId() != carrier.getId())
                .filter(p -> p.getTeam().equals(carrier.getTeam()))
                .sorted(Comparator.comparingDouble(p -> distance(carrier, p)))
                .limit(3)                    // ← uzimamo samo 3 najbliža
                .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            return null;                 // ili return carrier;
        }

        // Nasumično biramo jednog od tri
        return candidates.get(random.nextInt(candidates.size()));
    }

    private double distance(PlayerPositionDTO a, PlayerPositionDTO b) {
        return Math.sqrt(
                Math.pow(a.getX() - b.getX(), 2) +
                        Math.pow(a.getY() - b.getY(), 2)
        );
    }

    private double clamp(double val) {
        return Math.max(0, Math.min(100, val));
    }
}
