package org.example.footballmanager.engines;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.dto.BallPositionDTO;
import org.example.footballmanager.dto.GameStateDTO;
import org.example.footballmanager.dto.PlayerPositionDTO;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.MatchRuntime;
import org.example.footballmanager.model.MatchTickState;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.event.MatchEvent;
import org.example.footballmanager.repository.MatchEventRepository;
import org.example.footballmanager.repository.MatchRepository;
import org.example.footballmanager.repository.MatchTickStateRepository;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

@Component
@Slf4j
@RequiredArgsConstructor
public class MatchPlaybackEngine {

    private static final int TICK_MS = 480;

    private final Map<Long, ScheduledExecutorService> schedulers = new ConcurrentHashMap<>();
    private final BroadcastEngine broadcastEngine;
    private final MatchTickStateRepository tickStateRepository;
    private final MatchRepository matchRepository;
    private final MatchEventRepository matchEventRepository;
    private final ObjectMapper objectMapper;

    public MatchRuntime initializeRuntimeAndPositions(MatchRuntime runtime) {
        Random r = new Random();
        for (int i = 1; i <= 11; i++) {
            runtime.players.add(new PlayerPositionDTO(i, "HOME", 10 + r.nextDouble() * 35, 10 + r.nextDouble() * 80, 0, 0));
        }
        for (int i = 12; i <= 22; i++) {
            runtime.players.add(new PlayerPositionDTO(i, "AWAY", 65 + r.nextDouble() * 30, 10 + r.nextDouble() * 80, 0, 0));
        }
        runtime.ball = new BallPositionDTO(50, 50);
        runtime.currentCarrier = runtime.players.getFirst();
        return runtime;
    }

