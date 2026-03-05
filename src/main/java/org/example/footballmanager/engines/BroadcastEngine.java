package org.example.footballmanager.engines;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.dto.GameStateDTO;
import org.example.footballmanager.model.MatchRuntime;
import org.example.footballmanager.model.event.MatchEvent;
import org.example.footballmanager.util.events.MatchEventMapper;
import org.example.footballmanager.util.websocket.MatchEventWSHandler;
import org.example.footballmanager.util.websocket.PositionWSHandler;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
@RequiredArgsConstructor
public class BroadcastEngine {

    public final PositionWSHandler positionWsHandler;
    public final MatchEventWSHandler eventWsHandler;
    private final MatchEventMapper mapper;

    public void broadcastState(long matchId, MatchRuntime rt) {
        int ticksPerMinute = rt.ticksPerMinute > 0 ? rt.ticksPerMinute : 27;
        GameStateDTO state = new GameStateDTO(
                Math.min(90, rt.tick / ticksPerMinute + 1),
                new ArrayList<>(rt.players),
                rt.ball
        );
        broadcastState(matchId, state);
    }

    public void broadcastState(long matchId, GameStateDTO state) {
        if (!positionWsHandler.hasActiveSessions(matchId)) {
            return;
        }
        positionWsHandler.broadcast(matchId, state);
    }

    public void broadcastMinuteEvents(long matchId, MatchRuntime rt, int minute) {
        List<MatchEvent> events = rt.runtimeEvents.stream()
                .filter(e -> e.getMinute() == minute)
                .toList();
        broadcastMinuteEvents(matchId, events, minute);
    }

    public void broadcastMinuteEvents(long matchId, List<MatchEvent> events, int minute) {
        if (!eventWsHandler.hasActiveSessions(matchId)) {
            return;
        }

        events.forEach(e -> {
            log.info("[{}'] Event: {}", minute, e.getDescription());
            eventWsHandler.broadcast(matchId, mapper.toDto(e));
        });
    }

    public void broadcastPossession(
            long matchId,
            int minute,
            String teamName,
            String playerName,
            Integer playerAge,
            Double playerHeight,
            Double playerWeight,
            Integer playerTotalGoals,
            Integer playerTotalAssists
    ) {
        if (!eventWsHandler.hasActiveSessions(matchId)) {
            return;
        }

        eventWsHandler.broadcast(matchId, Map.of(
                "type", "possession",
                "minute", minute,
                "teamName", teamName,
                "playerName", playerName != null ? playerName : "",
                "playerAge", playerAge != null ? playerAge : 0,
                "playerHeight", playerHeight != null ? playerHeight : 0.0,
                "playerWeight", playerWeight != null ? playerWeight : 0,
                "playerTotalGoals", playerTotalGoals != null ? playerTotalGoals : 0,
                "playerTotalAssists", playerTotalAssists != null ? playerTotalAssists : 0
        ));
    }
}
