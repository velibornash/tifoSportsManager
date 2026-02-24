package org.example.footballmanager.simulator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.dto.GameStateDTO;
import org.example.footballmanager.util.MatchRuntime;
import org.example.footballmanager.util.events.MatchEventMapper;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import org.example.footballmanager.util.websocket.MatchEventWSHandler;
import org.example.footballmanager.util.websocket.PositionWSHandler;

@Component
@Slf4j
@RequiredArgsConstructor
public class BroadcastEngine {
    private final PositionWSHandler positionWs;
    private final MatchEventWSHandler eventWs;
    private final MatchEventMapper mapper;

    public void broadcastState(long matchId, MatchRuntime rt) {

        GameStateDTO state = new GameStateDTO(
                rt.tick / 10,
                new ArrayList<>(rt.players),
                rt.ball
        );

        positionWs.broadcast(matchId, state);
    }
    public void broadcastMinuteEvents(long matchId, MatchRuntime rt, int minute) {
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
}