    public void startPlayback(long matchId, MatchRuntime rt) {
        stopPlayback(matchId);

        List<MatchRuntime.TickState> frames = loadFrames(matchId, rt);
        if (frames.isEmpty()) {
            log.warn("No tick frames available for match {}. Playback not started.", matchId);
            return;
        }

        List<MatchEvent> events = loadEvents(matchId, rt);
        Map<Integer, List<MatchEvent>> eventsByMinute = indexEventsByMinute(events);
        Match match = loadMatch(matchId, rt);
        Map<String, String> teamNames = resolveTeamNames(match, events);
        Map<Integer, Player> playersByPositionId = mapPlayersByPositionId(match);

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        schedulers.put(matchId, scheduler);

        final int[] frameIndex = {0};
        final int[] lastBroadcastMinute = {-1};

        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (frameIndex[0] >= frames.size()) {
                    stopPlayback(matchId);
                    return;
                }

                MatchRuntime.TickState frame = frames.get(frameIndex[0]++);
                int minute = frame.tick / 10 + 1;

                GameStateDTO state = new GameStateDTO(minute, frame.players, frame.ball);
                broadcastEngine.broadcastState(matchId, state);

                if (minute != lastBroadcastMinute[0]) {
                    List<MatchEvent> minuteEvents = eventsByMinute.getOrDefault(minute, List.of());
                    broadcastEngine.broadcastMinuteEvents(matchId, minuteEvents, minute);
                    if (minuteEvents.isEmpty()) {
                        Player carrierPlayer = getCarrierPlayer(frame, playersByPositionId);
                        broadcastEngine.broadcastPossession(
                                matchId,
                                minute,
                                getTeamInPossession(frame, teamNames),
                                carrierPlayer != null ? carrierPlayer.getName() : "",
                                carrierPlayer != null ? carrierPlayer.getAge() : null,
                                carrierPlayer != null ? carrierPlayer.getHeight() : null,
                                carrierPlayer != null ? carrierPlayer.getWeight() : null,
                                carrierPlayer != null ? carrierPlayer.getTotalGoals() : null,
                                carrierPlayer != null ? carrierPlayer.getTotalAssists() : null
                        );
                    }
                    lastBroadcastMinute[0] = minute;
                }
            } catch (Exception e) {
                log.error("Playback error for match {}", matchId, e);
                stopPlayback(matchId);
            }
        }, 0, TICK_MS, TimeUnit.MILLISECONDS);

        log.info("Playback started for match {} with {} frames", matchId, frames.size());
    }

    public void startPlayback(long matchId) {
        startPlayback(matchId, null);
    }

    public void stopPlayback(long matchId) {
        ScheduledExecutorService scheduler = schedulers.remove(matchId);
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        log.info("Playback finished for match {}", matchId);
    }

    private List<MatchRuntime.TickState> loadFrames(long matchId, MatchRuntime rt) {
        if (rt != null && rt.tickStates != null && !rt.tickStates.isEmpty()) {
            return rt.tickStates;
        }

        Match match = matchRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("Match not found: " + matchId));

        List<MatchTickState> stored = tickStateRepository.findByMatchOrderByTickAsc(match);
        if (stored.isEmpty()) {
            return List.of();
        }

        List<MatchRuntime.TickState> frames = new ArrayList<>(stored.size());
        for (MatchTickState s : stored) {
            try {
                List<PlayerPositionDTO> players = objectMapper.readValue(
                        s.getPlayerPositionsJson(),
                        new TypeReference<List<PlayerPositionDTO>>() {}
                );
                BallPositionDTO ball = objectMapper.readValue(s.getBallPositionJson(), BallPositionDTO.class);
                int carrierId = s.getCurrentCarrierId() != null ? s.getCurrentCarrierId() : -1;
                frames.add(new MatchRuntime.TickState(s.getTick(), players, ball, carrierId, null));
            } catch (Exception ex) {
                log.error("Failed to parse stored tick {} for match {}", s.getTick(), matchId, ex);
            }
        }

        return frames;
    }

    private List<MatchEvent> loadEvents(long matchId, MatchRuntime rt) {
        if (rt != null && rt.runtimeEvents != null && !rt.runtimeEvents.isEmpty()) {
            return rt.runtimeEvents;
        }

        Match match = matchRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("Match not found: " + matchId));

        List<MatchEvent> events = matchEventRepository.findByMatch(match);
        events.sort(Comparator
                .comparingInt(MatchEvent::getMinute)
                .thenComparing(e -> e.getId() == null ? 0L : e.getId()));
        return events;
    }

    private Match loadMatch(long matchId, MatchRuntime rt) {
        if (rt != null && rt.matchRef != null) {
            return rt.matchRef;
        }
        return matchRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("Match not found: " + matchId));
    }

    private Map<Integer, List<MatchEvent>> indexEventsByMinute(List<MatchEvent> events) {
        Map<Integer, List<MatchEvent>> byMinute = new HashMap<>();
        for (MatchEvent event : events) {
            byMinute.computeIfAbsent(event.getMinute(), k -> new ArrayList<>()).add(event);
        }
        return byMinute;
    }

    private String getTeamInPossession(MatchRuntime.TickState frame, Map<String, String> teamNames) {
        if (frame == null || frame.players == null || frame.players.isEmpty()) {
            return "In transition";
        }

        return frame.players.stream()
                .filter(p -> p.getId() == frame.carrierId)
                .map(p -> teamNames.getOrDefault(p.getTeam(), p.getTeam()))
                .findFirst()
                .orElse("In transition");
    }

    private Map<String, String> resolveTeamNames(Match match, List<MatchEvent> events) {
        String homeName = match != null && match.getHomeTeam() != null ? match.getHomeTeam().getName() : null;
        String awayName = match != null && match.getAwayTeam() != null ? match.getAwayTeam().getName() : null;

        if ((homeName == null || awayName == null) && events != null) {
            for (MatchEvent event : events) {
                if (event instanceof org.example.footballmanager.model.event.MatchStartEvent startEvent) {
                    if (homeName == null) {
                        homeName = startEvent.getHomeTeamName();
                    }
                    if (awayName == null) {
                        awayName = startEvent.getAwayTeamName();
                    }
                }
            }
        }

        Map<String, String> names = new HashMap<>();
        names.put("HOME", homeName != null ? homeName : "Home");
        names.put("AWAY", awayName != null ? awayName : "Away");
        return names;
    }

    private Map<Integer, Player> mapPlayersByPositionId(Match match) {
        Map<Integer, Player> result = new HashMap<>();
        if (match == null) {
            return result;
        }

        List<Player> home = match.getHomeLineup() != null ? match.getHomeLineup().getStartingPlayers() : List.of();
        List<Player> away = match.getAwayLineup() != null ? match.getAwayLineup().getStartingPlayers() : List.of();

        for (int i = 0; i < Math.min(11, home.size()); i++) {
            result.put(i + 1, home.get(i));
        }
        for (int i = 0; i < Math.min(11, away.size()); i++) {
            result.put(i + 12, away.get(i));
        }

        return result;
    }

    private Player getCarrierPlayer(MatchRuntime.TickState frame, Map<Integer, Player> playersByPositionId) {
        if (frame == null || frame.carrierId < 0) {
            return null;
        }
        return playersByPositionId.get(frame.carrierId);
    }
}
